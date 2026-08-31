package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskUpdateFailureResultTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void failedDeleteOmitsStatusChangeLikeReleased197(@TempDir Path tasksBase) {
        TodoStore store = new DeleteFailingStore(tasksBase, "session");
        Task task = store.create("keep", "delete fails", null, Map.of());
        TaskUpdateTool tool = new TaskUpdateTool(store);
        JsonNode input = MAPPER.createObjectNode()
            .put("taskId", task.id())
            .put("status", "deleted");

        var result = tool.callWithResult(input,
            ToolExecutionContext.of(new AbortController(), "session"));

        JsonNode payload = (JsonNode) result.mappedResult().toolUseResult();
        assertFalse(payload.path("success").asBoolean());
        assertFalse(payload.has("statusChange"));
    }

    private static final class DeleteFailingStore extends TodoStore {
        private DeleteFailingStore(Path tasksBase, String taskListId) {
            super(tasksBase, taskListId);
        }

        @Override
        public synchronized boolean delete(String taskId) {
            return false;
        }
    }
}
