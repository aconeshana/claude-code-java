package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.tools.agent.SubAgentResult;

import java.time.Instant;
import java.util.List;
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

    @Test
    void displayTranscript_startsEmptySoViewerKnowsToSeedFromDisk() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);

        assertTrue(handle.displayTranscript().isEmpty());
        assertFalse(handle.hasDisplayTranscript());
    }

    @Test
    void appendToDisplayTranscript_notifiesListenerOncePerAcceptedRow() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);
        int[] notifications = {0};
        handle.setTranscriptListener(() -> notifications[0]++);

        handle.appendToDisplayTranscript(userMessage("u1", "hello"));
        handle.appendToDisplayTranscript(userMessage("u1", "hello"));
        handle.appendToDisplayTranscript(userMessage("u2", "world"));

        assertEquals(2, handle.displayTranscript().size());
        assertEquals(2, notifications[0], "a duplicate uuid must not fire the listener");
    }

    @Test
    void appendToDisplayTranscript_dropsOldestRowsPastTheCap() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);

        for (int i = 0; i < 250; i++) {
            handle.appendToDisplayTranscript(userMessage("u" + i, "row " + i));
        }

        List<Message> rows = handle.displayTranscript();
        assertEquals(200, rows.size());
        assertEquals("u50", rows.getFirst().uuid(), "the oldest rows drop off first");
        assertEquals("u249", rows.getLast().uuid());
    }

    @Test
    void seedDisplayTranscript_addsRowsAndReportsTheChange() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);

        assertTrue(handle.seedDisplayTranscript(List.of(
            userMessage("u1", "hello"), userMessage("u2", "world"))));
        assertEquals(2, handle.displayTranscript().size());

        // An unchanged re-read must report no change: the watcher uses this to skip a repaint,
        // and firing on a no-op read would make the poll as expensive as the disk parse it avoids.
        assertFalse(handle.seedDisplayTranscript(List.of(
            userMessage("u1", "hello"), userMessage("u2", "world"))));
    }

    @Test
    void seedDisplayTranscript_appendsOnlyRowsTheMirrorIsMissing() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);
        handle.appendToDisplayTranscript(userMessage("u1", "live"));

        // The seed carries the same row plus one the live mirror never saw, because the progress
        // callback filters out plain-text content.
        assertTrue(handle.seedDisplayTranscript(List.of(
            userMessage("u1", "live"), userMessage("u2", "plain text answer"))));

        List<Message> rows = handle.displayTranscript();
        assertEquals(List.of("u1", "u2"),
            rows.stream().map(Message::uuid).toList());
    }

    @Test
    void seedDisplayTranscript_neverOverwritesALiveRowWithThePersistedCopy() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_AGENT, "explore repo");
        LocalAgentTask handle = new LocalAgentTask(task, store);
        handle.appendToDisplayTranscript(userMessage("u1", "live text"));

        handle.seedDisplayTranscript(List.of(userMessage("u1", "stale text")));

        List<Message> rows = handle.displayTranscript();
        assertEquals(1, rows.size());
        assertEquals("live text",
            ((UserMessage) rows.getFirst()).message().text());
    }

    private static UserMessage userMessage(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }
}
