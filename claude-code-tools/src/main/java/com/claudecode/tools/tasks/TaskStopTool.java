package com.claudecode.tools.tasks;

import java.util.Locale;

import com.claudecode.core.engine.ToolExecutionContext;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.tools.ValidationResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Tool to stop/kill a running task.
 *
 * <p>Ports  (call → {@code
 * }): looks up the task, validates it is {@code RUNNING},
 * kills the underlying process/agent via {@link TaskRegistry#killTask(String)}
 * (which also flips the store status to {@code KILLED}), and returns a JSON
 * result {@code {message, task_id, task_type, command}} — {@code
 * mapToolResultToToolResultBlockParam} JSON-stringifies that object.</p>
 */
@BuiltInTool(
    name = "TaskStop", aliases = {"KillShell"},
    shouldDefer = true,
    concurrencySafe = true
)
public class TaskStopTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "kill a running background task";
    }

    private final TaskStore taskStore;

    public TaskStopTool(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public String description() {
        return ToolTexts.description("TaskStop");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TaskStop");
    }

    @Override
    public JsonNode inputSchema() {

        // optional (shell_id is the deprecated KillShell alias's compat param);
        // validateInput enforces "at least one of the two" at runtime, not schema.
        // z.strictObject → additionalProperties:false.
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("task_id").put("type", "string")
            .put("description", "The ID of the background task to stop");
        props.putObject("shell_id").put("type", "string")
            .put("description", "Deprecated: use task_id instead");
        return schema;
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        String taskId = input.path("task_id").asText("");
        return !StringUtils.isBlank(taskId) ? taskId : input.path("shell_id").asText("");
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String taskId = input == null ? ""
            : (StringUtils.isBlank(input.path("task_id").asText(""))
                ? input.path("shell_id").asText("") : input.path("task_id").asText(""));
        if (StringUtils.isBlank(taskId)) {
            return ValidationResult.invalid("Missing required parameter: task_id");
        }
        Optional<TaskState> task = taskStore.get(taskId);
        if (task.isEmpty()) {
            return ValidationResult.invalid("No task found with ID: " + taskId);
        }
        if (task.get().status() != TaskStatus.RUNNING) {
            return ValidationResult.invalid("Task " + taskId + " is not running (status: "
                + toTsStatus(task.get().status()) + ")");
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String taskId = input.has("task_id") && !input.get("task_id").isNull()
            ? input.get("task_id").asText()
            : (input.has("shell_id") && !input.get("shell_id").isNull()
                ? input.get("shell_id").asText() : "");
        if (StringUtils.isBlank(taskId)) {

            return error("Missing required parameter: task_id");
        }

        Optional<TaskState> taskOpt = taskStore.get(taskId);
        if (taskOpt.isEmpty()) {
            return error("No task found with ID: " + taskId);
        }
        TaskState task = taskOpt.get();
        if (task.status() != TaskStatus.RUNNING) {
            return error("Task " + taskId + " is not running (status: "
                + toTsStatus(task.status()) + ")");
        }

// HIGH: actually terminate the underlying process/agent.
        boolean killed = TaskRegistry.global().killTask(taskId);
        if (!killed) {
            taskStore.updateStatusAndMarkNotified(taskId, TaskStatus.KILLED);
        }
        // Re-read so the result reflects the post-kill state (status KILLED).
        TaskState stopped = taskStore.get(taskId).orElse(task);


// object { message, task_id, task_type, command }.
        String command = stopped.description();
        ObjectNode out = mapper().createObjectNode();
        out.put("message", "Successfully stopped task: " + stopped.id() + " (" + command + ")");
        out.put("task_id", stopped.id());
        out.put("task_type", toTsType(stopped.type()));
        out.put("command", command);
        try {
            return mapper().writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return error("Failed to serialize result: " + e.getMessage());
        }
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        String text = rawResult == null ? "" : rawResult.toString();
        try {
            JsonNode payload = mapper().readTree(text);
            if (payload != null && payload.isObject() && payload.has("task_id")) {
                return ToolResult.success(text).withToolUseResult(payload);
            }
        } catch (JsonProcessingException _) {
            // Validation/error text remains the model-facing result only.
        }
        return ToolResult.success(text);
    }


    private static String error(String message) {
        return "Error: " + message;
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


    private static String toTsType(TaskType type) {
        return switch (type) {
            case LOCAL_BASH    -> "local_bash";
            case LOCAL_AGENT   -> "local_agent";
            case REMOTE_AGENT  -> "remote_agent";
            case IN_PROCESS_TEAMMATE -> "in_process_teammate";
            default            -> type.name().toLowerCase(Locale.ROOT);
        };
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


}
