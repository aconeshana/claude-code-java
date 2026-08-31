package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.ToolExecutionContext.ProgressSink;
import com.claudecode.core.engine.ToolExecutionContext.ProgressUpdate;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.FakeSandboxBackend;
import com.claudecode.tools.sandbox.NoopSandboxBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LocalShellTaskTest {

    @AfterEach
    void reset() {
        TaskRegistry.resetGlobalForTest();
    }

    /** Polls {@code condition} until true or a 10s deadline, sleeping 20ms between checks. */
    private static void awaitTrue(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                fail("condition did not become true within 10s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for condition");
            }
        }
    }

    @Test
    void fullLifecycle_runningToCompleted_outputReadableByTaskOutputTool(@TempDir Path tmp) throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hello-from-bg");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "echo hello-from-bg", store, output);

        handle.start(tmp.toString());

        awaitTrue(() -> store.get(task.id()).get().status() == TaskStatus.COMPLETED);

        assertTrue(store.get(task.id()).get().endTime().isPresent());
        assertTrue(Files.isRegularFile(output), "output file should exist at " + output);
        String content = Files.readString(output);
        assertTrue(Strings.CS.contains(content, "hello-from-bg"), "expected output content, got: " + content);
    }

    @Test
    void failingCommand_transitionsToFailed(@TempDir Path tmp) {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "exit 7");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "exit 7", store, output);

        assertDoesNotThrow(() -> handle.start(tmp.toString()));

        awaitTrue(() -> store.get(task.id()).get().status() == TaskStatus.FAILED);
    }

    @Test
    void kill_runningTask_transitionsToKilledAndStopsProcess(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "sleep 60");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "sleep 60", store, output);

        handle.start(tmp.toString());
        awaitTrue(handle::isAlive);

        boolean killed = handle.kill();

        assertTrue(killed);
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
        assertTrue(store.get(task.id()).get().notified());
        assertTrue(store.get(task.id()).get().endTime().isPresent());
        awaitTrue(() -> !handle.isAlive());
    }

    @Test
    void kill_runningTaskStopsDescendantsBeforeLateSideEffects(@TempDir Path tmp) throws Exception {
        Assumptions.assumeFalse(Platform.IS_WINDOWS);
        Path started = tmp.resolve("descendant-started.txt");
        Path late = tmp.resolve("descendant-late.txt");
        String child = "trap '' HUP TERM; printf STARTED > " + shellQuote(started.toString())
            + "; sleep 4; printf LATE > " + shellQuote(late.toString());
        String command = "sh -c " + shellQuote(child) + " & wait";

        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, command);
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, command, store, output);

        handle.start(tmp.toString());
        awaitTrue(() -> Files.exists(started));

        assertTrue(handle.kill());
        awaitTrue(() -> !handle.isAlive());
        Thread.sleep(4_500);

        assertFalse(Files.exists(late),
            "a TaskStop-killed descendant must not execute its delayed side effect");
    }

    @Test
    void kill_notRunningTask_isNoOp(@TempDir Path tmp) {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hi");
        // Never started — task stays PENDING.
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "echo hi", store, output);

        boolean killed = handle.kill();

        assertFalse(killed);
        assertEquals(TaskStatus.PENDING, store.get(task.id()).get().status());
    }

    @Test
    void kill_alreadyCompletedTask_isNoOp(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hi");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "echo hi", store, output);

        handle.start(tmp.toString());
        awaitTrue(() -> store.get(task.id()).get().status() == TaskStatus.COMPLETED);

        boolean killed = handle.kill();

        assertFalse(killed);
        assertEquals(TaskStatus.COMPLETED, store.get(task.id()).get().status());
    }

    @Test
    void kill_isIdempotent_secondCallReturnsFalse(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "sleep 60");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "sleep 60", store, output);

        handle.start(tmp.toString());
        awaitTrue(handle::isAlive);

        assertTrue(handle.kill());
        assertFalse(handle.kill(), "second kill() call should be a no-op");
        awaitTrue(() -> !handle.isAlive());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // ── interactive-prompt stall notification ────────────────────────────

    @Test
    void looksLikePrompt_detectsInteractivePrompts() {
        assertTrue(LocalShellTask.looksLikePrompt("Proceed? (y/n)"));
        assertTrue(LocalShellTask.looksLikePrompt("Overwrite?"));
        assertTrue(LocalShellTask.looksLikePrompt("Continue?"));
        assertTrue(LocalShellTask.looksLikePrompt("Press any key to continue"));
        assertTrue(LocalShellTask.looksLikePrompt("Are you sure?"));
        assertFalse(LocalShellTask.looksLikePrompt("building...\ncompiling 123 files"));
        assertFalse(LocalShellTask.looksLikePrompt(""));
        assertFalse(LocalShellTask.looksLikePrompt(null));
    }

    @Test
    void stallNotification_enqueuedOnceWhenOutputLooksLikePrompt(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        MessageQueueManager queue = new MessageQueueManager();
        TaskRegistry.setGlobalForTest(new TaskRegistry(store));
        TaskRegistry.global().setMessageQueue(queue);

        TaskState task = store.create(TaskType.LOCAL_BASH, "rm -rf out");
        Path output = tmp.resolve(task.id() + ".output");
        Files.writeString(output, "Removing build artifacts...\nRemove all? (y/n)\n");
        LocalShellTask handle = new LocalShellTask(task, "rm -rf out", store, output);

        handle.checkAndEmitStallIfBlocked();

        assertEquals(1, queue.size(), "one-shot statusless stall notification");
        QueuedCommand cmd = queue.peek();
        assertEquals(QueuePriority.NEXT, cmd.priority(),
            "stall notification must be NEXT so it surfaces promptly");
        assertEquals("task-notification", cmd.mode());
        String text = cmd.text();
        assertTrue(Strings.CS.contains(text, "<task_notification>"), text);
        assertTrue(Strings.CS.contains(text, "<summary>Background command \"rm -rf out\" "
            + "appears to be waiting for interactive input</summary>"), text);
        assertFalse(Strings.CS.contains(text, "<status>"),
            "stall notification must omit <status> (TS treats missing status as a progress ping)");
        assertTrue(Strings.CS.contains(text, "likely blocked on an interactive prompt"), text);
        assertTrue(Strings.CS.contains(text, "Remove all? (y/n)"), "tail output is appended");

        // Latch: a second check must not enqueue again.
        handle.checkAndEmitStallIfBlocked();
        assertEquals(1, queue.size(), "stall notification latches — emitted only once");
    }

    @Test
    void stallNotification_notEnqueuedForPlainOutput(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        MessageQueueManager queue = new MessageQueueManager();
        TaskRegistry.setGlobalForTest(new TaskRegistry(store));
        TaskRegistry.global().setMessageQueue(queue);

        TaskState task = store.create(TaskType.LOCAL_BASH, "long build");
        Path output = tmp.resolve(task.id() + ".output");
        Files.writeString(output, "compiling 1234 files...\nlinking...\n");
        LocalShellTask handle = new LocalShellTask(task, "long build", store, output);

        handle.checkAndEmitStallIfBlocked();

        assertEquals(0, queue.size(), "plain (non-prompt) output must not trigger a stall notification");
    }

    @Test
    void stallNotification_isNoOpWhenQueueNotWired(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        // No TaskRegistry global queue configured.
        TaskState task = store.create(TaskType.LOCAL_BASH, "wait");
        Path output = tmp.resolve(task.id() + ".output");
        Files.writeString(output, "Continue?\n");
        LocalShellTask handle = new LocalShellTask(task, "wait", store, output);

        assertDoesNotThrow(handle::checkAndEmitStallIfBlocked);
    }

    @Test
    void stallNotification_tagsAgentId_whenTaskOwnedBySubagent(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        MessageQueueManager queue = new MessageQueueManager();
        TaskRegistry.setGlobalForTest(new TaskRegistry(store));
        TaskRegistry.global().setMessageQueue(queue);

        // A background bash started inside a sub-agent carries the sub-agent's id.
        TaskState task = store.create(TaskType.LOCAL_BASH, "rm -rf out", "sub-agent-9");
        Path output = tmp.resolve(task.id() + ".output");
        Files.writeString(output, "Remove all? (y/n)\n");
        LocalShellTask handle = new LocalShellTask(task, "rm -rf out", store, output);

        handle.checkAndEmitStallIfBlocked();

        assertEquals(1, queue.size());
        assertEquals("sub-agent-9", queue.peek().agentId(),
            "stall warning must be routed to the owning sub-agent");
    }

    @Test
    void stallNotification_tagsNullAgentId_forMainThreadTask(@TempDir Path tmp) throws IOException {
        TaskStore store = TaskStore.inMemory();
        MessageQueueManager queue = new MessageQueueManager();
        TaskRegistry.setGlobalForTest(new TaskRegistry(store));
        TaskRegistry.global().setMessageQueue(queue);

        TaskState task = store.create(TaskType.LOCAL_BASH, "rm -rf out");
        Path output = tmp.resolve(task.id() + ".output");
        Files.writeString(output, "Remove all? (y/n)\n");
        LocalShellTask handle = new LocalShellTask(task, "rm -rf out", store, output);

        handle.checkAndEmitStallIfBlocked();

        assertEquals(1, queue.size());
        assertNull(queue.peek().agentId(),
            "main-thread stall warning must stay unrouted");
    }

    // ── sandbox: resolveCommandLine pure method ──────────────────────────────

    @Test
    void resolveCommandLine_unsandboxed_returnsPlainBash() {
        List<String> argv = LocalShellTask.resolveCommandLine("echo hi", Path.of("/work"),
            new NoopSandboxBackend(), SandboxDecision.unsandboxed(), SandboxConfig.disabled());
        assertEquals(List.of(ExecutableFinder.bashExecutable(),
            "-c", "echo hi"), argv);
    }

    @Test
    void resolveCommandLine_sandboxed_usesManagerWrap() {
        List<String> argv = LocalShellTask.resolveCommandLine("echo hi", Path.of("/work"),
            new FakeSandboxBackend(true), SandboxDecision.sandbox(), SandboxConfig.disabled());
        assertEquals(List.of("fake-sandbox", "echo hi"), argv);
    }



    /** Newline count — for output terminated by a trailing '\n' this equals the line count. */
    private static int nl(String s) {
        return (int) s.chars().filter(c -> c == '\n').count();
    }

    @Test
    void backgroundTask_emitsInitialProgressBeforeStartReturns(@TempDir Path tmp) throws Exception {
        BlockingBackgroundReadTaskStore store = new BlockingBackgroundReadTaskStore(tmp, "test");
        TaskState task = store.create(TaskType.LOCAL_BASH, "sleep 5");
        Path output = tmp.resolve(task.id() + ".output");
        List<ProgressUpdate> updates = new CopyOnWriteArrayList<>();
        LocalShellTask handle = new LocalShellTask(task, "sleep 5", store, output,
            new NoopSandboxBackend(), SandboxDecision.unsandboxed(), SandboxConfig.disabled(),
            updates::add);

        try {
            handle.start(tmp.toString());

            assertFalse(updates.isEmpty(),
                "start() should publish the initial live tick synchronously; periodic scheduling follows");
            assertFalse(updates.getFirst().complete());
        } finally {
            store.releaseBackgroundReads();
            handle.kill();
        }
    }

    @Test
    void backgroundTask_emitsProgressWithDistinctOutputAndFullOutput(@TempDir Path tmp) throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "seq 1 200");
        Path output = tmp.resolve(task.id() + ".output");

        // Capture every progress update the task emits.
        List<ProgressUpdate> updates = new CopyOnWriteArrayList<>();
        ProgressSink sink = updates::add;

        LocalShellTask handle = new LocalShellTask(task, "seq 1 200", store, output,
            new NoopSandboxBackend(), SandboxDecision.unsandboxed(), SandboxConfig.disabled(), sink);

        handle.start(tmp.toString());

        // Wait for the task to reach a terminal state (runAndAwait emits the
        // final complete=true tick before transitioning).
        awaitTrue(() -> store.get(task.id())
            .map(t -> t.status().isTerminal())
            .orElse(false));

        // At least one completion tick must have been emitted.
        assertTrue(updates.stream().anyMatch(ProgressUpdate::complete),
            "expected a final complete=true progress update");

        // Every update carries the bash_progress dataType.
        assertTrue(updates.stream().allMatch(u -> Strings.CS.equals("bash_progress", u.dataType())),
            "all progress updates must be bash_progress");

        // Inspect the final (complete) tick: it observes the fully-written file.
        ProgressUpdate finalTick = updates.stream().filter(ProgressUpdate::complete).reduce((_, b) -> b).orElseThrow();
        assertTrue(nl(finalTick.fullOutput()) <= 100,
            "fullOutput must be capped at 100 lines, got " + nl(finalTick.fullOutput()));
        assertTrue(nl(finalTick.output()) <= 5,
            "output must be capped at 5 lines, got " + nl(finalTick.output()));
        assertTrue(finalTick.fullOutput().length() > finalTick.output().length(),
            "fullOutput should carry strictly more content than output");
        assertNotEquals(finalTick.output(), finalTick.fullOutput(),
            "output and fullOutput must be distinct (the pre-alignment bug returned the same tail for both)");
        assertEquals(200, finalTick.totalLines(), "totalLines should reflect the whole 200-line file");

        // At least one live (incomplete) tick was emitted by the 1000ms ticker
        // before completion (its payload may be empty if the command finished
        // within the first tick interval — that is fine; the final tick below
        // is what carries the fully-written output).
        assertTrue(updates.stream().anyMatch(u -> !u.complete()),
            "expected at least one live (incomplete) progress tick");

        // The final tick observes the fully-written file, so its tails are
        // non-empty and correctly bounded/distinct.
        assertFalse(finalTick.fullOutput().isEmpty(),
            "final fullOutput should be non-empty (command produced 200 lines)");
        assertFalse(finalTick.output().isEmpty(),
            "final output should be non-empty");
    }

    @Test
    void backgroundTask_withNoopSink_emitsNothingAndCompletes(@TempDir Path tmp) throws Exception {
        // The simplified constructor defaults to ProgressSink.NOOP — must not NPE
        // and must still reach COMPLETED.
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.LOCAL_BASH, "echo hi");
        Path output = tmp.resolve(task.id() + ".output");
        LocalShellTask handle = new LocalShellTask(task, "echo hi", store, output);

        handle.start(tmp.toString());

        awaitTrue(() -> store.get(task.id())
            .map(t -> t.status().isTerminal())
            .orElse(false));
        assertEquals(TaskStatus.COMPLETED, store.get(task.id()).orElseThrow().status());
    }

    private static final class BlockingBackgroundReadTaskStore extends TaskStore {
        private final Thread owner = Thread.currentThread();
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingBackgroundReadTaskStore(Path baseDir, String taskListId) {
            super(baseDir, taskListId);
        }

        @Override
        public Optional<TaskState> get(String taskId) {
            if (Thread.currentThread() != owner) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.get(taskId);
        }

        void releaseBackgroundReads() {
            release.countDown();
        }
    }
}
