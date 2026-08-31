package com.claudecode.tools.workflows;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.permissions.DecisionReason;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskIdGenerator;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;

/** Executes, validates, loads, and cancels dynamic workflow tasks. */
@BuiltInTool(name = "Workflow", aliases = {"RunWorkflow"})
public final class WorkflowTool extends AnnotatedTool<JsonNode, Object> {

    private static final String DISABLED_MANAGED =
        "Dynamic workflows are disabled by managed settings (`disableWorkflows`).";
    private static final String DISABLED_SESSION =
        "Dynamic workflows are not enabled for this session (org policy, launch gate, or the \"Dynamic workflows\" setting in /config).";
    private static final String MISSING_INPUT = "Must provide script, name, or scriptPath";

    private final WorkflowRuntime runtime;
    private final WorkflowCatalog catalog;
    private final TaskRegistry taskRegistry;
    private final WorkflowRunStore runStore;
    private final Path claudeHome;
    private final boolean enabled;
    private final boolean managedDisabled;

    public WorkflowTool(WorkflowRuntime runtime, WorkflowCatalog catalog,
                        TaskRegistry taskRegistry, WorkflowRunStore runStore,
                        Path claudeHome, boolean enabled) {
        this(runtime, catalog, taskRegistry, runStore, claudeHome, enabled, false);
    }

