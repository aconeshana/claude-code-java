package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ToolTexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TaskGet — retrieves a single task's full detail from the model-facing
 * to-do list ({@link TodoStore}).
 *
 * <ul>
 *   <li>input schema
 *       ({@code taskId} required, {@code additionalProperties:false}), the
 *       {@code call} flow (missing task → {@code null} payload, found task →
 *       trimmed field projection), and the
 *       {@code mapToolResultToToolResultBlockParam} multi-line result text
 *       (subject/status/description + conditional blocked-by/blocks lines).
 *       Java applies the same {@code isTodoV2Enabled} catalogue gate.</li>
 * </ul>
 */
@BuiltInTool(
    name = "TaskGet",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class TaskGetTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "retrieve a task by ID";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private static final JsonNode OUTPUT_SCHEMA = TaskToolOutputSchemas.taskGet();

    private final TaskBoardService taskBoard;

    public TaskGetTool(TodoStore todoStore) {
        this(new TaskBoardService(todoStore, () -> null));
    }

    TaskGetTool(TaskBoardService taskBoard) {
        this.taskBoard = taskBoard;
    }

    @Override
    public String description() {

        return ToolTexts.description("TaskGet");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TaskGet");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public JsonNode outputSchema() { return OUTPUT_SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("taskId").asText("");
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String taskId = input.get("taskId").asText();
        TodoStore activeStore = taskBoard.resolveStore(
            context != null ? context.sessionId() : null);
        activeStore.reload();
        Optional<Task> found = activeStore.get(taskId);
        if (found.isEmpty()) {
            return "Task not found";
        }

        Task task = found.get();
        List<String> lines = new ArrayList<>();
        lines.add("Task #" + task.id() + ": " + task.subject());
        lines.add("Status: " + TodoStatusWire.toWire(task.status()));
        lines.add("Description: " + task.description());
        if (!task.blockedBy().isEmpty()) {
            lines.add("Blocked by: " + joinIds(task.blockedBy()));
        }
        if (!task.blocks().isEmpty()) {
            lines.add("Blocks: " + joinIds(task.blocks()));
        }
        return String.join("\n", lines);
    }

    @Override
    public ToolCallResult<String> callWithResult(
            JsonNode input, ToolExecutionContext context) {
        String taskId = input.get("taskId").asText();
        TodoStore activeStore = taskBoard.resolveStore(
            context != null ? context.sessionId() : null);
        activeStore.reload();
        Optional<Task> found = activeStore.get(taskId);
        String text = found.map(TaskGetTool::formatTask).orElse("Task not found");
        return new ToolCallResult<>(text,
            ToolResult.success(text).withToolUseResult(taskPayload(found)));
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        String taskId = input == null ? "" : input.path("taskId").asText("");
        TodoStore activeStore = taskBoard.resolveStore(
            context != null ? context.sessionId() : null);
        activeStore.reload();
        Optional<Task> found = activeStore.get(taskId);
        return ToolResult.success(rawResult == null ? "" : rawResult.toString())
            .withToolUseResult(taskPayload(found));
    }

    @Override
    public ToolResult mapUpdatedOutput(
            JsonNode updatedOutput, JsonNode input, ToolExecutionContext context) {
        JsonNode task = updatedOutput.path("task");
        String text = task.isNull() ? "Task not found" : formatTask(task);
        return ToolResult.success(text).withToolUseResult(updatedOutput);
    }

    private static String formatTask(Task task) {
        List<String> lines = new ArrayList<>();
        lines.add("Task #" + task.id() + ": " + task.subject());
        lines.add("Status: " + TodoStatusWire.toWire(task.status()));
        lines.add("Description: " + task.description());
        if (!task.blockedBy().isEmpty()) {
            lines.add("Blocked by: " + joinIds(task.blockedBy()));
        }
        if (!task.blocks().isEmpty()) {
            lines.add("Blocks: " + joinIds(task.blocks()));
        }
        return String.join("\n", lines);
    }

    private static String formatTask(JsonNode task) {
        List<String> lines = new ArrayList<>();
        lines.add("Task #" + task.path("id").asText() + ": " + task.path("subject").asText());
        lines.add("Status: " + task.path("status").asText());
        lines.add("Description: " + task.path("description").asText());
        if (!task.path("blockedBy").isEmpty()) {
            lines.add("Blocked by: " + joinIds(task.path("blockedBy")));
        }
        if (!task.path("blocks").isEmpty()) {
            lines.add("Blocks: " + joinIds(task.path("blocks")));
        }
        return String.join("\n", lines);
    }

    private static ObjectNode taskPayload(Optional<Task> found) {
        ObjectNode payload = mapper().createObjectNode();
        if (found.isEmpty()) {
            payload.putNull("task");
            return payload;
        }
        Task task = found.orElseThrow();
        ObjectNode value = payload.putObject("task");
        value.put("id", task.id());
        value.put("subject", task.subject());
        value.put("description", task.description());
        value.put("status", TodoStatusWire.toWire(task.status()));
        value.set("blocks", mapper().valueToTree(task.blocks()));
        value.set("blockedBy", mapper().valueToTree(task.blockedBy()));
        return payload;
    }

    private static String joinIds(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("#").append(ids.get(i));
        }
        return sb.toString();
    }

    private static String joinIds(JsonNode ids) {
        List<String> formatted = new ArrayList<>();
        ids.forEach(id -> formatted.add("#" + id.asText()));
        return String.join(", ", formatted);
    }




    @Override public boolean isEnabled() { return TaskToolsGate.isEnabled(); }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("taskId")
            .put("description", "The ID of the task to retrieve")
            .put("type", "string");
        ArrayNode required = schema.putArray("required");
        required.add("taskId");
        schema.put("additionalProperties", false);
        return schema;
    }
}
