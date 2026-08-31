package com.claudecode.tools.tasks;

import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskRegistryTest {

    private TaskRegistry newRegistry() {
        return new TaskRegistry(TaskStore.inMemory());
    }

    @Test
    void terminalAgentCanBePreparedFor197StyleTranscriptResume() {
        TaskRegistry registry = newRegistry();
        TaskState original = registry.store().createWithId(
            "a1234567890abcdef", TaskType.LOCAL_AGENT, "audit", null);
        registry.store().updateStatus(original.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(original.id(), TaskStatus.COMPLETED);

        TaskState resumed = registry.prepareAgentResume(
            original.id(), "audit", "continue", "Explore", "tool-2");

        assertEquals(original.id(), resumed.id());
        assertEquals(TaskStatus.RUNNING, resumed.status());
        assertEquals("continue", registry.store().prompt(original.id()).orElseThrow());
        assertEquals("Explore", registry.store().agentType(original.id()).orElseThrow());
        assertEquals("tool-2", resumed.toolUseId().orElseThrow());
    }

    @Test
    void listBackground_includesRunningAndPending() {
        TaskRegistry registry = newRegistry();
        TaskState pending = registry.store().create(TaskType.LOCAL_BASH, "pending task");
        TaskState running = registry.store().create(TaskType.LOCAL_BASH, "running task");
        registry.store().updateStatus(running.id(), TaskStatus.RUNNING);

        var background = registry.listBackground();
        assertEquals(2, background.size());
        assertTrue(background.stream().anyMatch(t -> t.id().equals(pending.id())));
        assertTrue(background.stream().anyMatch(t -> t.id().equals(running.id())));
    }

    @Test
    void listBackground_excludesTerminalStates() {
        TaskRegistry registry = newRegistry();
        TaskState completed = registry.store().create(TaskType.LOCAL_BASH, "done");
        registry.store().updateStatus(completed.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(completed.id(), TaskStatus.COMPLETED);

        TaskState failed = registry.store().create(TaskType.LOCAL_BASH, "failed");
        registry.store().updateStatus(failed.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(failed.id(), TaskStatus.FAILED);

        TaskState killed = registry.store().create(TaskType.LOCAL_BASH, "killed");
        registry.store().updateStatus(killed.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(killed.id(), TaskStatus.KILLED);

        assertTrue(registry.listBackground().isEmpty());
    }

    @Test
    void killShell_delegatesToHandle() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "sleep 100");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        LocalShellTask handle = new LocalShellTask(task, "sleep 100", registry.store());
        registry.registerShell(handle);

        assertTrue(registry.killShell(task.id()));
        assertEquals(TaskStatus.KILLED, registry.store().get(task.id()).get().status());
    }

    @Test
    void killShell_unknownTaskId_returnsFalse() {
        TaskRegistry registry = newRegistry();
        assertFalse(registry.killShell("nonexistent"));
    }

    @Test
    void killAgent_delegatesToHandle() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "explore repo");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        registry.registerAgent(handle);

        assertTrue(registry.killAgent(task.id()));
        assertEquals(TaskStatus.KILLED, registry.store().get(task.id()).get().status());
    }

    @Test
    void userInitiatedKillPersistsTheStoppedMarker() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().createWithId(
            "agent-stopped", TaskType.LOCAL_AGENT, "inspect", null);
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        AtomicBoolean persisted = new AtomicBoolean();
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());
        handle.setStoppedByUserPersister(() -> persisted.set(true));
        registry.registerAgent(handle);

        assertTrue(registry.killAgentByUser(task.id()));
        assertTrue(persisted.get());
    }

    @Test
    void killAgent_unknownTaskId_returnsFalse() {
        TaskRegistry registry = newRegistry();
        assertFalse(registry.killAgent("nonexistent"));
    }

    @Test
    void foregroundAgent_isHiddenUntilBackgroundedInPlace() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "inspect repository");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, registry.store());

        registry.registerAgentForeground(handle);

        assertTrue(registry.listBackground().stream().noneMatch(t -> t.id().equals(task.id())));
        assertTrue(registry.listForegroundBackgroundable().stream()
            .anyMatch(t -> t.id().equals(task.id())));

        assertTrue(registry.backgroundAgent(task.id()));
        assertTrue(registry.listForegroundBackgroundable().isEmpty());
        assertTrue(registry.listBackground().stream().anyMatch(t -> t.id().equals(task.id())));
        assertSame(handle, registry.getAgentHandle(task.id()).orElseThrow(),
            "foreground-to-background must keep the same live handle");
    }

    @Test
    void unregisterForegroundAgent_removesCompletedBeforeBackgroundTask() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "quick inspection");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(task, registry.store()));

        assertTrue(registry.unregisterForegroundAgent(task.id()));

        assertTrue(registry.get(task.id()).isEmpty());
        assertTrue(registry.getAgentHandle(task.id()).isEmpty());
        assertTrue(registry.listForegroundBackgroundable().isEmpty());
    }

    @Test
    void backgroundAgent_losesRaceToTerminalTransition() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "fast agent");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(task, registry.store()));
        registry.store().updateStatus(task.id(), TaskStatus.COMPLETED);

        assertFalse(registry.backgroundAgent(task.id()));
        assertEquals(TaskStatus.COMPLETED, registry.get(task.id()).orElseThrow().status());
        assertTrue(registry.listBackground().isEmpty());
    }

    @Test
    void backgroundAllForegroundTasks_transitionsEveryLiveAgent() {
        TaskRegistry registry = newRegistry();
        TaskState first = registry.store().create(TaskType.LOCAL_AGENT, "first");
        TaskState second = registry.store().create(TaskType.LOCAL_AGENT, "second");
        registry.store().updateStatus(first.id(), TaskStatus.RUNNING);
        registry.store().updateStatus(second.id(), TaskStatus.RUNNING);
        registry.registerAgentForeground(new LocalAgentTask(first, registry.store()));
        registry.registerAgentForeground(new LocalAgentTask(second, registry.store()));

        assertEquals(2, registry.backgroundAllForegroundTasks());
        assertTrue(registry.listForegroundBackgroundable().isEmpty());
        assertEquals(2, registry.listBackground().size());
        assertEquals(0, registry.backgroundAllForegroundTasks());
    }

    @Test
    void foregroundShell_isHiddenThenBackgroundedWithoutChangingHandle() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, "sleep 10");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        AtomicBoolean activated =
            new AtomicBoolean();
        ForegroundShellTask handle = new ForegroundShellTask(task, registry.store(),
            Path.of("/tmp", task.id() + ".output"),
            () -> activated.set(true));
        registry.registerShellForeground(handle);

        assertTrue(registry.listBackground().isEmpty());
        assertEquals(1, registry.listForegroundBackgroundable().size());
        assertEquals(1, registry.backgroundAllForegroundTasks());
        assertTrue(activated.get());
        assertEquals(task.id(), registry.listBackground().getFirst().id());
        assertSame(handle, registry.getForegroundShellHandle(task.id()).orElseThrow());
    }

    @Test
    void runningAgentAcceptsQueuedContinuationUntilTerminal() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "explore repo");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgent(new LocalAgentTask(task, registry.store()));

        assertTrue(registry.queueAgentMessage(task.id(), "inspect the failing test"));
        assertEquals(List.of("inspect the failing test"),
            registry.drainAgentMessages(task.id()));
        registry.store().updateStatus(task.id(), TaskStatus.COMPLETED);
        assertFalse(registry.queueAgentMessage(task.id(), "too late"));
    }

    @Test
    void queuedAgentContinuationRetainsModelVisibleSender() {
        TaskRegistry registry = newRegistry();
        TaskState task = registry.store().create(TaskType.LOCAL_AGENT, "explore repo");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        registry.registerAgent(new LocalAgentTask(task, registry.store()));
        registry.registerAgentName("researcher", task.id());

        assertTrue(registry.queueAgentMessage(
            task.id(), "start on task 1", "researcher"));

        assertEquals(List.of(new TaskRegistry.PendingAgentMessage(
            "start on task 1", "researcher")),
            registry.drainAgentMessageEnvelopes(task.id()));
        assertEquals("researcher", registry.resolveAgentName(task.id()));
    }

    @Test
    void globalRegistry_isInMemory_startsEmptyEvenWithPersistedTaskFiles() {

        // is pure in-memory — a fresh process must start with ZERO background
        // tasks regardless of what's on disk.
        TaskRegistry.resetGlobalForTest();
        try {
            assertTrue(TaskRegistry.global().store().list().isEmpty(),
                "global background-task store must start empty (in-memory, never reads ~/.claude/tasks)");
            assertTrue(TaskRegistry.global().listBackground().isEmpty());
        } finally {
            TaskRegistry.resetGlobalForTest();
        }
    }

    @Test
    void globalSingleton_returnsStableInstance() {
        TaskRegistry.resetGlobalForTest();
        TaskRegistry a = TaskRegistry.global();
        TaskRegistry b = TaskRegistry.global();
        assertSame(a, b);
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void setGlobalForTest_overridesSingleton() {
        TaskRegistry custom = newRegistry();
        TaskRegistry.setGlobalForTest(custom);
        assertSame(custom, TaskRegistry.global());
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void agentExitKillsOwnedMonitorAndPurgesItsQueuedEvents() {
        TaskRegistry registry = newRegistry();
        MessageQueueManager queue = new MessageQueueManager();
        registry.setMessageQueue(queue);
        TaskState task = registry.store().create(
            TaskType.MONITOR_WS, "deploy events", "agent-7");
        registry.store().updateStatus(task.id(), TaskStatus.RUNNING);
        AtomicBoolean killed = new AtomicBoolean();
        registry.registerMonitor(new MonitorTaskHandle() {
            @Override public String getTaskId() { return task.id(); }
            @Override public Path getOutputPath() { return Path.of("monitor.output"); }
            @Override public String displaySource() { return "wss://example.com/events"; }
            @Override public boolean kill() {
                killed.set(true);
                registry.store().updateStatus(task.id(), TaskStatus.KILLED);
                return true;
            }
        });
        queue.enqueuePendingNotification(new QueuedCommand(
            "event", null, "task-notification", QueuePriority.NEXT,
            true, null, false, false, null, null, "agent-7"));
        queue.enqueue(QueuedCommand.prompt("keep"));

        registry.killShellAndMonitorTasksForAgent("agent-7");

        assertTrue(killed.get());
        assertEquals(TaskStatus.KILLED, registry.store().get(task.id()).orElseThrow().status());
        assertEquals(1, queue.size());
        assertEquals("keep", queue.dequeue().text());
    }
}
