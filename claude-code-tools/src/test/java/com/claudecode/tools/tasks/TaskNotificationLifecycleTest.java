package com.claudecode.tools.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.queue.MessageQueueManager;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskNotificationLifecycleTest {

    @AfterEach
    void reset() {
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void rebindingMovesDeliveryToLatestSessionWithoutDuplicatingListeners() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager firstQueue = new MessageQueueManager();
        new TaskNotificationBridge(firstQueue, registry).register();

        complete(registry, "first");
        assertEquals(1, firstQueue.size());

        MessageQueueManager secondQueue = new MessageQueueManager();
        new TaskNotificationBridge(secondQueue, registry).register();
        complete(registry, "second");

        assertEquals(1, firstQueue.size(), "the old session queue must be released after rebinding");
        assertEquals(1, secondQueue.size(), "the latest session receives exactly one notification");
    }

    @Test
    void repeatedRegistrationIsIdempotent() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        MessageQueueManager queue = new MessageQueueManager();

        new TaskNotificationBridge(queue, registry).register();
        new TaskNotificationBridge(queue, registry).register();
        complete(registry, "once");

        assertEquals(1, queue.size(), "one registry must own only one completion subscription");
    }

    @Test
    void terminalTaskCompletedBeforeBindingIsReconciled() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState task = complete(registry, "during-session-switch");
        MessageQueueManager queue = new MessageQueueManager();

        new TaskNotificationBridge(queue, registry).register();

        assertEquals(1, queue.size(), "binding must close the completion-before-listener race");
        assertTrue(registry.store().get(task.id()).orElseThrow().notified());
    }

    @Test
    void completionSubscriptionCanBeClosed() throws Exception {
        TaskStore store = TaskStore.inMemory();
        AtomicInteger calls = new AtomicInteger();
        Consumer<TaskState> listener = _ -> calls.incrementAndGet();
        AutoCloseable subscription = store.onCompletion(listener);
        subscription.close();

        TaskState task = store.create(TaskType.LOCAL_BASH, "after-close");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        assertEquals(0, calls.get(), "a closed subscription must release its listener");
    }

    @Test
    void explicitShellCancellationAtomicallySuppressesPerTaskNotification() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, registry).register();
        TaskState task = store.create(TaskType.LOCAL_BASH, "cancel me");
        LocalShellTask handle = new LocalShellTask(
            task, "sleep 60", store, Path.of("cancel-test.output"));
        registry.registerShell(handle);
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        assertTrue(handle.kill());

        assertEquals(0, queue.size(),
            "the cancel aggregate owns user-visible notification for an explicit kill");
        assertTrue(store.get(task.id()).orElseThrow().notified());
    }

    @Test
    void foregroundAgentCompletionDoesNotEnterBackgroundNotificationLifecycle() {
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        MessageQueueManager queue = new MessageQueueManager();
        new TaskNotificationBridge(queue, registry).register();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "foreground");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(task, store));

        store.updateStatus(task.id(), TaskStatus.COMPLETED);

        assertEquals(0, queue.size(),
            "foreground completion returns through the tool result, not a background notification");
        assertTrue(registry.unregisterForegroundAgent(task.id()));
        assertTrue(store.get(task.id()).isEmpty());
    }

    private static TaskState complete(TaskRegistry registry, String description) {
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, description);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(task.id(), TaskStatus.COMPLETED);
        return task;
    }
}
