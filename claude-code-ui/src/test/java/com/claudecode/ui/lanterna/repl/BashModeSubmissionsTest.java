package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * 236 queues a {@code !} submission while the query guard is active and holds the guard for the
 * whole bash command; see {@link BashModeSubmissions}.
 */
class BashModeSubmissionsTest {

    private final List<String> ran = new ArrayList<>();
    private final List<Runnable> pendingFinish = new ArrayList<>();
    private final List<BooleanSupplier> forceEnded = new ArrayList<>();
    private final List<QueuedCommand> queued = new ArrayList<>();
    private int drains;

    private final BashModeSubmissions submissions = new BashModeSubmissions(
        (command, ended, onFinished) -> {
            ran.add(command);
            forceEnded.add(ended);
            pendingFinish.add(onFinished);
        },
        queued::add,
        () -> drains++);

    @Test
    void idleSessionRunsTheCommandAndHoldsBusyUntilItFinishes() {
        submissions.submit("ls", "ls", Map.of(), false);

        assertEquals(List.of("ls"), ran);
        assertTrue(submissions.isRunning());

        pendingFinish.getFirst().run();
        assertFalse(submissions.isRunning());
        assertEquals(1, drains, "whatever queued behind the command runs next");
    }

    @Test
    void aBusySessionQueuesTheCommandAsABashEntry() {
        submissions.submit("  ls -la ", "  ls -la ", Map.of(), true);

        assertTrue(ran.isEmpty(), "236 enqueues mode:\"bash\" while the guard is active");
        QueuedCommand entry = queued.getFirst();
        assertEquals("bash", entry.mode());
        assertEquals("ls -la", entry.text(), "236 enqueues value: ee.trim()");
        assertTrue(BashModeSubmissions.isBashEntry(entry));
    }

    @Test
    void aSecondCommandWaitsForTheRunningOne() {
        submissions.submit("sleep 5", "sleep 5", Map.of(), false);
        submissions.submit("echo next", "echo next", Map.of(), false);

        assertEquals(List.of("sleep 5"), ran);
        assertEquals("echo next", queued.getFirst().text());
    }

    @Test
    void anIdleCommandRunsAsTyped() {
        submissions.submit(" echo hi ", " echo hi ", Map.of(), false);

        assertEquals(List.of(" echo hi "), ran, "236 hands processBashCommand the untrimmed value");
    }

    @Test
    void theQueuedEntryKeepsPasteChipsAndThePreExpansionText() {
        Map<Integer, PastedContent> pasted =
            Map.of(1, PastedContent.text(1, "a\nb\nc"));
        submissions.submit("cat <<EOF\na\nb\nc\nEOF", "cat <<EOF\n[Pasted text #1 +2 lines]\nEOF",
            pasted, true);

        QueuedCommand entry = queued.getFirst();
        assertEquals(pasted, entry.pastedContents());
        assertEquals("cat <<EOF\n[Pasted text #1 +2 lines]\nEOF", entry.preExpansionValue());
    }

    @Test
    void aDequeuedEntryRunsTheShellCommand() {
        submissions.runQueued("pwd");

        assertEquals(List.of("pwd"), ran);
        assertTrue(submissions.isRunning());
    }

    @Test
    void forceEndReleasesTheSessionBeforeTheShellExits() {
        submissions.submit("sleep 5", "sleep 5", Map.of(), false);

        assertTrue(submissions.forceEnd());

        assertFalse(submissions.isRunning(), "236 cancel() forceEnds the reserved guard");
        assertEquals(1, drains, "the queue drains without waiting for the killed shell");
        assertTrue(forceEnded.getFirst().getAsBoolean());
    }

    @Test
    void aForceEndedRunsLateCompletionLeavesTheNextRunAlone() {
        submissions.submit("sleep 5", "sleep 5", Map.of(), false);
        submissions.forceEnd();
        submissions.runQueued("echo next");

        pendingFinish.getFirst().run();

        assertTrue(submissions.isRunning(), "the dequeued command still holds the session");
        assertEquals(1, drains);
        assertFalse(forceEnded.getLast().getAsBoolean());
    }

    @Test
    void forceEndWithoutARunningCommandDoesNothing() {
        assertFalse(submissions.forceEnd());
        assertEquals(0, drains);
    }

    @Test
    void promptEntriesAreNotBashEntries() {
        assertFalse(BashModeSubmissions.isBashEntry(new QueuedCommand("hi", null)));
    }
}
