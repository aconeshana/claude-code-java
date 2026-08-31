package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TaskToolPostOutputMappingTest {

    @Test
    void todoWriteReplacementUsesReleased197SuccessText() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        replacement.putArray("oldTodos");
        replacement.putArray("newTodos");

        ToolResult result = new TodoWriteTool().mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode(), null);

        assertEquals("Todos have been modified successfully. Ensure that you continue to use the todo "
            + "list to track your progress. Please proceed with the current tasks if applicable",
            text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskCreateReplacementUsesReplacementTask() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        replacement.putObject("task").put("id", "9").put("subject", "Replacement subject");

        ToolResult result = new TaskCreateTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement,
            JsonUtils.getMapper().createObjectNode()
                .put("subject", "Original subject")
                .put("description", "Original description"),
            null);

        assertEquals("Task #9 created successfully: Replacement subject", text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskGetReplacementUsesReplacementSnapshot() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        ObjectNode task = replacement.putObject("task");
        task.put("id", "7");
        task.put("subject", "Replacement");
        task.put("description", "Hook-provided details");
        task.put("status", "in_progress");
        task.putArray("blocks").add("8");
        task.putArray("blockedBy").add("6");

        ToolResult result = new TaskGetTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode().put("taskId", "7"), null);

        assertEquals("""
            Task #7: Replacement
            Status: in_progress
            Description: Hook-provided details
            Blocked by: #6
            Blocks: #8""", text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskGetNullReplacementMapsToNotFound() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        replacement.putNull("task");

        ToolResult result = new TaskGetTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode().put("taskId", "missing"), null);

        assertEquals("Task not found", text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskListReplacementUsesReplacementOrderAndFields() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        ArrayNode tasks = replacement.putArray("tasks");
        ObjectNode first = tasks.addObject();
        first.put("id", "2");
        first.put("subject", "Second");
        first.put("status", "pending");
        first.put("owner", "alice");
        first.putArray("blockedBy").add("1");
        ObjectNode second = tasks.addObject();
        second.put("id", "1");
        second.put("subject", "First");
        second.put("status", "completed");
        second.putArray("blockedBy");

        ToolResult result = new TaskListTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode(), null);

        assertEquals("""
            #2 [pending] Second (alice) [blocked by #1]
            #1 [completed] First""", text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskListReplacementOmitsEmptyOwnerLikeReleased197() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        ObjectNode task = replacement.putArray("tasks").addObject();
        task.put("id", "1");
        task.put("subject", "Unassigned");
        task.put("status", "pending");
        task.put("owner", "");
        task.putArray("blockedBy");

        ToolResult result = new TaskListTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode(), null);

        assertEquals("#1 [pending] Unassigned", text(result));
        assertSame(replacement, result.toolUseResult());
    }

    @Test
    void taskListEmptyReplacementMapsToNoTasks() {
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        replacement.putArray("tasks");

        ToolResult result = new TaskListTool(TodoStore.inMemory()).mapUpdatedOutput(
            replacement, JsonUtils.getMapper().createObjectNode(), null);

        assertEquals("No tasks found", text(result));
    }

    @Test
    void taskUpdateReplacementMapsSuccessAndFailure() {
        TaskUpdateTool tool = new TaskUpdateTool(TodoStore.inMemory());
        ObjectNode success = JsonUtils.getMapper().createObjectNode();
        success.put("success", true);
        success.put("taskId", "4");
        success.putArray("updatedFields").add("subject").add("status");
        ObjectNode failure = JsonUtils.getMapper().createObjectNode();
        failure.put("success", false);
        failure.put("taskId", "5");
        failure.putArray("updatedFields");
        failure.put("error", "Failed to delete task");

        ToolResult successResult = tool.mapUpdatedOutput(
            success, JsonUtils.getMapper().createObjectNode(), null);
        ToolResult failureResult = tool.mapUpdatedOutput(
            failure, JsonUtils.getMapper().createObjectNode(), null);

        assertEquals("Updated task #4 subject, status", text(successResult));
        assertEquals("Failed to delete task", text(failureResult));
        assertSame(success, successResult.toolUseResult());
        assertSame(failure, failureResult.toolUseResult());
    }

    private static String text(ToolResult result) {
        return ((TextBlock) result.content().getFirst()).text();
    }
}
