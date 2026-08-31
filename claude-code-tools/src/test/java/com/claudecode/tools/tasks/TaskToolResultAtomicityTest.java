package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolResultAtomicityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void taskGetTextAndStructuredResultUseOneSnapshot(@TempDir Path tasksBase) {
        MutatingGetStore store = new MutatingGetStore(tasksBase, "session");
        Task task = store.create("before", "description", null, Map.of());
        TaskGetTool tool = new TaskGetTool(store);
        var input = MAPPER.createObjectNode().put("taskId", task.id());

        var result = tool.callWithResult(input, context());

        JsonNode payload = (JsonNode) result.mappedResult().toolUseResult();
        assertTrue(result.rawResult().contains("before"));
        assertEquals("before", payload.path("task").path("subject").asText());
    }

    @Test
    void taskListTextAndStructuredResultUseOneSnapshot(@TempDir Path tasksBase) {
        MutatingListStore store = new MutatingListStore(tasksBase, "session");
        store.create("before", "description", null, Map.of());
        TaskListTool tool = new TaskListTool(store);

        var result = tool.callWithResult(MAPPER.createObjectNode(), context());

        JsonNode payload = (JsonNode) result.mappedResult().toolUseResult();
        assertTrue(result.rawResult().contains("before"));
        assertEquals("before", payload.path("tasks").get(0).path("subject").asText());
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "session");
    }

    private static final class MutatingGetStore extends TodoStore {
        private boolean mutated;

        private MutatingGetStore(Path tasksBase, String taskListId) {
            super(tasksBase, taskListId);
        }

        @Override
        public Optional<Task> get(String taskId) {
            Optional<Task> current = super.get(taskId);
            if (!mutated && current.isPresent()) {
                mutated = true;
                super.update(taskId, current.orElseThrow().withSubject("after"));
            }
            return current;
        }
    }

    private static final class MutatingListStore extends TodoStore {
        private boolean mutated;

        private MutatingListStore(Path tasksBase, String taskListId) {
            super(tasksBase, taskListId);
        }

        @Override
        public List<Task> list() {
            List<Task> current = super.list();
            if (!mutated && !current.isEmpty()) {
                mutated = true;
                Task first = current.getFirst();
                super.update(first.id(), first.withSubject("after"));
            }
            return current;
        }
    }
}
