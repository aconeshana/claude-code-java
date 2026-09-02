package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TaskList — lists every task in the model-facing to-do list ({@link
 * TodoStore}) in summary form.
 *
 * <ul>
 *   <li>empty input schema
 *       ({@code additionalProperties:false}), the {@code call} flow
 *       (internal-metadata-tagged tasks filtered out, {@code blockedBy}
 *       pruned to references still unresolved), and the
 *       {@code mapToolResultToToolResultBlockParam} one-line-per-task result
 *       text (id/status/subject + conditional owner/blocked-by suffixes).
 *       Java applies the same {@code isTodoV2Enabled} catalogue gate.</li>
 * </ul>
 */
@BuiltInTool(
    name = "TaskList",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class TaskListTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "list all tasks";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private static final JsonNode OUTPUT_SCHEMA = TaskToolOutputSchemas.taskList();

    private final TaskBoardService taskBoard;

    public TaskListTool(TodoStore todoStore) {
        this(new TaskBoardService(todoStore, () -> null));
    }

    TaskListTool(TaskBoardService taskBoard) {
        this.taskBoard = taskBoard;
    }

    @Override
    public String description() {

        return ToolTexts.description("TaskList");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return AgentTeamsEnabled.isEnabled()
            ? ToolTexts.prompt("TaskList", "teammate")
            : ToolTexts.prompt("TaskList");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public JsonNode outputSchema() { return OUTPUT_SCHEMA; }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        List<Task> allTasks = taskBoard.tasksForSession(
            context != null ? context.sessionId() : null);

        Set<String> resolvedIds = allTasks.stream()
            .filter(t -> t.status() == TodoStatus.COMPLETED)
            .map(Task::id)
            .collect(Collectors.toCollection(HashSet::new));

        if (allTasks.isEmpty()) return "No tasks found";

        return allTasks.stream()
            .map(task -> formatLine(task, resolvedIds))
            .collect(Collectors.joining("\n"));
    }

    @Override
    public ToolCallResult<String> callWithResult(
            JsonNode input, ToolExecutionContext context) {
        List<Task> allTasks = taskBoard.tasksForSession(
            context != null ? context.sessionId() : null);
        Set<String> resolvedIds = resolvedIds(allTasks);
        String text = allTasks.isEmpty() ? "No tasks found" : allTasks.stream()
            .map(task -> formatLine(task, resolvedIds))
            .collect(Collectors.joining("\n"));
        return new ToolCallResult<>(text,
            ToolResult.success(text).withToolUseResult(taskListPayload(allTasks, resolvedIds)));
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        List<Task> allTasks = taskBoard.tasksForSession(
            context != null ? context.sessionId() : null);
        Set<String> resolvedIds = resolvedIds(allTasks);
        return ToolResult.success(rawResult == null ? "" : rawResult.toString())
            .withToolUseResult(taskListPayload(allTasks, resolvedIds));
    }

    @Override
    public ToolResult mapUpdatedOutput(
            JsonNode updatedOutput, JsonNode input, ToolExecutionContext context) {
        JsonNode tasks = updatedOutput.path("tasks");
        String text;
        if (tasks.isEmpty()) {
            text = "No tasks found";
        } else {
            List<String> lines = new ArrayList<>();
            tasks.forEach(task -> lines.add(formatLine(task)));
            text = String.join("\n", lines);
        }
        return ToolResult.success(text).withToolUseResult(updatedOutput);
    }

    private static Set<String> resolvedIds(List<Task> allTasks) {
        return allTasks.stream()
            .filter(task -> task.status() == TodoStatus.COMPLETED)
            .map(Task::id).collect(Collectors.toSet());
    }

    private static ObjectNode taskListPayload(
            List<Task> allTasks, Set<String> resolvedIds) {
        ArrayNode tasks = mapper().createArrayNode();
        for (Task task : allTasks) {
            ObjectNode value = tasks.addObject();
            value.put("id", task.id());
            value.put("subject", task.subject());
            value.put("status", TodoStatusWire.toWire(task.status()));
            task.owner().ifPresent(owner -> value.put("owner", owner));
            value.set("blockedBy", mapper().valueToTree(task.blockedBy().stream()
                .filter(id -> !resolvedIds.contains(id)).toList()));
        }
        ObjectNode payload = mapper().createObjectNode();
        payload.set("tasks", tasks);
        return payload;
    }

    private static String formatLine(Task task, Set<String> resolvedIds) {
        String owner = task.owner().filter(o -> !o.isEmpty())
            .map(o -> " (" + o + ")").orElse("");
        List<String> unresolvedBlockedBy = task.blockedBy().stream()
            .filter(id -> !resolvedIds.contains(id))
            .toList();
        String blocked = unresolvedBlockedBy.isEmpty() ? "" :
            " [blocked by " + unresolvedBlockedBy.stream()
                .map(id -> "#" + id)
                .collect(Collectors.joining(", ")) + "]";
        return "#" + task.id() + " [" + TodoStatusWire.toWire(task.status()) + "] "
            + task.subject() + owner + blocked;
    }

    private static String formatLine(JsonNode task) {
        String ownerName = task.path("owner").asText("");
        String owner = ownerName.isEmpty() ? "" : " (" + ownerName + ")";
        List<String> blockedIds = new ArrayList<>();
        task.path("blockedBy").forEach(id -> blockedIds.add("#" + id.asText()));
        String blocked = blockedIds.isEmpty() ? "" : " [blocked by "
            + String.join(", ", blockedIds) + "]";
        return "#" + task.path("id").asText() + " [" + task.path("status").asText() + "] "
            + task.path("subject").asText() + owner + blocked;
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
        schema.putObject("properties");
        schema.put("additionalProperties", false);
        return schema;
    }
}
