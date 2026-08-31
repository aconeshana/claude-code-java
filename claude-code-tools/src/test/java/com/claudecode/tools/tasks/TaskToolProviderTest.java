package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.TaskReminderItem;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.ToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolProviderTest {

    @Test
    void disabledTaskV2KeepsLegacyTodoWriteOutOfTheTaskBoardLikeReleased197() {
        String sessionId = "legacy-" + UUID.randomUUID();
        ToolRegistry registry = new ToolRegistry();
        TodoWriteTool todoWrite = new TodoWriteTool();
        registry.register(todoWrite);
        try (TaskToolProvider provider = new TaskToolProvider(
                TodoStore.inMemory(), () -> sessionId, () -> false)) {
            provider.initialize(registry);
            ObjectNode input = new ObjectMapper().createObjectNode();
            ObjectNode todo = input.putArray("todos").addObject();
            todo.put("content", "legacy task");
            todo.put("status", "in_progress");
            todo.put("activeForm", "working legacy task");
            ToolExecutionContext context = ToolExecutionContext.of(
                new AbortController(), sessionId);

            todoWrite.call(input, context);

            TaskBoardPort.Snapshot snapshot = provider.taskBoard().snapshot();
            assertTrue(snapshot.hidden());
            assertTrue(snapshot.tasks().isEmpty());
            assertEquals(List.of(new TodoWriteTool.TodoItem(
                "legacy task", "in_progress", "working legacy task")),
                TodoWriteTool.getTodos(sessionId));
        }
    }

    @Test
    void exposesBorrowedViewsAndOwnsTaskBoardLifecycle() {
        TodoStore store = TodoStore.inMemory();
        store.create("borrowed task", "description", "working", Map.of("source", "test"));
        try (TaskToolProvider provider = new TaskToolProvider(store)) {
            TaskBoardPort taskBoard = provider.taskBoard();
            TaskReminderSource reminders = provider.taskReminders();

            assertTrue(AutoCloseable.class.isAssignableFrom(TaskToolProvider.class));
            assertFalse(AutoCloseable.class.isAssignableFrom(TaskBoardPort.class));
            assertFalse(AutoCloseable.class.isAssignableFrom(TaskReminderSource.class));
            assertFalse(taskBoard instanceof TaskBoardService);
            assertEquals(List.of(new TaskReminderItem(
                "1", "borrowed task", "description", "working", null,
                "pending", List.of(), List.of(), Map.of("source", "test"))),
                reminders.currentReminders());
        }
    }

    @Test
    void remindersIncludeInternalTasksThatStayHiddenFromTheBoard() {
        TodoStore store = TodoStore.inMemory();
        store.create("visible", "shown on board", null, Map.of());
        store.create("internal", "still model-visible in reminders", null,
            Map.of("_internal", true));
        try (TaskToolProvider provider = new TaskToolProvider(store)) {
            assertEquals(List.of("visible"), provider.taskBoard().snapshot().tasks().stream()
                .map(TaskBoardPort.TaskItem::subject).toList());
            assertEquals(List.of("visible", "internal"),
                provider.taskReminders().currentReminders().stream()
                    .map(TaskReminderItem::subject).toList());
        }
    }
}
