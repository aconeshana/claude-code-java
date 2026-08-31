package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class BackgroundAgentCancellationActionTest {

    @Test
    void clearsExistingQueueAndPublishesOneUserStopSummary() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();
        queue.enqueue(QueuedCommand.prompt("queued draft"));
        AtomicBoolean stoppedByUser = new AtomicBoolean();
        TaskState agent = registerRunningAgent(
            registry, "inspect repository", stoppedByUser);

        BackgroundAgentCancellationAction.execute(registry, queue);

        TaskState stopped = registry.store().get(agent.id()).orElseThrow();
        assertEquals(TaskStatus.KILLED, stopped.status());
        assertTrue(stopped.notified());
        assertTrue(stoppedByUser.get());
        assertEquals(1, queue.size());
        QueuedCommand notification = queue.snapshot().getFirst();
        assertEquals("Background agent \"inspect repository\" was stopped by the user.",
            notification.text());
        assertEquals("task-notification", notification.mode());
        assertEquals(QueuePriority.LATER, notification.priority());
    }

    @Test
    void clearsQueueWithoutPublishingWhenNoBackgroundAgentIsRunning() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();
        queue.enqueue(QueuedCommand.prompt("queued draft"));

        BackgroundAgentCancellationAction.execute(registry, queue);

        assertEquals(0, queue.size());
    }

    @Test
    void combinesMultipleStoppedAgentsIntoOneNotification() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();
        registerRunningAgent(registry, "inspect", new AtomicBoolean());
        registerRunningAgent(registry, "test", new AtomicBoolean());

        BackgroundAgentCancellationAction.execute(registry, queue);

        assertEquals(1, queue.size());
        String summary = queue.snapshot().getFirst().text();
        assertTrue(Strings.CS.equals(
                "2 background agents were stopped by the user: \"inspect\", \"test\".", summary)
            || Strings.CS.equals(
                "2 background agents were stopped by the user: \"test\", \"inspect\".", summary),
            "the in-memory task store does not promise iteration order");
    }

    private static TaskState registerRunningAgent(
            TaskRegistry registry, String description, AtomicBoolean stoppedByUser) {
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, description);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        handle.setStoppedByUserPersister(() -> stoppedByUser.set(true));
        registry.registerAgent(handle);
        return task;
    }
}
