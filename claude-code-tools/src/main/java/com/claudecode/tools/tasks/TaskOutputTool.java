package com.claudecode.tools.tasks;

import java.util.Locale;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ToolUseRenderContext;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.tools.ValidationResult;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Retrieves output from a running or completed background task.
 */
@BuiltInTool(
    name = "TaskOutput", aliases = {"AgentOutputTool", "BashOutputTool"},
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class TaskOutputTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "read output/logs from a background task";
    }

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_TIMEOUT_MS     = 600_000;
    private static final long MAX_OUTPUT_BYTES  = 64L * 1024 * 1024;

    private final TaskStore taskStore;
    /** Env lookup seam for {@code TASK_MAX_OUTPUT_LENGTH} — see {@link TaskOutputFormatting}. */
    private final Function<String, String> envLookup;
    private static final class InvocationCapture {
        private TaskOutputInvocation invocation = new TaskOutputInvocation("timeout", null, null);
    }

    private record TaskOutputInvocation(String retrievalStatus, TaskState task,
                                        OutputFile output) {}

    /**
     * Defaults to {@link TaskRegistry#global}'s store — the in-memory store
     * where {@code BashTool}/{@code AgentTool} actually register background
     * tasks. A persistent {@code new TaskStore} here would read a different
     * (disk) task set and never find any live background task.
     */
    public TaskOutputTool() {
        this(TaskRegistry.global().store());
    }

    public TaskOutputTool(TaskStore taskStore) {
        this(taskStore, SubprocessEnvironment::get);
    }

    /** Test seam: inject an env lookup instead of the real process environment. */
    TaskOutputTool(TaskStore taskStore, Function<String, String> envLookup) {
        this.taskStore = taskStore;
        this.envLookup = envLookup != null ? envLookup : SubprocessEnvironment::get;
    }

    @Override
    public String description() {
        return ToolTexts.description("TaskOutput");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TaskOutput");
    }

    @Override
    public Optional<ToolUseTag> renderToolUseTag(
            JsonNode input, ToolUseRenderContext context) {
        String taskId = input == null ? "" : input.path("task_id").asText("");
        return StringUtils.isBlank(taskId)
            ? Optional.empty() : Optional.of(ToolUseTag.dim(taskId));
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");

        props.putObject("task_id")
             .put("type", "string")
             .put("description", "The task ID to get output from");

        props.putObject("block")
             .put("type", "boolean")
             .put("default", true)
             .put("description", "Whether to wait for completion");


// z.number, not an int refinement, so the wire schema type is
// "number"; zod's.default also lands the field in "required"
        // (safeParse still applies the default when the key is absent, but
        // the openapi/JSON-Schema generator doesn't special-case defaulted
        // fields out of the required array).
        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "number")
               .put("minimum", 0)
               .put("maximum", MAX_TIMEOUT_MS)
               .put("default", DEFAULT_TIMEOUT_MS)
               .put("description", "Max wait time in ms");

        ArrayNode required = schema.putArray("required");
        required.add("task_id");
        required.add("block");
        required.add("timeout");
        return schema;
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("task_id").asText("");
    }




    @Override public boolean isEnabled() {
        return !Strings.CI.equals("ant", envLookup.apply("USER_TYPE"));
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }



    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String taskId = input == null ? "" : input.path("task_id").asText("");
        if (StringUtils.isBlank(taskId)) {
            return ValidationResult.invalid("Task ID is required");
        }
        if (taskStore.get(taskId).isEmpty()) {
            return ValidationResult.invalid("No task found with ID: " + taskId);
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return invoke(input, context, new InvocationCapture());
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        InvocationCapture capture = new InvocationCapture();
        String text = invoke(input, context, capture);
        return new ToolCallResult<>(text, mapInvocation(text, capture.invocation));
    }

    private String invoke(JsonNode input, ToolExecutionContext context, InvocationCapture capture) {
        TaskOutputPaths.initialize(context);
        String taskId = input.has("task_id") ? input.get("task_id").asText("") : "";
        boolean block = !input.has("block") || input.get("block").asBoolean(true);
        int timeout   = input.has("timeout") ? input.get("timeout").asInt(DEFAULT_TIMEOUT_MS) : DEFAULT_TIMEOUT_MS;
        timeout = Math.min(timeout, MAX_TIMEOUT_MS);

        if (StringUtils.isBlank(taskId)) {
            return "Error: task_id is required";
        }

        Optional<TaskState> taskOpt = taskStore.get(taskId);
        if (taskOpt.isEmpty()) {
            return "Error: No task found with ID: " + taskId;
        }
        TaskState task = taskOpt.get();

        // block=false: non-blocking — return current state immediately
        if (!block) {
            String status = task.status().isTerminal() ? "success" : "not_ready";
            if (task.status().isTerminal()) {
                taskStore.markNotified(taskId);
            }
            return buildResult(task, readOutputForTask(task), status, capture);
        }

        // block=true: await TaskStore's per-task completion signal. This preserves

        // polling the store at a fixed interval.
        if (!task.status().isTerminal()) {


            // the eventual tool_result: the UI uses it to render the waiting
            // state and the engine stamps the current tool_use identity onto it.
            if (context != null) {
                context.reportProgress(ToolExecutionContext.ProgressUpdate.of(
                    0.0,
                    "Waiting for task " + task.description(),
                    "waiting_for_task",
                    context.toolUseId(),
                    null,
                    null,
                    null,
                    0L,
                    0L,
                    0.0,
                    timeout,
                    false));
            }
            try {
                taskOpt = taskStore.awaitTerminal(taskId, Duration.ofMillis(timeout));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                taskOpt = taskStore.get(taskId);
            }
            if (taskOpt.isEmpty()) {
                return "<retrieval_status>timeout</retrieval_status>";
            }
            task = taskOpt.get();
        }


        // the retrieval_status timeout line, NOT the full task block.

        String retrievalStatus = task.status().isTerminal() ? "success" : "timeout";
        if (task.status().isTerminal()) {
            taskStore.markNotified(taskId);
        }
        return buildResult(task, readOutputForTask(task), retrievalStatus, capture);
    }


    private OutputFile readOutputForTask(TaskState task) {
        if (task.type() == TaskType.LOCAL_AGENT) {
            Optional<String> finalMessage = task.finalMessage();
            if (finalMessage.isPresent() && !StringUtils.isBlank(finalMessage.get())) {
                return new OutputFile(finalMessage.get(), null);
            }
        }
        return readOutput(task.id());
    }


    private String buildResult(TaskState task, OutputFile output, String retrievalStatus,
                               InvocationCapture capture) {
        capture.invocation = new TaskOutputInvocation(retrievalStatus, task, output);
        List<String> parts = new ArrayList<>();
        parts.add("<retrieval_status>" + retrievalStatus + "</retrieval_status>");
        parts.add("<task_id>" + task.id() + "</task_id>");
        parts.add("<task_type>" + toTsType(task.type()) + "</task_type>");
        parts.add("<status>" + toTsStatus(task.status()) + "</status>");

        task.exitCode().ifPresent(code -> parts.add("<exit_code>" + code + "</exit_code>"));
        if (output != null && output.content() != null && !StringUtils.isBlank(output.content())) {
            TaskOutputFormatting.Formatted formatted =
                TaskOutputFormatting.formatTaskOutput(output.content(), output.file(), envLookup);
            parts.add("<output>\n" + formatted.content().stripTrailing() + "\n</output>");
        }
        task.errorMessage().ifPresent(err -> {
            if (!StringUtils.isBlank(err)) parts.add("<error>" + err + "</error>");
        });
        return String.join("\n\n", parts);
    }


    private ToolResult mapInvocation(String text, TaskOutputInvocation invocation) {
        ObjectNode payload = mapper().createObjectNode();
        payload.put("retrieval_status", invocation.retrievalStatus());
        if (invocation.task() == null) {
            payload.putNull("task");
        } else {
            TaskState task = invocation.task();
            ObjectNode value = payload.putObject("task");
            value.put("task_id", task.id());
            value.put("task_type", toTsType(task.type()));
            value.put("status", toTsStatus(task.status()));
            value.put("description", task.description());
            value.put("output", invocation.output() == null || invocation.output().content() == null
                ? "" : invocation.output().content());
            if (task.type() == TaskType.LOCAL_BASH) {
                if (task.exitCode().isPresent()) value.put("exitCode", task.exitCode().get());
                else value.putNull("exitCode");
            }
            if (task.type() == TaskType.LOCAL_AGENT) {
                value.put("prompt", taskStore.prompt(task.id()).orElse(task.description()));
                value.put("result", invocation.output() == null || invocation.output().content() == null
                    ? "" : invocation.output().content());
                task.errorMessage().ifPresent(error -> value.put("error", error));
            } else if (task.type() == TaskType.REMOTE_AGENT) {
                value.put("prompt", task.description());
            }
        }
        return ToolResult.success(text).withToolUseResult(payload);
    }


    private static String toTsType(TaskType type) {
        return switch (type) {
            case LOCAL_BASH    -> "local_bash";
            case LOCAL_AGENT   -> "local_agent";
            case REMOTE_AGENT  -> "remote_agent";
            default            -> type.name().toLowerCase(Locale.ROOT);
        };
    }


    private static String toTsStatus(TaskStatus status) {
        return switch (status) {
            case PENDING   -> "pending";
            case RUNNING   -> "running";
            case PAUSED    -> "paused";
            case COMPLETED -> "completed";
            case FAILED    -> "failed";
            case KILLED    -> "killed";
        };
    }

    /** Output file content plus the resolved path it was read from. */
    private record OutputFile(String content, Path file) {}

    private OutputFile readOutput(String taskId) {
        Path p = TaskOutputPaths.outputPath(taskId);
        if (Files.isRegularFile(p)) {
            try {
                long size = Files.size(p);
                if (size > MAX_OUTPUT_BYTES) {
                    return new OutputFile("(output too large: " + size + " bytes — use Read tool)", p);
                }
                return new OutputFile(Files.readString(p, StandardCharsets.UTF_8), p);
            } catch (IOException e) {
                return new OutputFile("(failed to read output file: " + e.getMessage() + ")", p);
            }
        }
        return null;
    }
}
