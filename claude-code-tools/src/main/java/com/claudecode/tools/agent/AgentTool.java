package com.claudecode.tools.agent;

import java.util.Locale;

import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubAgentCompactServiceFactory;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.engine.SubAgentProgressSummarizer;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.message.Usage;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.util.AgentId;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.session.SessionManager;
import com.claudecode.session.AgentMetadata;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.ToolErrors;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.BackgroundHint;
import com.claudecode.tools.tasks.BackgroundTaskGate;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.tasks.TeamRegistry;
import com.claudecode.tools.tasks.TeamTaskListRegistry;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.TeammateLeaderCoordinator;
import com.claudecode.tools.tasks.teammate.TeammatePermissionAskCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.claudecode.tools.skills.Skill;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;

/**
 * AgentTool — creates a sub-QuerySession instance with independent message history.
 */
@BuiltInTool(
    name = "Agent", aliases = {"Task"},
    readOnly = true,
    concurrencySafe = true
)
public class AgentTool extends AnnotatedTool<JsonNode, ToolResult> {

    /** Default tools available to sub-agents when none specified. */
    public static final List<String> DEFAULT_SAFE_TOOLS = List.of(
        "Bash", "FileRead", "FileWrite", "FileEdit", "GlobTool", "GrepTool"
    );

    /**
     * Tools a sub-agent can never be given, regardless of what the caller requests.
     */
    public static final Set<String> AGENT_DISALLOWED_TOOLS = Set.of(
        "AskUserQuestion",
        "ExitPlanMode",
        "EnterPlanMode",
        "TaskOutput",
        "TaskStop",
        "Workflow",
        "ScheduleWakeup"
    );

    /**
     * Built-in agents that run once and return a report — the parent never SendMessages back to
     * continue them.
     */
    private static final Set<String> ONE_SHOT_BUILTIN_AGENT_TYPES = Set.of("Explore", "Plan");

