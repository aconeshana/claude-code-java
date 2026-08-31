package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.tools.agent.SubAgentResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalAgentTaskTest {

    @Test
    void complete_transitionsRunningTaskToCompleted() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        handle.complete(SubAgentResult.of("done"));

        assertEquals(TaskStatus.COMPLETED, store.get(task.id()).get().status());
        assertTrue(store.get(task.id()).get().endTime().isPresent());
        assertEquals(1.0, handle.getProgress());
    }

    @Test
    void fail_transitionsRunningTaskToFailed() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        handle.fail("boom");

        assertEquals(TaskStatus.FAILED, store.get(task.id()).get().status());
    }

    @Test
    void kill_runningTask_transitionsToKilledAndInterruptsRunner() throws InterruptedException {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        var interrupted = new AtomicBoolean(false);
        Thread runner = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException _) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        handle.setRunnerThread(runner);
        runner.start();
        // Give the runner a moment to reach the sleep call.
        Thread.sleep(50);

        boolean killed = handle.kill();

        assertTrue(killed);
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
        assertTrue(store.get(task.id()).get().notified());
        runner.join(2000);
        assertTrue(interrupted.get(), "runner thread should have observed the interrupt");
    }

    @Test
    void kill_notRunningTask_isNoOp() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        // Still PENDING — never transitioned to RUNNING.
        LocalAgentTask handle = new LocalAgentTask(task, store);

        assertFalse(handle.kill());
        assertEquals(TaskStatus.PENDING, store.get(task.id()).get().status());
    }

    @Test
    void kill_withoutTaskStore_isBestEffortNoOp() {
        TaskState task = TaskState.create(TaskType.LOCAL_AGENT, "explore repo").withStatus(TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task);

        assertFalse(handle.kill(), "kill() without a TaskStore has nothing to transition");
    }

    @Test
    void complete_stampsPanelEvictionDeadline() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        assertTrue(store.evictAfter(task.id()).isEmpty(),
            "a running task is not scheduled for eviction");

        Instant before = Instant.now().plus(LocalAgentTask.PANEL_GRACE);
        handle.complete(SubAgentResult.of("done"));

        Instant deadline = store.evictAfter(task.id()).orElseThrow();
        assertFalse(deadline.isBefore(before),
            "completion stamps evictAfter at least PANEL_GRACE into the future");
    }

    @Test
    void fail_stampsPanelEvictionDeadline() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        handle.fail("boom");

        assertTrue(store.evictAfter(task.id()).isPresent(),
            "failure schedules the terminal row for eviction");
    }

    @Test
    void kill_stampsPanelEvictionDeadline() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        assertTrue(handle.kill());
        assertTrue(store.evictAfter(task.id()).isPresent(),
            "kill schedules the terminal row for eviction");
    }

    @Test
    void updateProgress_rejectsOutOfRangeValues() {
        TaskState task = TaskState.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task);

        assertThrows(IllegalArgumentException.class, () -> handle.updateProgress(-0.1, "x"));
        assertThrows(IllegalArgumentException.class, () -> handle.updateProgress(1.1, "x"));
    }

    @Test
    void liveUsageIsVisibleBeforeCompletionAndIsNotReplacedByASmallerFinalSnapshot() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "usage");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);

        handle.updateUsage(12_345, 7, 1_000);

        assertEquals(new TaskUsage(12_345, 7, 1_000),
            store.get(task.id()).orElseThrow().usage().orElseThrow());
        handle.complete(SubAgentResult.of("done", 500, 0, 2, 2_000));
        assertEquals(12_345,
            store.get(task.id()).orElseThrow().usage().orElseThrow().totalTokens());
        assertEquals(7,
            store.get(task.id()).orElseThrow().usage().orElseThrow().toolUses());
    }

    @Test
    void requestBackground_completesOneShotSignalWithoutCancellingAgent() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "backgroundable");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);
        AbortController controller = new AbortController();
        handle.setAbortController(controller);

        assertTrue(handle.requestBackground());
        handle.backgroundSignal().get(1, TimeUnit.SECONDS);
        assertFalse(handle.requestBackground(), "background transition is one-shot");
        assertFalse(controller.isAborted(), "backgrounding must not cancel live execution");
    }


    @Test
    void kill_abortsInjectedController() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "abortable");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);
        AbortController controller = new AbortController();
        handle.setAbortController(controller);

        assertTrue(handle.kill());
        assertTrue(controller.isAborted(),
            "kill must abort the sub-engine's query loop, not just interrupt the thread");
        assertEquals("killed via /tasks", controller.getReason());
    }

    @Test
    void kill_withoutController_stillTransitions() {
        // Pre-injection construction sites keep working (controller optional).
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "legacy");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        LocalAgentTask handle = new LocalAgentTask(task, store);
        assertTrue(handle.kill());
        assertEquals(TaskStatus.KILLED,
            store.get(task.id()).orElseThrow().status());
    }
}
