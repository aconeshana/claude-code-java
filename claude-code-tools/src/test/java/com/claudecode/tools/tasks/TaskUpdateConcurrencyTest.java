package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskUpdateConcurrencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void concurrentDisjointUpdatesMergeAgainstTheLatestPersistedTask(@TempDir Path tasksBase)
            throws Exception {
        TodoStore creator = new TodoStore(tasksBase, "session");
        Task created = creator.create("old subject", "old description", null, Map.of());
        CyclicBarrier bothUpdatesHaveReadTheOriginal = new CyclicBarrier(2);
        TodoStore subjectStore = new CoordinatedTodoStore(
            tasksBase, "session", bothUpdatesHaveReadTheOriginal);
        TodoStore descriptionStore = new CoordinatedTodoStore(
            tasksBase, "session", bothUpdatesHaveReadTheOriginal);
        TaskUpdateTool subjectTool = new TaskUpdateTool(subjectStore);
        TaskUpdateTool descriptionTool = new TaskUpdateTool(descriptionStore);
        ObjectNode subjectInput = MAPPER.createObjectNode()
            .put("taskId", created.id())
            .put("subject", "new subject");
        ObjectNode descriptionInput = MAPPER.createObjectNode()
            .put("taskId", created.id())
            .put("description", "new description");
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "session");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> subjectUpdate = executor.submit(
                () -> subjectTool.call(subjectInput, context));
            Future<String> descriptionUpdate = executor.submit(
                () -> descriptionTool.call(descriptionInput, context));
            subjectUpdate.get(5, TimeUnit.SECONDS);
            descriptionUpdate.get(5, TimeUnit.SECONDS);
        }

        Task persisted = new TodoStore(tasksBase, "session")
            .get(created.id()).orElseThrow();
        assertEquals("new subject", persisted.subject());
        assertEquals("new description", persisted.description());
    }

    private static final class CoordinatedTodoStore extends TodoStore {
        private final CyclicBarrier barrier;
        private boolean coordinated;

        private CoordinatedTodoStore(Path tasksBase, String taskListId, CyclicBarrier barrier) {
            super(tasksBase, taskListId);
            this.barrier = barrier;
        }

        @Override
        public Optional<Task> get(String taskId) {
            Optional<Task> task = super.get(taskId);
            if (!coordinated) {
                coordinated = true;
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return task;
        }
    }
}
