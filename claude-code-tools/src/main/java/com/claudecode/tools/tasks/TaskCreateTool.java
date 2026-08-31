package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TaskCreate — adds a task to the model-facing to-do list (the {@link TodoStore}-backed system, not
 * the background-task system fronted by {@code TaskStore}/{@code TaskState}).
 */
@BuiltInTool(
    name = "TaskCreate",
    shouldDefer = true
)
public class TaskCreateTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "create a task in the task list";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private static final JsonNode OUTPUT_SCHEMA = TaskToolOutputSchemas.taskCreate();

    private final TaskBoardService taskBoard;

    public TaskCreateTool(TodoStore todoStore) {
        this(new TaskBoardService(todoStore, () -> null));
    }

    TaskCreateTool(TaskBoardService taskBoard) {
        this.taskBoard = taskBoard;
    }

    @Override
    public String description() {

        return ToolTexts.description("TaskCreate");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return AgentTeamsEnabled.isEnabled()
            ? ToolTexts.prompt("TaskCreate", "teammate")
            : ToolTexts.prompt("TaskCreate");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public JsonNode outputSchema() { return OUTPUT_SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("subject").asText("");
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String subject = input.get("subject").asText();
        String description = input.get("description").asText();
        String activeForm = input.hasNonNull("activeForm") ? input.get("activeForm").asText() : null;
        Map<String, Object> metadata = readMetadata(input);

        String sessionId = context != null ? context.sessionId() : null;
        TodoStore activeStore = taskBoard.resolveStore(sessionId);
        Task task = activeStore.create(subject, description, activeForm, metadata);

        TaskLifecycleHooks hooks = TodoStore.getTaskLifecycleHooks();
        if (hooks != null && hooks.hasTaskCreatedHook()) {
            List<String> blockingErrors = hooks.dispatchTaskCreated(
                task.id(), subject, description,
                taskBoard.currentTeammateName(), taskBoard.currentTeamName(sessionId));
            if (!blockingErrors.isEmpty()) {
                if (activeStore.delete(task.id())) {
                    taskBoard.publishChanged(sessionId);
                }
                throw new RuntimeException(String.join("\n", blockingErrors));
            }
        }

        taskBoard.publishChanged(sessionId);
        taskBoard.publishExpand(sessionId);
        return "Task #" + task.id() + " created successfully: " + subject;
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        String text = rawResult == null ? "" : rawResult.toString();
        ObjectNode task = mapper().createObjectNode();
        String marker = "Task #";
        int start = text.indexOf(marker);
        int end = start < 0 ? -1 : text.indexOf(' ', start + marker.length());
        String id = start >= 0
            ? text.substring(start + marker.length(), end >= 0 ? end : text.length()) : "";
        task.put("id", id);
        task.put("subject", input == null ? "" : input.path("subject").asText(""));
        ObjectNode payload = mapper().createObjectNode();
        payload.set("task", task);
        return ToolResult.success(text).withToolUseResult(payload);
    }

    @Override
    public ToolResult mapUpdatedOutput(
            JsonNode updatedOutput, JsonNode input, ToolExecutionContext context) {
        JsonNode task = updatedOutput.path("task");
        String text = "Task #" + task.path("id").asText()
            + " created successfully: " + task.path("subject").asText();
        return ToolResult.success(text).withToolUseResult(updatedOutput);
    }

    static Map<String, Object> readMetadata(JsonNode input) {
        if (!input.hasNonNull("metadata")) return null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        input.get("metadata").fields().forEachRemaining(entry ->
            metadata.put(entry.getKey(), JsonUtils.getMapper().convertValue(entry.getValue(), Object.class)));
        return metadata;
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    @Override public boolean isEnabled() { return TaskToolsGate.isEnabled(); }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("subject")
            .put("description", "A brief title for the task")
            .put("type", "string");
        props.putObject("description")
            .put("description", "What needs to be done")
            .put("type", "string");
        props.putObject("activeForm")
            .put("description", "Present continuous form shown in spinner when in_progress (e.g., \"Running tests\")")
            .put("type", "string");

        ObjectNode metadataProp = props.putObject("metadata");
        metadataProp.put("description", "Arbitrary metadata to attach to the task");
        metadataProp.put("type", "object");
        metadataProp.putObject("propertyNames").put("type", "string");
        metadataProp.putObject("additionalProperties");

        ArrayNode required = schema.putArray("required");
        required.add("subject");
        required.add("description");
        schema.put("additionalProperties", false);
        return schema;
    }
}