    /** Fraction of parent budget allocated to sub-agent. */
    public static final double BUDGET_FRACTION = -1.0;
    private static final int FORK_MAX_TURNS = 200;
    private static final long BACKGROUND_HINT_DELAY_MS = 2_000L;
    private static final ScheduledExecutorService BACKGROUND_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
            .name("agent-background-timer-", 0).factory());

    private static final JsonNode BASE_SCHEMA = buildSchema();

    private final SubAgentFactory subAgentFactory;
    private final TaskRegistry taskRegistry;
    private final Path outputDirOverride;
    private final Long autoBackgroundDelayOverrideMs;
    private volatile SubAgentModelPolicy modelPolicy = SubAgentModelPolicy.permissive();
    private volatile Supplier<List<String>> modelOptionsSupplier =
        () -> List.of("sonnet", "opus", "haiku", "fable");
    private volatile Supplier<ToolPermissionContext> permissionContextSupplier;
    private volatile IntSupplier subagentMaxDepthSupplier = () -> 2;
    private volatile HookDispatcher teammateHookDispatcher;

    public void setTeammateHookDispatcher(HookDispatcher dispatcher) {
        this.teammateHookDispatcher = dispatcher;
    }

    /** Installs the live user setting used only when a new ordinary tree starts. */
    public void setSubagentMaxDepthSupplier(IntSupplier supplier) {
        subagentMaxDepthSupplier = supplier != null ? supplier : () -> 2;
    }

    public AgentTool(SubAgentFactory subAgentFactory) {
        this(subAgentFactory, null, null);
    }

    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir")), null, null);
    }

    /**
     * Production constructor that also supplies a {@link com.claudecode.core.engine.SubAgentProgressSummarizer}
     * (core interface) so the sub-agent progress summarizer can run. The concrete
     * implementation lives in services and is injected by the composition root.
     * Falls back to the 2-arg behaviour (no summarizer) when {@code summarizer}
     * is {@code null}.
     */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"), summarizer, null), null, null);
    }

    /**
     * Production constructor that also threads the parent session's shared {@link SessionIdentity}
     * into every sub-agent's {@link com.claudecode.runtime.query.QuerySession} (see
     * {@link DefaultSubAgentFactory}'s class Javadoc). Pass {@code null} to fall back to the 3-arg
     * behaviour (each sub-agent gets an independent identity).
     */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, null), null, null);
    }

    /**
     * Production constructor that also wires a {@link SubAgentLifecycleListener}
     * (core interface) fired when each sub-agent finishes — used to release
     * per-agent resources in higher layers (e.g. prompt-cache-break tracking's
     * {@code cleanupAgentTracking(agentId)}). The concrete implementation lives
     * in services and is injected by the composition root; pass {@code null} to
     * fall back to the 4-arg behaviour.
     */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity,
                     SubAgentLifecycleListener lifecycleListener) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, lifecycleListener, null), null, null);
    }


    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity,
                     SubAgentLifecycleListener lifecycleListener,
                     SubAgentCompactServiceFactory compactFactory) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, lifecycleListener, compactFactory), null, null);
    }

    /** Production constructor with the live Skill inventory supplier. */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity,
                     SubAgentLifecycleListener lifecycleListener,
                     SubAgentCompactServiceFactory compactFactory,
                     Supplier<List<SkillListingEntry>> skillListingSupplier) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, lifecycleListener, compactFactory, skillListingSupplier), null, null);
    }

    /** Production constructor with live skill bodies for agent-frontmatter preloading. */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity,
                     SubAgentLifecycleListener lifecycleListener,
                     SubAgentCompactServiceFactory compactFactory,
                     Supplier<List<SkillListingEntry>> skillListingSupplier,
                     Supplier<List<Skill>> skillSupplier) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, lifecycleListener, compactFactory,
            skillListingSupplier, skillSupplier), null, null);
    }

    /** Production constructor with the live Git gate and sub-agent memory loader. */
    public AgentTool(StreamingClient llmClient, ToolExecutor toolExecutor,
                     SubAgentProgressSummarizer summarizer, SessionIdentity sessionIdentity,
                     SubAgentLifecycleListener lifecycleListener,
                     SubAgentCompactServiceFactory compactFactory,
                     Supplier<List<SkillListingEntry>> skillListingSupplier,
                     Supplier<List<Skill>> skillSupplier,
                     Supplier<Boolean> includeGitInstructionsSupplier,
                     Function<Path, String> claudeMdContentLoader) {
        this(new DefaultSubAgentFactory(llmClient, toolExecutor, System.getProperty("user.dir"),
            summarizer, sessionIdentity, lifecycleListener, compactFactory,
            skillListingSupplier, skillSupplier, includeGitInstructionsSupplier,
            claudeMdContentLoader), null, null);
    }

    public AgentTool() {
        this(new NoOpSubAgentFactory(), null, null);
    }

    /**
     * Test/advanced-caller constructor: injects the {@link TaskRegistry} used
     * for {@code run_in_background} execution instead of {@link TaskRegistry#global},
     * and an {@code outputDir} to write background-task {@code .output} files
     * into instead of the real session-scoped task-output directory; tests redirect via path
     * injection, not {@code System.setProperty}). Either argument may be
     * {@code null} to fall back to the production default independently.
     */
    public AgentTool(SubAgentFactory subAgentFactory, TaskRegistry taskRegistry, Path outputDir) {
        this(subAgentFactory, taskRegistry, outputDir, null);
    }

    AgentTool(SubAgentFactory subAgentFactory, TaskRegistry taskRegistry, Path outputDir,
              Long autoBackgroundDelayOverrideMs) {
        this.subAgentFactory = subAgentFactory;
        this.taskRegistry = taskRegistry;
        this.outputDirOverride = outputDir;
        this.autoBackgroundDelayOverrideMs = autoBackgroundDelayOverrideMs;
    }

    /** Installs the composition root's provider/auth-aware model capability check. */
    public void setModelAvailabilityPredicate(Predicate<String> predicate) {
        Predicate<String> effective = predicate != null ? predicate : _ -> true;
        setSubAgentModelPolicy(new SubAgentModelPolicy() {
            @Override
            public Decision resolve(String requestedModel, String parentModel) {
                String selected = StringUtils.isBlank(requestedModel)
                    || Strings.CI.equals("inherit", requestedModel) ? parentModel : requestedModel;
                return effective.test(selected) ? Decision.use(selected)
                    : Decision.reject(selected, "Sub-agent model is not available with the current "
                        + "model provider and authentication: " + selected);
            }

            @Override
            public List<String> advertisedModels() {
                return Stream.of("sonnet", "opus", "haiku", "fable")
                    .filter(effective).toList();
            }
        });
    }

    /** Installs the session model catalogue and execution policy. */
    public void setSubAgentModelPolicy(SubAgentModelPolicy policy) {
        modelPolicy = policy != null ? policy : SubAgentModelPolicy.permissive();
        setModelOptionsSupplier(modelPolicy::advertisedModels);
        if (subAgentFactory instanceof DefaultSubAgentFactory factory) {
            factory.setSubAgentModelPolicy(modelPolicy);
        }
    }

    @Override
    public Optional<ToolUseTag> renderToolUseTag(
            JsonNode input, ToolUseRenderContext context) {
        if (input == null || context == null) return Optional.empty();
        String requested = input.path("model").asText("");
        if (StringUtils.isBlank(requested) || Strings.CI.equals("inherit", requested)) {
            return Optional.empty();
        }
        String resolved = resolvedProgressModel(context.progressMessages());
        if (StringUtils.isBlank(resolved)) {
            resolved = resolvedTagModel(context.toolUseResult());
        }
        if (StringUtils.isBlank(resolved)) return Optional.empty();
        String normalizedRequested = ModelNames.parseUserSpecifiedModel(requested);
        if (Strings.CS.equals(resolved, context.mainModel())
                && Strings.CS.equals(normalizedRequested, resolved)) {
            return Optional.empty();
        }
        return Optional.of(ToolUseTag.dim(ModelNames.displayName(resolved)));
    }

    private static String resolvedProgressModel(List<ProgressMessage> progress) {
        for (int index = progress.size() - 1; index >= 0; index--) {
            var data = progress.get(index).data();
            if (data != null && Strings.CS.equals("agent_progress", data.type())
                    && !StringUtils.isBlank(data.resolvedModel())) {
                return data.resolvedModel();
            }
        }
        return null;
    }

    private static String resolvedTagModel(Object result) {
        if (result instanceof JsonNode node) {
            String resolved = node.path("resolvedModel").asText("");
            if (!StringUtils.isBlank(resolved)) return resolved;
            if (Strings.CS.equals("teammate_spawned", node.path("status").asText(""))) {
                return ModelNames.parseUserSpecifiedModel(node.path("model").asText(""));
            }
        }
        if (result instanceof Map<?, ?> map) {
            Object resolved = map.get("resolvedModel");
            if (resolved instanceof String value && !StringUtils.isBlank(value)) return value;
            if (Strings.CS.equals("teammate_spawned", String.valueOf(map.get("status")))) {
                Object model = map.get("model");
                if (model instanceof String value) return ModelNames.parseUserSpecifiedModel(value);
            }
        }
        return null;
    }

    /** Shares the already-wired runtime with internal verifier-agent hooks. */
    public SubAgentFactory subAgentFactory() {
        return subAgentFactory;
    }


    public record SpawnedFork(String agentId, String name) { }


    public SpawnedFork spawnForkFromDirective(String directive,
                                              List<Message> additionalMessages,
                                              ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        if (context.conversationMessages().isEmpty()
                || StringUtils.isBlank(context.renderedSystemPrompt())) {
            return null;
        }
        rejectAtNestingLimit(context);

        String normalized = StringUtils.defaultString(directive)
            .replaceAll("\\s+", " ").trim();
        String description = normalized.length() > 50
            ? normalized.substring(0, 49) + "…" : normalized;
        String name = deriveForkName(normalized);
        ObjectNode input = mapper().createObjectNode();
        input.put("prompt", directive);
        input.put("description", description);
        input.put("name", name);
        input.put("fork", true);

        SubAgentRequest request = buildRequest(input, context);
        request = request.withPriorMessages(ForkMessageBuilder.build(
            context.conversationMessages(), directive, additionalMessages));
        SubAgentModelPolicy.Decision decision = modelPolicy.resolveAgent(
            findAgentDefinition(null, context), null, context.currentModel());
        if (decision.outcome() == SubAgentModelPolicy.Outcome.REJECT) {
            throw new IllegalStateException(decision.message());
        }

        ToolResult result = handleAsyncExecution(request, description, name, context);
        if (result.afterResultEmitted() != null) result.afterResultEmitted().run();
        if (result.isError()) {
            throw new IllegalStateException(result.content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .findFirst().orElse("Failed to launch fork"));
        }
        if (!(result.toolUseResult() instanceof Map<?, ?> payload)) {
            throw new IllegalStateException("Fork launch did not return an agent id");
        }
        String agentId = Objects.toString(payload.get("agentId"), "");
        if (StringUtils.isBlank(agentId)) {
            throw new IllegalStateException("Fork launch did not return an agent id");
        }
        return new SpawnedFork(agentId, name);
    }

    private static String deriveForkName(String directive) {
        String[] words = StringUtils.defaultString(directive).trim().split("\\s+");
        String joined = Arrays.stream(words).limit(3)
            .collect(Collectors.joining("-"))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
        if (joined.length() > 24) joined = joined.substring(0, 24);
        return StringUtils.isBlank(joined) ? "fork" : joined;
    }

    /** Installs the live model choices used to derive a fresh schema per request. */
    public void setModelOptionsSupplier(Supplier<List<String>> supplier) {
        modelOptionsSupplier = supplier != null ? supplier
            : () -> List.of("sonnet", "opus", "haiku", "fable");
    }

    /** Installs the registry's live permission context for model-visible agent filtering. */
    public void setPermissionContextSupplier(Supplier<ToolPermissionContext> supplier) {
        permissionContextSupplier = supplier;
    }

    @Override
    public String description() {
        return ToolTexts.description("Agent");
    }


    @Override
    public String searchHint() {
        return "delegate work to a subagent";
    }

    /**
     * Builds the model-facing Agent prompt from the active definition catalogue.
     */
    @Override
    public String prompt(ToolExecutionContext context) {
        String cwd = context != null && context.workingDirectory() != null
            ? context.workingDirectory() : System.getProperty("user.dir");
        Set<String> mcpServers = availableMcpServers(context);
        List<String> listing = AgentDefinitionLoader.getActive(cwd).stream()
            .filter(agent -> hasRequiredMcpServers(agent, mcpServers))
            .filter(agent -> isAgentModelAvailable(agent, context))
            .filter(agent -> !isAgentDenied(agent.agentType()))
            .map(AgentDefinition::toPromptLine)
            .toList();

        // isolation and writing guidance) while deriving the listing from the
        // same loader used by execution. An empty list is still valid: it means
        // the model receives the general Agent guidance without stale names.
        boolean protectMissingExample = context != null
            && !ModelCatalog.isBuiltInSelection(context.currentModel());
        return AgentToolPrompt.getPrompt(listing, protectMissingExample);
    }

    private boolean isAgentDenied(String agentType) {
        Supplier<ToolPermissionContext> supplier = permissionContextSupplier;
        if (supplier == null) return false;
        ToolPermissionContext permissionContext = supplier.get();
        return permissionContext != null
            && PermissionEngine.getDenyRuleForAgent(permissionContext, agentType).isPresent();
    }

    private static Set<String> availableMcpServers(ToolExecutionContext context) {
        if (context == null || context.enabledTools() == null) return Set.of();
        Set<String> servers = new HashSet<>();
        for (String name : context.enabledTools()) {
            if (name == null || !Strings.CS.startsWith(name, "mcp__")) continue;
            String remainder = name.substring("mcp__".length());
            int separator = remainder.indexOf("__");
            if (separator > 0) servers.add(remainder.substring(0, separator));
        }
        return Set.copyOf(servers);
    }

    private static boolean hasRequiredMcpServers(
            AgentDefinition agent, Set<String> availableServers) {
        if (agent.mcpServers() == null || agent.mcpServers().isEmpty()) return true;
        return agent.mcpServers().stream().allMatch(required ->
            availableServers.stream().anyMatch(available ->Strings.CS.contains(
                available.toLowerCase(Locale.ROOT), required.toLowerCase(Locale.ROOT))));
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = BASE_SCHEMA.deepCopy();
        ObjectNode properties = (ObjectNode) schema.get("properties");
        List<String> models;
        try {
            models = modelOptionsSupplier.get();
        } catch (RuntimeException _) {
            models = List.of();
        }
        if (models == null || models.isEmpty()) {
            properties.remove("model");
        } else {
            ArrayNode values = (ArrayNode) properties.get("model").get("enum");
            values.removeAll();
            models.forEach(values::add);
        }
        return schema;
    }

    @Override
    public ToolResult call(JsonNode input, ToolExecutionContext context) {
        Objects.requireNonNull(context, "context");
        String depthError = nestingLimitError(context);
        if (depthError != null) {
            return blockResult(ToolResult.error(depthError));
        }


        // "in_process_teammate" has no agent-definition entry of its own, so it
        // is treated as known here even though AgentDefinitionLoader won't find

        String subagentType = input.has("subagent_type") && !input.get("subagent_type").isNull()
            ? input.get("subagent_type").asText() : null;
        if (subagentType != null && !isKnownAgentType(subagentType, context)) {
            return blockResult(ToolResult.error("Error: Agent type '" + subagentType
                + "' not found. Available agents: " + listKnownAgentTypes(context)));
        }

        SubAgentRequest request = buildRequest(input, context);
        AgentDefinition resolvedDefinition = findAgentDefinition(subagentType, context);
        String toolSpecifiedModel = input.has("model") && !input.get("model").isNull()
            ? input.get("model").asText() : null;
        SubAgentModelPolicy.Decision modelDecision;
        try {
            modelDecision = modelPolicy.resolveAgent(
                resolvedDefinition, toolSpecifiedModel,
                context.currentModel());
        } catch (RuntimeException failure) {
            modelDecision = SubAgentModelPolicy.Decision.reject(request.model(),
                "model policy evaluation failed: " + failure.getMessage());
        }
        if (modelDecision.outcome() == SubAgentModelPolicy.Outcome.REJECT) {
            return blockResult(ToolResult.error("Error: Agent type '" + effectiveAgentType(request)
                + "' is not available with the current model provider and authentication: "
                + modelDecision.message()));
        }
        String agentName = input.has("name") && !input.get("name").isNull()
            ? input.get("name").asText(null) : null;
        boolean teammateCaller = TeammateContextHolder.get() != null;
        if (teammateCaller && StringUtils.isNotBlank(agentName)) {
            return blockResult(ToolResult.error(
                "Teammates cannot spawn other teammates — the team roster is flat. "
                    + "To spawn a subagent instead, omit the `name` parameter."));
        }
        if (teammateCaller && extractBool(input, "run_in_background",
                extractBool(input, "async", false))) {
            return blockResult(ToolResult.error(
                "In-process teammates cannot spawn background agents. "
                    + "Use run_in_background=false for synchronous subagents."));
        }
        if (teammateCaller && resolvedDefinition != null && resolvedDefinition.background()) {
            return blockResult(ToolResult.error(
                "In-process teammates cannot spawn background agents. Agent '"
                    + resolvedDefinition.agentType()
                    + "' has background: true in its definition."));
        }

        if (StringUtils.isBlank(request.prompt())) {
            return blockResult(ToolResult.error("Error: prompt is required"));
        }

        if (request.async()) {
            String description = StringUtils.isBlank(request.description()) ? request.prompt() : request.description();
            return blockResult(handleAsyncExecution(request, description, agentName, context));
        }

        // Agent-teams (opt-in, off by default): an in-process teammate spawn.
        if (AgentTeamsEnabled.isEnabled() && request.teammate()) {
            String description = StringUtils.isBlank(request.description()) ? request.prompt() : request.description();
            return blockResult(ToolResult.success(handleTeammateExecution(
                request, description, agentName, context)));
        }

        return blockResult(runSynchronousExecution(request, agentName, context));
    }

    private static ToolResult blockResult(ToolResult result) {
        return result.withContentForm(ToolResultContentForm.BLOCKS);
    }

    private int effectiveMaxDepth(ToolExecutionContext context) {
        Integer snapshot = context.subagentMaxDepthSnapshot();
        if (snapshot != null) return boundedMaxDepth(snapshot);
        try {
            return boundedMaxDepth(subagentMaxDepthSupplier.getAsInt());
        } catch (RuntimeException _) {
            return 2;
        }
    }

    private static int boundedMaxDepth(int value) {
        return value >= 1 && value <= 5 ? value : 2;
    }

    private String nestingLimitError(ToolExecutionContext context) {
        int depth = Math.max(0, context.agentDepth());
        int maxDepth = effectiveMaxDepth(context);
        if (depth < maxDepth) return null;
        return "Subagent nesting limit reached (depth " + depth + " of " + maxDepth
            + "). Complete this task directly using your tools instead of spawning another agent.";
    }

    private void rejectAtNestingLimit(ToolExecutionContext context) {
        String error = nestingLimitError(context);
        if (error != null) throw new IllegalStateException(error);
    }

    /**
     * Runs a foreground agent while emitting the SDK-only task lifecycle that.
     */
    private ToolResult runSynchronousExecution(SubAgentRequest request, String agentName,
                                               ToolExecutionContext context) {
        var queue = context.messageQueueManager();
        String description = StringUtils.isBlank(request.description())
            ? request.prompt() : request.description();
        String taskId = AgentId.create();
        long startedAt = System.currentTimeMillis();
        if (BackgroundTaskGate.disabled()) {
            try {
                AbortController parentAbort = context.abortController();
                SubAgentRequest foregroundRequest = withTrackedProgress(
                    request.withAgentId(taskId)
                        .withAbortController(parentAbort != null
                            ? parentAbort : new AbortController()),
                    taskId, null, startedAt);
                SubAgentResult result = subAgentFactory.runSubAgent(foregroundRequest);
                return completedResult(result, request, taskId);
            } catch (Exception e) {
                return failedResult(e, request, taskId);
            }
        }
        TaskRegistry registry = taskRegistry != null ? taskRegistry : TaskRegistry.global();
        TaskState task = registry.store().createWithId(
            taskId, TaskType.LOCAL_AGENT, description, null);
        registry.store().updatePrompt(taskId, request.prompt());
        registry.store().updateAgentType(taskId, effectiveAgentType(request));
        registry.store().updateToolUseId(taskId, context.toolUseId());
        registry.store().updateStatus(taskId, TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        configureStoppedByUserPersistence(handle, context, request);
        AbortController abortController = new AbortController();
        handle.setAbortController(abortController);
        AbortController parentAbortController = context.abortController();
        AutoCloseable parentAbortLink = parentAbortController == null ? () -> {}
            : parentAbortController.registerOnAbort(() -> abortController.abort(
                parentAbortController.getReason()));
        SubAgentRequest foregroundRequest = withTrackedProgress(
            request.withAbortController(abortController).withAgentId(taskId),
            taskId, handle, startedAt);
        registry.registerAgentForeground(handle);
        registry.registerAgentName(agentName, taskId);
        BackgroundHint hint = new BackgroundHint(context);
        ScheduledFuture<?> hintFuture = BACKGROUND_SCHEDULER.schedule(() -> {
                if (registry.listForegroundBackgroundable().stream()
                        .anyMatch(candidate -> candidate.id().equals(taskId))) {
                    hint.show();
                }
            }, BACKGROUND_HINT_DELAY_MS, TimeUnit.MILLISECONDS);
        long autoBackgroundMs = autoBackgroundDelayMs();
        ScheduledFuture<?> autoBackgroundFuture = autoBackgroundMs > 0
            ? BACKGROUND_SCHEDULER.schedule(() -> registry.backgroundAgent(taskId),
                autoBackgroundMs, TimeUnit.MILLISECONDS)
            : null;
        Path outputPath = outputDirOverride != null
            ? outputDirOverride.resolve(taskId + ".output")
            : TaskOutputPaths.outputPath(taskId, context);
        boolean transcriptLinked = outputDirOverride == null
            && initAsyncOutputSymlink(outputPath, context, taskId);
        if (queue != null) {
            queue.enqueueSdkEvent(new SDKMessage.TaskStarted(
                taskId, context.toolUseId(), description, "local_agent", null,
                request.prompt(), request.subagentType()));
            queue.enqueueSdkEvent(new SDKMessage.User(
                MessageFactory.createUserMessage(List.of(new TextBlock(request.prompt())), false),
                false, context.toolUseId(),
                request.subagentType(), description));
        }
        context.reportProgress(ToolExecutionContext.ProgressUpdate.agent(
            MessageFactory.createUserMessage(List.of(new TextBlock(request.prompt())), false),
            request.prompt(), taskId));

        CompletableFuture<SubAgentResult> completion =
            new CompletableFuture<>();
        Thread runner = Thread.ofVirtual().name("fg-agent-" + taskId).unstarted(() -> {
            try {
                SubAgentRequest turnRequest = foregroundRequest;
                while (true) {
                    SubAgentResult result = subAgentFactory.runSubAgent(turnRequest);
                    // A normal synchronous completion belongs to the parent
                    // tool call. Once Ctrl+B has detached it, however, the same
                    // live task must consume SendMessage continuations at safe
                    // turn boundaries just like an explicitly async agent.
                    if (!handle.backgroundSignal().isDone() || !isSuccessful(result)) {
                        completion.complete(result);
                        return;
                    }
                    List<String> pending = registry.drainAgentMessages(taskId);
                    if (pending.isEmpty()) {
                        completion.complete(result);
                        return;
                    }
                    turnRequest = turnRequest
                        .withPrompt(pending.getFirst())
                        .withPriorMessages(result.conversation().orElse(List.of()));
                    for (int i = 1; i < pending.size(); i++) {
                        registry.queueAgentMessage(taskId, pending.get(i));
                    }
                }
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        handle.setRunnerThread(runner);
        runner.start();

        try {
            Object winner = CompletableFuture.anyOf(
                completion, handle.backgroundSignal()).get();
            if (winner == null) {
                closeAbortLink(parentAbortLink);
                cancelBackgroundTimers(hint, hintFuture, autoBackgroundFuture);
                context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                    .complete(true)
                    .build());
                startForegroundCompletionLifecycle(
                    completion, handle, registry, taskId, foregroundRequest, context, description,
                    outputPath, transcriptLinked, startedAt);
                return asyncLaunchResult(foregroundRequest, description, context, taskId, outputPath);
            }
            SubAgentResult result = completion.get();
            cancelBackgroundTimers(hint, hintFuture, autoBackgroundFuture);
            registry.unregisterForegroundAgent(taskId);
            context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                .complete(true)
                .build());
            emitForegroundTerminalEvents(queue, taskId, context.toolUseId(), description,
                isSuccessful(result) ? "completed" : "failed", result, startedAt);
            return completedResult(result, request, taskId);
        } catch (InterruptedException e) {
            String reason = parentAbortController != null
                ? parentAbortController.getReason() : null;
            abortController.abort(reason != null ? reason : "user-cancel");
            Thread.currentThread().interrupt();
            cancelBackgroundTimers(hint, hintFuture, autoBackgroundFuture);
            registry.unregisterForegroundAgent(taskId);
            context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                .complete(true)
                .build());
            emitForegroundTerminalEvents(queue, taskId, context.toolUseId(), description,
                "failed", null, startedAt);
            return failedResult(e, request, taskId);
        } catch (Exception e) {
            cancelBackgroundTimers(hint, hintFuture, autoBackgroundFuture);
            registry.unregisterForegroundAgent(taskId);
            context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                .complete(true)
                .build());
            emitForegroundTerminalEvents(queue, taskId, context.toolUseId(), description,
                "failed", null, startedAt);
            return failedResult(e, request, taskId);
        } finally {
            closeAbortLink(parentAbortLink);
        }
    }

    private static void closeAbortLink(AutoCloseable link) {
        try {
            link.close();
        } catch (Exception _) {
            // Abort listener cleanup is best-effort and must not mask the tool result.
        }
    }

    /**
     * Stops the timers and closes the background-hint window before the caller emits its own
     * {@code complete(true)}. {@code cancel(false)} alone cannot stop a hint callback that has
     * already started running, so the affordance would otherwise reappear after teardown.
     */
    private static void cancelBackgroundTimers(BackgroundHint hint, ScheduledFuture<?> hintFuture,
            ScheduledFuture<?> autoBackgroundFuture) {
        hintFuture.cancel(false);
        hint.disarm();
        if (autoBackgroundFuture != null) autoBackgroundFuture.cancel(false);
    }

    private void startForegroundCompletionLifecycle(
            CompletableFuture<SubAgentResult> completion,
            LocalAgentTask handle, TaskRegistry registry, String taskId, SubAgentRequest request,
            ToolExecutionContext context, String description, Path outputPath,
            boolean transcriptLinked, long startedAt) {
        completion.whenComplete((result, error) -> {
            if (error != null) {
                if (outputDirOverride != null || !transcriptLinked) {
                    writeOutput(outputPath, "Error: sub-agent execution failed: " + error.getMessage());
                }
                handle.fail(error.getMessage());
                registry.clearAgentMessages(taskId);
                emitForegroundTerminalEvents(context.messageQueueManager(), taskId,
                    context.toolUseId(), description, "failed", null, startedAt);
                return;
            }
            if (outputDirOverride != null || !transcriptLinked) {
                writeOutput(outputPath, formatResultText(result, request.subagentType()));
            }
            if (!isSuccessful(result)) handle.fail(terminationMessage(result));
            else handle.complete(result);
            emitForegroundTerminalEvents(context.messageQueueManager(), taskId,
                context.toolUseId(), description, isSuccessful(result) ? "completed" : "failed",
                result, startedAt);
            registry.clearAgentMessages(taskId);
        });
    }

    private void emitForegroundTerminalEvents(MessageQueueManager queue,
                                              String taskId, String toolUseId,
                                              String description, String status,
                                              SubAgentResult result, long startedAt) {
        if (queue == null) return;
        long duration = Math.max(0L, System.currentTimeMillis() - startedAt);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("status", status);
        patch.put("end_time", System.currentTimeMillis());
        queue.enqueueSdkEvent(new SDKMessage.TaskUpdated(taskId, patch));
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("total_tokens", result == null ? 0L : result.progressTokens());
        usage.put("tool_uses", result == null ? 0 : result.toolUseCount());
        usage.put("duration_ms", result == null || result.durationMs() <= 0
            ? duration : result.durationMs());
        queue.enqueueSdkEvent(new SDKMessage.TaskNotification(
            taskId, toolUseId, status, "", description, usage));
    }

    /**
     * Runs the sub-agent as a real background task and returns immediately.
     */
    private ToolResult handleAsyncExecution(SubAgentRequest request, String description,
                                            String agentName,
                                            ToolExecutionContext context) {
        String taskDescription = StringUtils.isBlank(description) ? request.prompt() : description;

        TaskRegistry registry = taskRegistry != null ? taskRegistry : TaskRegistry.global();
        String launchedAgentId = AgentId.create();
        TaskState task = registry.store().createWithId(
            launchedAgentId, TaskType.LOCAL_AGENT, taskDescription, null);
        registry.store().updatePrompt(launchedAgentId, request.prompt());
        registry.store().updateAgentType(launchedAgentId, effectiveAgentType(request));
        registry.store().updateToolUseId(task.id(), context.toolUseId());
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);

        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        configureStoppedByUserPersistence(handle, context, request);
        // Real cancellation: the sub-engine runs on this controller, and