    public WorkflowTool(WorkflowRuntime runtime, WorkflowCatalog catalog,
                        TaskRegistry taskRegistry, WorkflowRunStore runStore,
                        Path claudeHome, boolean enabled, boolean managedDisabled) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.taskRegistry = Objects.requireNonNull(taskRegistry, "taskRegistry");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.claudeHome = Objects.requireNonNull(claudeHome, "claudeHome");
        this.enabled = enabled;
        this.managedDisabled = managedDisabled;
    }
    @Override
    public String description() {
        return ToolTexts.description("Workflow");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("script")
            .put("description", "Self-contained workflow script. Must begin with `export const meta = { name, description, phases }` (pure literal, no computed values) followed by the script body using agent()/parallel()/pipeline()/phase().")
            .put("type", "string")
            .put("maxLength", WorkflowScriptParser.MAX_SCRIPT_BYTES);
        properties.putObject("name")
            .put("description", "Name of a predefined workflow (built-in or from .claude/workflows/). Resolves to a self-contained script.")
            .put("type", "string");
        properties.putObject("description")
            .put("description", "Ignored — set the workflow description in the script's `meta` block.")
            .put("type", "string");
        properties.putObject("title")
            .put("description", "Ignored — set the workflow title in the script's `meta` block.")
            .put("type", "string");
        properties.putObject("args")
            .put("description", "Optional input value exposed to the script as the global `args`, verbatim. Pass arrays/objects as actual JSON values, NOT as a JSON-encoded string — a stringified list breaks `args.filter`/`args.map` in the script. Use for parameterized named workflows (e.g. a research question).");
        properties.putObject("scriptPath")
            .put("description", "Path to a workflow script file on disk. Every Workflow invocation persists its script under the session directory and returns the path in the tool result. To iterate, edit that file with Write/Edit and re-invoke Workflow with the same `scriptPath` instead of re-sending the full script. Takes precedence over `script` and `name`.")
            .put("type", "string");
        properties.putObject("resumeFromRunId")
            .put("description", "Run ID of a prior Workflow invocation to resume from. Completed agent() calls with unchanged (prompt, opts) return their cached results instantly; only edited or new calls re-run. Same-session only. Stop the prior run first (TaskStop) before resuming.")
            .put("type", "string")
            .put("pattern", "^wf_[a-z0-9-]{6,}$");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public boolean isEnabled() { return enabled && !managedDisabled; }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        String name = text(input, "name");
        boolean namedRuleEligible = text(input, "scriptPath") == null && name != null;
        PermissionRule denyRule = namedRuleEligible
            ? namedRule(context, name, PermissionBehavior.DENY).orElse(null) : null;
        if (denyRule != null) {
            return new PermissionDecision.Deny(
                "Workflow " + name + " blocked by permission rules",
                new DecisionReason.Rule(denyRule));
        }
        Path cwd = context == null || context.workingDirectory() == null
            ? Path.of(System.getProperty("user.dir")) : context.workingDirectory();
        ResolvedWorkflow resolved = resolve(input, cwd.toAbsolutePath().normalize());
        ObjectNode updated = input != null && input.isObject()
            ? ((ObjectNode) input).deepCopy() : mapper().createObjectNode();
        updated.put("script", resolved.script());
        if (namedRuleEligible
                && namedRule(context, name, PermissionBehavior.ASK).isPresent()) {
            return new PermissionDecision.Ask(null, updated,
                "Review dynamic workflow before running", null, null);
        }
        if (namedRuleEligible
                && namedRule(context, name, PermissionBehavior.ALLOW).isPresent()) {
            return PermissionDecision.allow();
        }
        List<PermissionUpdate> suggestions = !namedRuleEligible ? List.of()
            : List.of(new PermissionUpdate.AddRules(
                List.of(new PermissionUpdate.RuleValue(name(), name)),
                PermissionUpdate.Behavior.ALLOW,
                PermissionUpdate.Destination.LOCAL_SETTINGS));
        return new PermissionDecision.Ask(null, updated,
            "Review dynamic workflow before running", null, null, suggestions);
    }

    private static Optional<PermissionRule> namedRule(ToolPermissionContext context,
                                                       String name,
                                                       PermissionBehavior behavior) {
        if (context == null) return Optional.empty();
        return context.rules().stream()
            .filter(rule -> Strings.CS.equals("Workflow", rule.toolName()))
            .filter(rule -> rule.behavior() == behavior)
            .filter(rule -> rule.pattern().filter(name::equals).isPresent())
            .findFirst();
    }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        if (managedDisabled) return ValidationResult.invalid(DISABLED_MANAGED);
        if (!enabled) return ValidationResult.invalid(DISABLED_SESSION);
        try {
            ResolvedWorkflow resolved = resolve(input, context);
            validateControlCharacters(resolved.script());
            ParsedWorkflowScript parsed = WorkflowScriptParser.parse(resolved.script());
            if (text(input, "script") != null) {
                WorkflowDeterminism.validate(parsed.body());
            }
            String resumeRunId = text(input, "resumeFromRunId");
            if (resumeRunId != null) {
                WorkflowRun previous = priorRun(resumeRunId, context).orElse(null);
                if (previous != null && !previous.status().isTerminal()) {
                    return ValidationResult.invalid("Workflow " + resumeRunId
                        + " is still running (task " + previous.taskId()
                        + "). Stop it first with TaskStop({taskId: \""
                        + previous.taskId() + "\"}) before resuming.");
                }
            }
            return ValidationResult.valid();
        } catch (WorkflowInputException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (WorkflowScriptException e) {
            return ValidationResult.invalid("Invalid workflow script: " + e.getMessage());
        } catch (RuntimeException e) {
            return ValidationResult.invalid(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        ResolvedWorkflow resolved = resolve(input, context);
        validateControlCharacters(resolved.script());
        ParsedWorkflowScript parsed = WorkflowScriptParser.parse(resolved.script());
        WorkflowDefinition definition = new WorkflowDefinition(parsed.metadata(),
            resolved.script(), parsed.body(), resolved.source(), resolved.path(),
            resolved.pluginName(), false, text(input, "script") != null);

        String resumeFromRunId = text(input, "resumeFromRunId");
        String runId = resumeFromRunId != null
            ? resumeFromRunId
            : "wf_" + UUID.randomUUID().toString().substring(0, 12);
        String taskId = TaskIdGenerator.generate(TaskType.LOCAL_WORKFLOW);
        String syntaxError = runtime.validate(definition).orElse(null);
        if (syntaxError != null) {
            ObjectNode data = mapper().createObjectNode();
            data.put("status", "async_launched");
            data.put("taskId", taskId);
            data.put("taskType", "local_workflow");
            data.put("workflowName", parsed.metadata().name());
            data.put("runId", runId);
            data.put("summary", parsed.metadata().description());
            data.put("error", syntaxError);
            return new StructuredToolOutput(
                "Workflow script has a syntax error and was not launched:\n" + syntaxError,
                data);
        }
        SessionManager sessions = new SessionManager(claudeHome, context.workingDirectory());
        Path transcriptDir = sessions.getWorkflowTranscriptDir(context.sessionId(), runId);
        Path scriptPath = resolved.path();
        if (scriptPath == null) {
            scriptPath = sessions.getProjectDir().resolve(context.sessionId())
                .resolve("workflows").resolve("scripts")
                .resolve(sanitizeScriptName(parsed.metadata().name()) + "-" + runId + ".js");
            persistScript(scriptPath, resolved.script());
            definition = new WorkflowDefinition(parsed.metadata(), resolved.script(),
                parsed.body(), resolved.source(), scriptPath, resolved.pluginName(), false,
                text(input, "script") != null);
        }

        TaskState task = taskRegistry.store().createWithId(taskId, TaskType.LOCAL_WORKFLOW,
            parsed.metadata().description(), context.agentId());
        if (context.toolUseId() != null) {
            task = taskRegistry.store().updateToolUseId(task.id(), context.toolUseId());
        }
        long startTime = System.currentTimeMillis();
        Path outputFile = TaskOutputPaths.outputPath(task.id(), context);
        WorkflowRun run = WorkflowRun.builder(runId, task.id(), TaskStatus.PENDING)
            .workflowName(parsed.metadata().name())
            .summary(parsed.metadata().description())
            .script(resolved.script())
            .scriptPath(scriptPath.toAbsolutePath().normalize())
            .transcriptDir(transcriptDir)
            .outputFile(outputFile)
            .runFile(sessions.getWorkflowRunPath(context.sessionId(), runId))
            .timestamp(Instant.now())
            .startTime(startTime)
            .defaultModel(context.currentModel())
            .agentCache(resumeFromRunId == null ? List.of()
                : priorRun(resumeFromRunId, context)
                    .map(WorkflowRun::agentCache).orElse(List.of()))
            .args(input == null ? null : input.get("args"))
            .phases(parsed.metadata().phases())
            .title(parsed.metadata().title())
            .build();
        runStore.put(run);

        WorkflowTask handle = new WorkflowTask(runtime, definition,
            input == null ? null : input.get("args"),
            context, taskRegistry, runStore, run);
        taskRegistry.registerWorkflow(handle);
        if (context.messageQueueManager() != null) {
            context.messageQueueManager().enqueueSdkEvent(new SDKMessage.TaskStarted(
                task.id(), context.toolUseId(), parsed.metadata().description(),
                "local_workflow", parsed.metadata().name(), resolved.script(), null));
        }
        handle.start();

        ObjectNode data = mapper().createObjectNode();
        data.put("status", "async_launched");
        data.put("taskId", task.id());
        data.put("taskType", "local_workflow");
        data.put("workflowName", parsed.metadata().name());
        data.put("runId", runId);
        data.put("summary", parsed.metadata().description());
        data.put("transcriptDir", transcriptDir.toString());
        data.put("scriptPath", scriptPath.toAbsolutePath().normalize().toString());

        String text = "Workflow launched in background. Task ID: " + task.id()
            + "\nSummary: " + parsed.metadata().description()
            + "\nTranscript dir: " + transcriptDir
            + "\nScript file: " + scriptPath.toAbsolutePath().normalize()
            + "\n(Edit this file with Write/Edit and re-invoke Workflow with {scriptPath: \""
            + scriptPath.toAbsolutePath().normalize()
            + "\"} to iterate without resending the script.)"
            + "\nRun ID: " + runId
            + "\nTo resume after editing the script: Workflow({scriptPath: \""
            + scriptPath.toAbsolutePath().normalize() + "\", resumeFromRunId: \""
            + runId + "\"}) — completed agents return cached results."
            + "\n\nYou will be notified when it completes. Use /workflows to watch live progress.";
        return new StructuredToolOutput(text, data);
    }

    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input,
                                ToolExecutionContext context) {
        if (!(rawResult instanceof StructuredToolOutput(String text, Object toolUseResult))
                || !(toolUseResult instanceof JsonNode data)
                || !data.hasNonNull("error")) {
            return null;
        }
        return ToolResult.error(text).withToolUseResult(data);
    }

    private ResolvedWorkflow resolve(JsonNode input, ToolExecutionContext context) {
        Path cwd = Path.of(context.workingDirectory()).toAbsolutePath().normalize();
        return resolve(input, cwd);
    }

    private ResolvedWorkflow resolve(JsonNode input, Path cwd) {
        String scriptPathText = text(input, "scriptPath");
        String inlineScript = text(input, "script");
        if (scriptPathText != null) {
            if (Strings.CS.startsWith(scriptPathText, "\\\\") || Strings.CS.startsWith(scriptPathText, "//")) {
                throw new WorkflowInputException(
                    "UNC paths are not allowed for workflow scriptPath: " + scriptPathText);
            }
            Path path = Path.of(scriptPathText);
            if (!path.isAbsolute()) path = cwd.resolve(path);
            path = path.toAbsolutePath().normalize();
            if (inlineScript != null) {
                return new ResolvedWorkflow(inlineScript, path, WorkflowSource.PROJECT, null);
            }
            return new ResolvedWorkflow(readScript(path), path, WorkflowSource.PROJECT, null);
        }

        String name = text(input, "name");
        if (name != null) {
            WorkflowDefinition named = catalog.find(name, cwd).orElse(null);
            if (named == null) {
                String available = String.join(", ", catalog.load(cwd).stream()
                    .map(definition -> definition.metadata().name()).toList());
                throw new WorkflowInputException("Workflow \"" + name
                    + "\" not found. Available: " + (available.isEmpty() ? "(none)" : available));
            }
            return new ResolvedWorkflow(inlineScript != null ? inlineScript : named.script(),
                inlineScript != null ? null : named.path(), named.source(), named.pluginName());
        }
        if (inlineScript != null) {
            return new ResolvedWorkflow(inlineScript, null, WorkflowSource.USER, null);
        }
        throw new WorkflowInputException(MISSING_INPUT);
    }

    private Optional<WorkflowRun> priorRun(String runId, ToolExecutionContext context) {
        Optional<WorkflowRun> live = runStore.get(runId);
        if (live.isPresent()) return live;
        SessionManager sessions = new SessionManager(claudeHome, context.workingDirectory());
        return runStore.load(sessions.getWorkflowRunPath(context.sessionId(), runId));
    }

    private static String readScript(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new WorkflowInputException("Workflow script file not found: " + path);
        }
        try {
            if (Files.size(path) > WorkflowScriptParser.MAX_SCRIPT_BYTES) {
                throw new WorkflowInputException("Workflow script file " + path
                    + " exceeds " + WorkflowScriptParser.MAX_SCRIPT_BYTES + " bytes");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WorkflowInputException(
                "Failed to read workflow script file " + path + ": " + e.getMessage());
        }
    }

    private static void persistScript(Path path, String script) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, script, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new WorkflowInputException(
                "Failed to persist workflow script file " + path + ": " + e.getMessage());
        }
    }

    private static void validateControlCharacters(String script) {
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\r' && c != '\t')
                    || (c >= 0x7f && c <= 0x9f)) {
                throw new WorkflowInputException(
                    "script contains control characters that would be hidden in the approval dialog");
            }
        }
    }

    private static String sanitizeScriptName(String name) {
        String sanitized = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        return sanitized.isEmpty() ? "workflow" : sanitized;
    }

    private static String text(JsonNode input, String field) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null || value.isNull() || !value.isTextual()) return null;
        String text = value.asText();
        return StringUtils.isBlank(text) ? null : text;
    }

    private record ResolvedWorkflow(String script, Path path,
                                    WorkflowSource source, String pluginName) {}

    private static final class WorkflowInputException extends RuntimeException {
        private WorkflowInputException(String message) { super(message); }
    }
}
