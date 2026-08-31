package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;

/**
 * TodoWriteTool — replaces the full session todo list.
 */
@BuiltInTool(
    name = "TodoWrite",
    shouldDefer = true,
    strict = true
)
public class TodoWriteTool extends AnnotatedTool<JsonNode, String> {

    private static final String SUCCESS_MESSAGE =
        "Todos have been modified successfully. Ensure that you continue to use the todo "
            + "list to track your progress. Please proceed with the current tasks if applicable";

    @Override
    public String searchHint() {
        return "manage the session task checklist";
    }


    private static final JsonNode SCHEMA = buildSchema();

/**
     * In-memory todo store keyed by session/agent ID.
     */
    private static final Map<String, List<TodoItem>> TODO_STORE = new ConcurrentHashMap<>();

    private volatile BooleanSupplier simplePromptSupplier = () -> false;

    private static final JsonNode OUTPUT_SCHEMA = TaskToolOutputSchemas.todoWrite();


    public void setSimplePromptSupplier(BooleanSupplier supplier) {
        simplePromptSupplier = supplier != null ? supplier : () -> false;
    }

    /**
     * enabled only when the v2 task-list tools are OFF, so the v1 (TodoWrite) and v2 (Task*) todo
     * surfaces stay mutually exclusive.
     */
    @Override
    public boolean isEnabled() {
        return !TaskToolsGate.isEnabled();
    }



    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        if (input.has("todos") && input.get("todos").isArray()) {
            for (JsonNode item : input.get("todos")) {
                if (!item.path("content").isTextual()
                        || item.path("content").asText().isEmpty()) {
                    return ValidationResult.invalid("Each todo must have non-empty content.");
                }
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public String description() {
        return ToolTexts.description("TodoWrite");
    }


    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TodoWrite",
            simplePromptSupplier.getAsBoolean() ? "harness" : "long");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public JsonNode outputSchema() { return OUTPUT_SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input != null && input.path("todos").isArray()
            ? input.path("todos").size() + " items" : "";
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {

        // scopes its own todo list), falling back to the session id.
        String todoKey = context.agentId() != null ? context.agentId() : context.sessionId();
        List<TodoItem> oldTodos = List.copyOf(
            TODO_STORE.getOrDefault(todoKey, Collections.emptyList()));


        List<TodoItem> newTodos = new ArrayList<>();
        if (input.has("todos") && input.get("todos").isArray()) {
            for (JsonNode item : input.get("todos")) {
                // Zod's strings are not trimmed by TodoItemSchema. Preserve the
                // exact values that passed schema validation; trimming here would
                // silently change the persisted todo text and the UI reminder.
                String content    = item.path("content").asText("");
                String status     = item.path("status").asText("");
                String activeForm = item.path("activeForm").asText("");
// Empty content is rejected by validateInput.
                if (content.isEmpty()) continue;
                newTodos.add(new TodoItem(content, status, activeForm));
            }
        }


        boolean allDone = newTodos.stream().allMatch(t -> Strings.CS.equals("completed", t.status()));
        TODO_STORE.put(todoKey, allDone ? Collections.emptyList() : newTodos);

        ObjectNode payload = mapper().createObjectNode();
        payload.set("oldTodos", todoItems(oldTodos));
        payload.set("newTodos", todoItems(newTodos));
        return new ToolCallResult<>(SUCCESS_MESSAGE,
            ToolResult.success(SUCCESS_MESSAGE).withToolUseResult(payload));
    }

    @Override
    public ToolResult mapUpdatedOutput(
            JsonNode updatedOutput, JsonNode input, ToolExecutionContext context) {
        return ToolResult.success(SUCCESS_MESSAGE).withToolUseResult(updatedOutput);
    }


    private static ArrayNode todoItems(List<TodoItem> todos) {
        ArrayNode array = mapper().createArrayNode();
        for (TodoItem todo : todos) {
            ObjectNode item = array.addObject();
            item.put("content", todo.content());
            item.put("status", todo.status());
            item.put("activeForm", todo.activeForm());
        }
        return array;
    }

    /** Returns the current todo list for a session (read by UI components). */
    public static List<TodoItem> getTodos(String sessionId) {
        return Collections.unmodifiableList(
            TODO_STORE.getOrDefault(sessionId != null ? sessionId : "", Collections.emptyList()));
    }


    public record TodoItem(String content, String status, String activeForm) {}

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");


        ObjectNode todosArr = properties.putObject("todos");
        todosArr.put("type", "array");
        todosArr.put("description", "The updated todo list");

        ObjectNode itemObj = todosArr.putObject("items");
        itemObj.put("type", "object");
        ObjectNode itemProps = itemObj.putObject("properties");

        ObjectNode contentProp = itemProps.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("minLength", 1);
        contentProp.put("description", "Imperative description of the task (e.g., \"Run tests\")");

        ObjectNode statusProp = itemProps.putObject("status");
        statusProp.put("type", "string");
        ArrayNode statusEnum = statusProp.putArray("enum");
        statusEnum.add("pending").add("in_progress").add("completed");
        statusProp.put("description", "Current status of the task");

        ObjectNode activeFormProp = itemProps.putObject("activeForm");
        activeFormProp.put("type", "string");
        activeFormProp.put("minLength", 1);
        activeFormProp.put("description",
            "Present-continuous form for spinner display (e.g., \"Running tests\")");

        itemObj.putArray("required").add("content").add("status").add("activeForm");

        schema.putArray("required").add("todos");


        schema.put("additionalProperties", false);

        return schema;
    }
}