// kill aborts it (plus the thread interrupt for blocking I/O).
        AbortController abortController = new AbortController();
        handle.setAbortController(abortController);
        CountDownLatch firstModelRequest = subAgentFactory.supportsFirstModelRequestSignal()
            ? new CountDownLatch(1) : null;
        CountDownLatch parentLaunchResultEmitted = firstModelRequest == null
            ? null : new CountDownLatch(1);
        long startedAt = System.currentTimeMillis();
        SubAgentRequest cancellableRequest = withTrackedProgress(
            request.withAbortController(abortController)
                .withAgentId(launchedAgentId)
                .withBeforeFirstModelRequest(firstModelRequest == null
                    ? null : firstModelRequest::countDown)
                .withAwaitParentToolResultEmission(parentLaunchResultEmitted == null
                    ? null : () -> awaitFirstModelRequest(parentLaunchResultEmitted)),
            launchedAgentId, handle, startedAt);
        registry.registerAgent(handle);
        registry.registerAgentName(agentName, launchedAgentId);

        Path outputPath = outputDirOverride != null
            ? outputDirOverride.resolve(task.id() + ".output")
            : TaskOutputPaths.outputPath(task.id(), context);
        boolean transcriptLinked = outputDirOverride == null
            && initAsyncOutputSymlink(outputPath, context, launchedAgentId);

        if (context.messageQueueManager() != null) {
            context.messageQueueManager().enqueueSdkEvent(new SDKMessage.TaskStarted(
                launchedAgentId, context.toolUseId(), taskDescription, "local_agent", null,
                request.prompt(), request.subagentType()));
        }

        Thread runner = Thread.ofVirtual().name("bg-agent-" + task.id()).unstarted(() -> {
            SubAgentResult result;
            try {
                SubAgentRequest turnRequest = cancellableRequest;
                Deque<String> pending = new ArrayDeque<>();
                while (true) {
                    result = subAgentFactory.runSubAgent(turnRequest);
                    if (outputDirOverride != null || !transcriptLinked) {
                        writeOutput(outputPath, formatResultText(result, request.subagentType()));
                    }
                    if (!isSuccessful(result)) {
                        handle.fail(terminationMessage(result));
                        return;
                    }

                    // SendMessage may enqueue a plain-text continuation while this
                    // turn is running. Re-run with the child's returned

                    // queuePendingMessage/resume path without claiming success for
                    // an unsupported cross-session transport.
                    pending.addAll(registry.drainAgentMessages(launchedAgentId));
                    if (pending.isEmpty()) {
                        handle.complete(result);
                        return;
                    }
                    String nextPrompt = pending.removeFirst();
                    turnRequest = turnRequest
                        .withPrompt(nextPrompt)
                        .withPriorMessages(result.conversation().orElse(List.of()));
                }
            } catch (Exception e) {
                writeOutput(outputPath, "Error: sub-agent execution failed: " + e.getMessage());
                handle.fail(e.getMessage());
            } finally {
                registry.clearAgentMessages(launchedAgentId);
                // Avoid stranding the parent if child setup fails before the
                // first API dispatch. A successful production run releases this
                // latch earlier through beforeFirstModelRequest.
                if (firstModelRequest != null) firstModelRequest.countDown();
            }
        });
        handle.setRunnerThread(runner);
        runner.start();
        awaitFirstModelRequest(firstModelRequest);

        ToolResult launchResult = asyncLaunchResult(
            request, taskDescription, context, launchedAgentId, outputPath);
        return parentLaunchResultEmitted == null
            ? launchResult
            : launchResult.withAfterResultEmitted(parentLaunchResultEmitted::countDown);
    }


    private ToolResult asyncLaunchResult(SubAgentRequest request, String description,
                                         ToolExecutionContext context, String agentId,
                                         Path outputPath) {
        boolean canReadOutputFile = context.enabledTools().isEmpty()
            || context.enabledTools().stream().anyMatch(name ->
                Strings.CS.equalsAny(name, "Read", "FileRead", "Bash"));

        String prefix = "Async agent launched successfully.\nagentId: " + agentId
            + " (internal ID - do not mention to user. Use SendMessage with to: '"
            + agentId + "', summary: '<5-10 word recap>' to continue this agent.)\n"
            + "The agent is working in the background. You will be notified automatically when it completes.";
        String instructions = canReadOutputFile
            ? "Do not duplicate this agent's work — avoid working with the same files or topics it is using.\n"
                + "output_file: " + outputPath + "\n"
                + "Do NOT Read or tail this file via the shell tool — it is the full subagent JSONL transcript "
                + "and reading it will overflow your context. If the user asks for progress, say the agent is "
                + "still running; you'll get a completion notification."
            : "Briefly tell the user what you launched and end your response. Do not generate any other text — "
                + "agent results will arrive in a subsequent message.";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("isAsync", true);
        payload.put("status", "async_launched");
        payload.put("agentId", agentId);
        payload.put("description", description);
        payload.put("resolvedModel", request.model());
        payload.put("prompt", request.prompt());
        payload.put("outputFile", outputPath.toString());
        payload.put("canReadOutputFile", canReadOutputFile);
        return ToolResult.success(prefix + "\n" + instructions).withToolUseResult(payload);
    }

    static SubAgentRequest withTrackedProgress(SubAgentRequest request,
            String agentId, LocalAgentTask handle, long startedAt) {
        SubAgentRequest.ProgressCallback downstream = request.progressCallback();
        AgentProgressTracker tracker = new AgentProgressTracker();
        return request.withProgressCallback(new SubAgentRequest.ProgressCallback() {
            @Override
            public void onProgress(String status, double progressPercent) {
                if (handle != null) handle.updateProgress(
                    normalizeAgentProgress(progressPercent), status);
                if (downstream != null) downstream.onProgress(status, progressPercent);
            }

            @Override
            public void onAgentMessage(Message message, String ignoredAgentId) {
                tracker.recordMessage(message);
                publishUsage();
                if (downstream == null) return;
                Message projected = message instanceof AssistantMessage assistant
                    ? tracker.messageWithAggregatedUsage(assistant.uuid()) : message;
                downstream.onAgentMessage(projected != null ? projected : message, agentId);
            }

            @Override
            public void onAgentUsage(String messageId, Usage usage) {
                tracker.recordUsage(messageId, usage);
                publishUsage();
                if (downstream == null) return;
                AssistantMessage projected = tracker.messageWithAggregatedUsage(messageId);
                if (projected != null) downstream.onAgentMessage(projected, agentId);
                downstream.onAgentUsage(messageId, usage);
            }

            private void publishUsage() {
                if (handle == null) return;
                AgentProgressTracker.Snapshot snapshot = tracker.snapshot();
                handle.updateUsage(snapshot.totalTokens(), snapshot.toolUseCount(),
                    System.currentTimeMillis() - startedAt);
            }
        });
    }

    private static void configureStoppedByUserPersistence(LocalAgentTask handle,
            ToolExecutionContext context, SubAgentRequest request) {
        if (context == null || StringUtils.isBlank(context.sessionId())) return;
        handle.setStoppedByUserPersister(() -> {
            Path transcript = new SessionManager(context.workingDirectory())
                .getAgentTranscriptPath(context.sessionId(), handle.getTaskId());
            SessionStorage storage = new SessionStorage();
            AgentMetadata previous = storage.readAgentMetadata(transcript).orElse(null);
            String agentType = request.fork() ? "fork"
                : StringUtils.defaultIfBlank(request.subagentType(), "general-purpose");
            storage.writeAgentMetadata(transcript, new AgentMetadata(
                previous != null && StringUtils.isNotBlank(previous.agentType())
                    ? previous.agentType() : agentType,
                previous == null ? null : previous.worktreePath(),
                previous != null && previous.description() != null
                    ? previous.description() : request.description(),
                true,
                previous != null && previous.spawnDepth() != null
                    ? previous.spawnDepth() : request.agentDepth(),
                previous != null && previous.subagentMaxDepth() != null
                    ? previous.subagentMaxDepth() : request.subagentMaxDepthSnapshot()));
        });
    }

    private static double normalizeAgentProgress(double progress) {
        double normalized = progress > 1.0 ? progress / 100.0 : progress;
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    private static String effectiveAgentType(SubAgentRequest request) {
        return StringUtils.isBlank(request.subagentType())
            ? "general-purpose" : request.subagentType();
    }

    private static void awaitFirstModelRequest(CountDownLatch firstModelRequest) {
        if (firstModelRequest == null) return;
        try {
            firstModelRequest.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }


    private boolean initAsyncOutputSymlink(Path outputPath, ToolExecutionContext context,
                                           String agentId) {
        try {
            Path transcript = new SessionManager(context.workingDirectory())
                .getAgentTranscriptPath(context.sessionId(), agentId);
            Files.createDirectories(outputPath.getParent());
            Files.deleteIfExists(outputPath);
            Files.createSymbolicLink(outputPath, transcript);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException _) {
            return false;
        }
    }

    /**
     * Spawns an in-process teammate (agent-teams subsystem) and returns
     * immediately. matches {@link #handleAsyncExecution} but registers an
     * {@code IN_PROCESS_TEAMMATE} task, gives the teammate an isolated
     * {@link TeammateContext}, and routes its permission/plan asks the leader
     * over the in-JVM mailbox instead of prompting the user directly.
     *
     * <p>The teammate's sub-agent runs on a virtual thread with the leader's
     * {@link ToolExecutionContext} callback swapped for a
     * {@link TeammatePermissionAskCallback}, so any permission ask the teammate
     * makes is forwarded to the leader and blocks only the teammate thread.
     */
    private String handleTeammateExecution(SubAgentRequest request, String description,
                                           String agentName, ToolExecutionContext context) {
        String taskDescription = StringUtils.isBlank(description) ? request.prompt() : description;

        TaskRegistry registry = taskRegistry != null ? taskRegistry : TaskRegistry.global();
        TaskState task = registry.store().create(TaskType.IN_PROCESS_TEAMMATE, taskDescription);
        registry.store().updatePrompt(task.id(), request.prompt());
        // Ensure the leader-side TEAM_LEAD consumer is running so the teammate's
        // permission/plan/idle asks are actually serviced (idempotent).
        TeammateLeaderCoordinator.instance().start();
// start transitions the task to RUNNING; don't pre-set it here or the
        // second transition (RUNNING -> RUNNING) throws an IllegalStateException.

        AbortController abortController = new AbortController();
// Teammates always run under the DEFAULT permission mode and forward every permission/plan
// ask the leader.
        TeammateContext teammateContext = TeammateContext.builder()
            .agentId(task.id())
            .teamId(request.teamId())
            .permissionMode(PermissionMode.DEFAULT)

            // ExitPlanMode call must be approved by the team lead. Keep this
            // separate from the live permission mode, which is reset after approval.
            .planMode(request.permissionMode() == PermissionMode.PLAN)
            .abortController(abortController)
            .name(agentName)
            .build();

        // Break the cycle: the handle needs the (callback-carrying) request, and
// the callback needs the handle. The supplier resolves by the time ask
        // is invoked (during the run, after the handle is started).
        AtomicReference<InProcessTeammateTask> handleRef = new AtomicReference<>();
        TeammatePermissionAskCallback teammateCallback =
            new TeammatePermissionAskCallback(handleRef::get);

// Coordination tools the teammate needs to talk to the leader / peers.
        List<String> teammateTools = new ArrayList<>(request.tools() != null ? request.tools() : DEFAULT_SAFE_TOOLS);
        if (!teammateTools.contains("SendMessage")) {
            teammateTools.add("SendMessage");
        }

        // Wrap the first prompt in a teammate-message envelope so the model knows

        // inProcessRunner injecting the teammate-message wrapper).
        String wrappedPrompt = wrapTeammatePrompt(request.prompt(), task.id());

        SubAgentRequest teammateRunRequest = request
            .withPrompt(wrappedPrompt)
            .withTools(teammateTools)
            .withParentContext(context.withPermissionAskCallback(teammateCallback))
            .withAbortController(abortController);

        // A teammate spawned into a team reads/writes that team's shared task list

        // teammates sharing one ~/.claude/tasks/{team}/ list.
        TodoStore teamTodoStore = (StringUtils.isNotBlank(request.teamId()))
            ? TeamTaskListRegistry.instance().getOrCreate(request.teamId())
            : null;

        InProcessTeammateTask handle = new InProcessTeammateTask(
            task, registry.store(), subAgentFactory, teammateRunRequest, teammateContext,
            teamTodoStore, teammateHookDispatcher);
        handleRef.set(handle);
        registry.registerTeammate(handle);
// Track the spawned teammate as an active member of its team so TeamDeleteTool's
// active-member guard can refuse deletion while it runs.
        if (StringUtils.isNotBlank(request.teamId())) {
            TeamRegistry.instance().addAgent(request.teamId(), task.id(),
                teammateContext.name(), request.subagentType(), request.model(), request.cwd());
        }
        handle.start();


        return "In-process teammate launched successfully.\ntaskId: " + task.id()
            + "\nThe teammate is running in the same process. You will be notified "
            + "automatically when it completes.";
    }

    /**
     * Wraps a teammate's initial prompt in a teammate-message envelope so the model understands it is a
     * teammate assignment and how to report back to the leader.
     */
    private static String wrapTeammatePrompt(String prompt, String taskId) {


        // teammate system-prompt addendum).
        return "<teammate-message teammate_id=\"team-lead\" summary=\"" + taskId + "\">"
            + "You are an in-process teammate (task " + taskId + "). "
            + "Communicate with the team leader using the SendMessage tool. "
            + "The leader will send you work; complete one task at a time, then wait "
            + "for the next message. When you are done, report your result briefly.\n"
            + "Your task:\n" + prompt + "</teammate-message>";
    }

    private void writeOutput(Path path, String content) {        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException _) {
            // Best-effort: a failed output write shouldn't crash the background
            // runner — the task status transition below still records the
            // outcome even if the .output file is unavailable for this run.
        }
    }

    /**
     * Builds the protocol content of a completed synchronous agent's
     * {@code tool_result}.
     */
    private ToolResult completedResult(SubAgentResult result, SubAgentRequest request,
                                       String fallbackAgentId) {
        if (!isSuccessful(result)) {
            String agentId = result == null
                ? fallbackAgentId : result.agentId().orElse(fallbackAgentId);
            return failedResult(terminationMessage(result), request, agentId);
        }
        List<TextBlock> blocks = resultBlocks(result, request.subagentType(), fallbackAgentId);
        String agentId = result.agentId().orElse(fallbackAgentId);
        String resolvedModel = result.resolvedModel() != null
            ? result.resolvedModel() : request.model();
        Usage usage = result.usage() != null ? result.usage() : Usage.EMPTY;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "completed");
        payload.put("prompt", request.prompt());
        payload.put("agentId", agentId);
        payload.put("agentType", request.subagentType());
        payload.put("content", StringUtils.isBlank(result.output())
            ? List.of()
            : List.of(Map.of("type", "text", "text", result.output())));
        payload.put("resolvedModel", resolvedModel);
        payload.put("totalDurationMs", result.durationMs());
        payload.put("totalTokens", result.tokensUsed());
        payload.put("totalToolUseCount", result.toolUseCount());
        payload.put("usage", usage);
        if (result.toolUseCount() > 0) {
            Map<String, Object> toolStats = new LinkedHashMap<>();
            toolStats.put("readCount", 0);
            toolStats.put("searchCount", 0);
            toolStats.put("bashCount", 0);
            toolStats.put("editFileCount", 0);
            toolStats.put("linesAdded", 0);
            toolStats.put("linesRemoved", 0);
            toolStats.put("otherToolCount", result.toolUseCount());
            payload.put("toolStats", toolStats);
        }

        return new ToolResult(new ArrayList<>(blocks), false)
            .withContentForm(ToolResultContentForm.BLOCKS)
            .withToolUseResult(payload);
    }

    @Explanation("Persists UI-only Agent failure identity so Ctrl+O can reload the separate "
        + "sidechain transcript after /resume; model-visible tool-result content is unchanged")
    private static ToolResult failedResult(String message, SubAgentRequest request,
                                           String agentId) {
        String content = StringUtils.defaultIfBlank(message, "Command failed with no output");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "failed");
        payload.put("prompt", request == null ? null : request.prompt());
        payload.put("agentId", agentId);
        payload.put("agentType", request == null ? null : request.subagentType());
        payload.put("error", content);
        return ToolResult.error(content).withToolUseResult(payload);
    }

    private static boolean isSuccessful(SubAgentResult result) {
        return result != null
            && !result.isError()
            && result.termination() == SubAgentTermination.COMPLETED;
    }

    private static String terminationMessage(SubAgentResult result) {
        if (result == null) return "Sub-agent execution failed";
        return result.error()
            .filter(StringUtils::isNotBlank)
            .orElseGet(() -> switch (result.termination()) {
                case COMPLETED, FAILED -> StringUtils.defaultIfBlank(result.output(), "Sub-agent execution failed");
                case MAX_BUDGET -> "Sub-agent stopped: maximum budget exceeded.";
                case MAX_TURNS -> "Sub-agent stopped: maximum turns reached.";
                case INTERRUPTED -> "Sub-agent was interrupted.";
            });
    }

    private static ToolResult failedResult(Throwable error, SubAgentRequest request,
                                           String agentId) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return failedResult(ToolErrors.formatError(cause), request, agentId);
    }

    private String formatResultText(SubAgentResult result, String subagentType) {
        String fallbackAgentId = result.agentId().orElse(null);
        return resultBlocks(result, subagentType, fallbackAgentId).stream()
            .map(TextBlock::text)
            .collect(Collectors.joining("\n"))
            .trim();
    }

    private List<TextBlock> resultBlocks(SubAgentResult result, String subagentType,
                                         String fallbackAgentId) {
        List<TextBlock> blocks = new ArrayList<>();
        String output = result.output();
        if (!StringUtils.isBlank(output)) {
            blocks.add(new TextBlock(output));
        } else {

            // emit an explicit marker so the parent has something to react to
            // (otherwise it reads the trailing metadata as "nothing to act on").
            blocks.add(new TextBlock("(Subagent completed but returned no output.)"));
        }

        boolean oneShot = subagentType != null && ONE_SHOT_BUILTIN_AGENT_TYPES.contains(subagentType);
        if (oneShot && result.worktreePath().isEmpty()) {
            return List.copyOf(blocks);
        }

        StringBuilder trailer = new StringBuilder();
        String agentId = result.agentId().orElse(fallbackAgentId);
        if (StringUtils.isNotBlank(agentId)) {
/* Keep this literal synchronized with asyncLaunchResult above. */
            trailer.append("agentId: ").append(agentId)
              .append(" (use SendMessage with to: '").append(agentId)
              .append("', summary: '<5-10 word recap>' to continue this agent)");
        }
        result.worktreePath().ifPresent(wp -> {
            if (!trailer.isEmpty()) trailer.append('\n');
            trailer.append("worktreePath: ").append(wp);
            result.worktreeBranch().ifPresent(wb -> trailer.append("\nworktreeBranch: ").append(wb));
        });
        if (!trailer.isEmpty()) trailer.append('\n');
        trailer.append("<usage>subagent_tokens: ").append(result.tokensUsed())
          .append("\ntool_uses: ").append(result.toolUseCount())
          .append("\nduration_ms: ").append(result.durationMs())
          .append("</usage>");
        blocks.add(new TextBlock(trailer.toString()));

        return List.copyOf(blocks);
    }



    private boolean isKnownAgentType(String subagentType, ToolExecutionContext context) {
        if (Strings.CS.equals("in_process_teammate", subagentType)) {
            return true;
        }
        String cwd = (context.workingDirectory() != null)
            ? context.workingDirectory() : System.getProperty("user.dir");
        return AgentDefinitionLoader.getAll(cwd).stream()
            .anyMatch(a -> a.agentType().equals(subagentType));
    }

    private AgentDefinition findAgentDefinition(
            String subagentType, ToolExecutionContext context) {
        String effectiveType = StringUtils.defaultIfBlank(
            subagentType, "general-purpose");
        String cwd = context != null && context.workingDirectory() != null
            ? context.workingDirectory() : System.getProperty("user.dir");
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(agent -> Strings.CS.equals(effectiveType, agent.agentType()))
            .findFirst().orElse(null);
    }


    private String listKnownAgentTypes(ToolExecutionContext context) {
        String cwd = (context.workingDirectory() != null)
            ? context.workingDirectory() : System.getProperty("user.dir");
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(agent -> isAgentModelAvailable(agent, context))
            .map(AgentDefinition::agentType)
            .distinct().sorted()
            .collect(Collectors.joining(", "));
    }

    private boolean isAgentModelAvailable(
            AgentDefinition definition, ToolExecutionContext context) {
        try {
            return modelPolicy.resolveAgent(definition, null,
                context != null ? context.currentModel() : null).outcome()
                != SubAgentModelPolicy.Outcome.REJECT;
        } catch (RuntimeException _) {
            return false;
        }
    }

    private SubAgentRequest buildRequest(JsonNode input, ToolExecutionContext context) {

        String prompt = input.has("prompt") ? input.get("prompt").asText("") : "";
        if (StringUtils.isBlank(prompt)) {

            prompt = input.has("task") ? input.get("task").asText("") : "";
        }
        String description = input.has("description") ? input.get("description").asText("") : "";


        String subagentType = null;
        if (input.has("subagent_type") && !input.get("subagent_type").isNull()) {
            subagentType = input.get("subagent_type").asText();
        }

        // Resolve once for tool inheritance and the background trigger.

        AgentDefinition resolvedDef = findAgentDefinition(subagentType, context);

        boolean runInBackground = extractBool(input, "run_in_background",
            extractBool(input, "async", false));
        boolean fork = extractBool(input, "fork", false);

        // Explicit background requests, agent frontmatter, and fork mode force
        // asynchronous execution unless background tasks are disabled.
        boolean async = !backgroundTasksDisabled()
            && (runInBackground || (resolvedDef != null && resolvedDef.background()) || fork);



        // over the inherited cwd / session worktree.
        String requestCwd = input.has("cwd") && !input.get("cwd").isNull()
            ? input.get("cwd").asText() : null;
        Integer maxTurns = fork
            ? Integer.valueOf(FORK_MAX_TURNS)
            : resolvedDef != null ? resolvedDef.maxTurns() : null;

        boolean teammateRequest = (input.has("name") && !input.get("name").isNull()
            && input.has("team_name") && !input.get("team_name").isNull())
            || Strings.CS.equals("in_process_teammate", subagentType);
        int maxDepthSnapshot = effectiveMaxDepth(context);
        int childDepth = Math.max(0, context.agentDepth()) + 1;

        SubAgentRequest.Builder builder = SubAgentRequest.builder()
            .prompt(prompt)
            // Preserve omission for lifecycle/wire metadata; definition and
            // tool resolution still use general-purpose internally.
            .subagentType(subagentType)

            .teammate(teammateRequest)

            .agentDepth(teammateRequest ? 0 : childDepth)
            .subagentMaxDepthSnapshot(teammateRequest ? null : maxDepthSnapshot)
            .budgetUsd(extractBudget(input))
            .parentContext(context)
            // Share the parent session's command queue with the sub-engine so
            // agentId-routed task notifications (e.g. a background bash the
            // sub-agent starts) land on the same queue the sub-engine drains.
            // Null on the main thread → sub-engine falls back to its own queue.
            .parentQueue(context.messageQueueManager())
            .async(async)
            .fork(fork)
            .maxTurns(maxTurns)
            .cwd(requestCwd)
// Bridge sub-agent progress (incl.
            .progressCallback(new SubAgentRequest.ProgressCallback() {
                @Override
                public void onProgress(String status, double progress) {
                    context.reportProgress(progress, status);
                }

                @Override
                public void onAgentMessage(Message message,
                        String agentId) {
                    if (!async) {
                        String resolvedModel = message instanceof AssistantMessage assistant
                            && assistant.message() != null ? assistant.message().model() : null;
                        context.reportProgress(ToolExecutionContext.ProgressUpdate.agent(
                            message, "", agentId, resolvedModel));
                    }
                }
            })
            .description(description);


        // conversation history and exact tool array to runAgent. Capture those
        // immutable snapshots before the child engine is created; ordinary
        // sub-agents continue through the independent prompt/tool path below.
        // context was already dereferenced unconditionally above
// (context.messageQueueManager, context.reportProgress(...)), so the
        // fork snapshot needs no separate null guard.
        if (fork) {
            if (!context.conversationMessages().isEmpty()) {
                builder.priorMessages(ForkMessageBuilder.build(
                    context.conversationMessages(), prompt));
            }
            if (StringUtils.isNotBlank(context.renderedSystemPrompt())) {
                builder.systemPromptOverride(context.renderedSystemPrompt());
            }
        }
        PermissionMode requestedPermissionMode = null;

        if (StringUtils.isNotBlank(context.currentModel())) {
            builder.model(context.currentModel());
        }

        if (resolvedDef != null) {
            builder.disallowedTools(resolvedDef.disallowedTools());
            if (resolvedDef.model() != null && !Strings.CS.equals("inherit", resolvedDef.model())) {
                builder.model(resolvedDef.model());
            }
            if (StringUtils.isNotBlank(resolvedDef.effort())) {
                builder.effort(resolvedDef.effort());
            }
            if (StringUtils.isNotBlank(resolvedDef.permissionMode())) {
                requestedPermissionMode = parseInternalPermissionMode(
                    resolvedDef.permissionMode());
            }
        }

// Resolve tools: caller-explicit tools win; otherwise inherit the agent definition's
// allow-list.
        List<String> explicitTools = extractToolList(input);
        if (fork) {
            // Fork children use the parent's exact model-visible tool array so
            // their cache prefix remains stable. The restricted executor has a
            // separate fork branch that preserves this set, including Agent;
            // recursive fork attempts are rejected by the fork directive/history
            // guard in the child rather than by changing the tool definition.
            if (!context.enabledTools().isEmpty()) {
                builder.tools(context.enabledTools());
            }
        } else if (!explicitTools.isEmpty()) {

            // interactive / coordination tools requiring a human at the main
            // thread (see {@link #AGENT_DISALLOWED_TOOLS}). If the caller named
            // ONLY such tools, fall back to the safe default rather than an
            // empty list — an empty list would otherwise be treated by
            // RestrictedToolExecutor as "allow everything".
            List<String> agentTools = explicitTools.stream()
                .filter(t -> !AGENT_DISALLOWED_TOOLS.contains(t))
                .collect(Collectors.toList());
            if (agentTools.isEmpty()) {
                agentTools = new ArrayList<>(DEFAULT_SAFE_TOOLS);
            }
            builder.tools(agentTools);
        } else if (resolvedDef != null) {

            if (resolvedDef.color() != null) {
                AgentColorStore.set(subagentType, resolvedDef.color());
            }
            // Tools list of "*" means "all tools" — pass empty so the factory
            // resolves and freezes the registry's ordered tool catalogue at
            // child-session creation. Otherwise inherit the definition's order.
            if (!resolvedDef.tools().contains("*")) {
                builder.tools(resolvedDef.tools());
            }
        } else {
            // No explicit tools and no agent definition: safe default tool set.
            builder.tools(new ArrayList<>(DEFAULT_SAFE_TOOLS));
        }

        String toolSpecifiedModel = input.has("model") && !input.get("model").isNull()
            ? input.get("model").asText() : null;
        SubAgentModelPolicy.Decision effectiveModel = modelPolicy.resolveAgent(
            resolvedDef, toolSpecifiedModel, context.currentModel());
        builder.model(effectiveModel.model());

        if (input.has("effort") && !input.get("effort").isNull()) {
            builder.effort(input.get("effort").asText());
        }

        if (input.has("permission_mode") && !input.get("permission_mode").isNull()) {
            String rawMode = input.get("permission_mode").asText();
            if (Strings.CS.equals("bubble", rawMode)) {
                requestedPermissionMode = PermissionMode.DEFAULT;
            } else {
                try {
                    requestedPermissionMode = PermissionMode.valueOf(
                        rawMode.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException _) {
                }
            }
        }


        // the internal sub-agent-only `bubble` mode; parseInternalPermissionMode
        // maps that to DEFAULT permission decisions without exposing it globally.
        if (input.has("mode") && !input.get("mode").isNull()) {
            requestedPermissionMode = parseInternalPermissionMode(
                input.get("mode").asText());
        }

        PermissionMode parentPermissionMode = context.currentPermissionMode() == null
            ? null : PermissionMode.fromString(context.currentPermissionMode().wireValue());
        PermissionMode effectivePermissionMode = resolveSubAgentPermissionMode(
            parentPermissionMode, requestedPermissionMode);
        if (effectivePermissionMode != null) {
            builder.permissionMode(effectivePermissionMode);
        }

        if (input.has("worktree_branch") && !input.get("worktree_branch").isNull()) {
            builder.worktreeBranch(input.get("worktree_branch").asText());
        }


        // dedicated git worktree (DefaultSubAgentFactory.createAgentWorktree),
        // cleaned up after if it made no changes. The "remote" value is ant-only
        // and not declared in Java's schema (see inputSchema comment).
        if (input.has("isolation") && !input.get("isolation").isNull()
                && Strings.CS.equals("worktree", input.get("isolation").asText())) {
            builder.worktreeIsolation(true);
        }

        if (input.has("team_id") && !input.get("team_id").isNull()) {
            builder.teamId(input.get("team_id").asText());
        }

        if (input.has("remote_agent_id") && !input.get("remote_agent_id").isNull()) {
            builder.remoteAgentId(input.get("remote_agent_id").asText());
        }

        if (input.has("mcp_server_ids") && input.get("mcp_server_ids").isArray()) {
            List<String> serverIds = new ArrayList<>();
            for (JsonNode node : input.get("mcp_server_ids")) {
                serverIds.add(node.asText());
            }
            builder.mcpServerIds(serverIds);
        }

        // Thread the resolved agent definition's experimental critical reminder

        // toolUseContext.criticalSystemReminder_EXPERIMENTAL from the agent def).
        // The sub-agent factory forwards it to QuerySessionSpec.criticalSystemReminder,
        // re-injected by CriticalSystemReminderProvider as a system-reminder.
        builder.criticalSystemReminder(resolvedDef != null ? resolvedDef.criticalSystemReminder() : null);

        return builder.build();
    }


    private static PermissionMode resolveSubAgentPermissionMode(
            PermissionMode parentMode, PermissionMode requestedMode) {
        if (parentMode == null) {
            return requestedMode;
        }
        if (requestedMode == null
                || parentMode == PermissionMode.BYPASS_PERMISSIONS
                || parentMode == PermissionMode.ACCEPT_EDITS
                || parentMode == PermissionMode.AUTO) {
            return parentMode;
        }
        return requestedMode;
    }

    /**
     * Parses the internal sub-agent permission-mode union.
     */
    static PermissionMode parseInternalPermissionMode(String rawMode) {
        return Strings.CS.equals("bubble", rawMode)
            ? PermissionMode.DEFAULT
            : PermissionMode.fromString(rawMode);
    }

    private List<String> extractToolList(JsonNode input) {
        if (input.has("tools") && input.get("tools").isArray()) {
            List<String> tools = new ArrayList<>();
            for (JsonNode toolNode : input.get("tools")) {
                tools.add(toolNode.asText());
            }
            return tools;
        }
        if (input.has("allowed_tools") && input.get("allowed_tools").isArray()) {
            List<String> tools = new ArrayList<>();
            for (JsonNode toolNode : input.get("allowed_tools")) {
                tools.add(toolNode.asText());
            }
            return tools;
        }
        // No caller-specified tool list. Returning EMPTY (not DEFAULT_SAFE_TOOLS)
        // lets buildRequest fall through to the agent definition's own allow-list
        // (verification's read/run-only set, Explore/Plan's sets, or
        // general-purpose's "*" → allow everything). An anonymous sub-agent with
        // neither explicit tools nor a definition gets DEFAULT_SAFE_TOOLS via the
        // else branch in buildRequest.
        return List.of();
    }

    private double extractBudget(JsonNode input) {
        if (input.has("budget_usd")) {
            return input.get("budget_usd").asDouble(BUDGET_FRACTION);
        }
        if (input.has("max_budget_usd")) {
            return input.get("max_budget_usd").asDouble(BUDGET_FRACTION);
        }
        return BUDGET_FRACTION;
    }

    private boolean extractBool(JsonNode input, String field, boolean defaultValue) {
        if (input.has(field) && !input.get(field).isNull()) {
            return input.get(field).asBoolean(defaultValue);
        }
        return defaultValue;
    }


    private static boolean backgroundTasksDisabled() {
        return BackgroundTaskGate.disabled();
    }


    private long autoBackgroundDelayMs() {
        if (autoBackgroundDelayOverrideMs != null) {
            return Math.max(0L, autoBackgroundDelayOverrideMs);
        }
        if (EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_AUTO_BACKGROUND_TASKS"))) {
            return 120_000L;
        }
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                JsonNode feature = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON)
                    .path("cachedGrowthBookFeatures")
                    .get("tengu_auto_background_agents");
                if (feature != null && feature.isBoolean() && feature.asBoolean()) {
                    return 120_000L;
                }
            }
        } catch (Exception _) {

        }
        return 0L;
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        String agentType = input == null
            ? "general-purpose"
            : StringUtils.defaultIfBlank(input.path("subagent_type").asText(null),
                "general-purpose");
        if (permCtx != null
                && PermissionEngine.getDenyRuleForAgent(permCtx, agentType).isPresent()) {
            return new PermissionDecision.Deny(
                "Permission to use Agent(" + agentType + ") has been denied.", null);
        }
        return PermissionDecision.allow();
    }



    private static JsonNode buildSchema() {

// applies the model-visible feature profile observed in the lossless.

        // equivalent in either version) and are dropped from the model-facing
        // contract for the same reason; buildRequest() below still honors
        // them if a caller passes them directly (unreachable via the model
        // now that they're unschema'd, but kept for programmatic callers).
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");


        properties.putObject("description")
            .put("type", "string")
            .put("description", "A short (3-5 word) description of the task");


        properties.putObject("prompt")
            .put("type", "string")
            .put("description", "The task for the agent to perform");


// When omitted and fork mode is enabled, the agent forks; when omitted without fork
// mode the general-purpose agent is used.
        properties.putObject("subagent_type")
            .put("type", "string")
            .put("description", "The type of specialized agent to use for this task");


        ObjectNode modelProp = properties.putObject("model");
        modelProp.put("type", "string");
        ArrayNode modelEnum = modelProp.putArray("enum");

        modelEnum.add("sonnet").add("opus").add("haiku").add("fable");
        modelProp.put("description",
            "Optional model override for this agent. Takes precedence over the agent "
            + "definition's model frontmatter. If omitted, uses the agent definition's "
            + "model, or inherits from the parent. Ignored for subagent_type: \"fork\" "
            + "— forks always inherit the parent model.");



        properties.putObject("run_in_background")
            .put("type", "boolean")
            .put("description",
                "Set to true to run this agent in the background. You will be "
                + "notified when it completes.");



        properties.putObject("name").put("type", "string")
            .put("description", "Name for the spawned agent.");
        properties.putObject("team_name").put("type", "string")
            .put("description", "Team name for spawning.");
        properties.putObject("mode").put("type", "string");
        properties.putObject("cwd").put("type", "string")
            .put("description", "Absolute working directory for the agent.");



        ObjectNode isolationProp = properties.putObject("isolation");
        isolationProp.put("type", "string");
        ArrayNode isolationEnum = isolationProp.putArray("enum");
        isolationEnum.add("worktree");
        isolationProp.put("description",
            "Isolation mode. \"worktree\" creates a temporary git worktree "
            + "so the agent works on an isolated copy of the repo.");


        schema.putArray("required").add("description").add("prompt");

        return schema;
    }
}
