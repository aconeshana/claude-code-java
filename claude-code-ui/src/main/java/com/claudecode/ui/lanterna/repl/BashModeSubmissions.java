package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import org.apache.commons.lang3.Strings;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Routes {@code !} bash-mode submissions through the REPL's busy state.
 *
 * <p>2.1.236 queues a bash-mode submission like a prompt whenever the query guard is active
 * ({@code if(A.isActive||R){if(I!=="prompt"&&I!=="bash")return; … enqueue}}), and runs
 * {@code processBashCommand} after {@code queryGuard.reserve()}, so the guard stays active for
 * the whole command and anything submitted meanwhile queues behind it. This class supplies both
 * halves: queue while busy, and be busy while running. Esc is 236's {@code cancel()}, which
 * {@code forceEnd}s the guard at once, so the queue drains while the killed shell is still
 * winding down; the late result then learns it was force-ended.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/handlePromptSubmit.ts} — the busy branch that enqueues {@code mode:"bash"}
 *       submissions, and {@code queryGuard.reserve()} ahead of the bash command's await.</li>
 *   <li>{@code utils/processUserInput/processUserInput.ts} — a dequeued {@code bash} entry runs
 *       the shell command locally instead of becoming a model turn.</li>
 *   <li>{@code screens/REPL.tsx} {@code cancel} — {@code queryGuard.forceEnd()} releases the
 *       reserved guard without waiting for the aborted command (authoritative logic from the
 *       2.1.236 bundle).</li>
 * </ul>
 */
final class BashModeSubmissions {

    /** Runs one shell command; {@code onFinished} fires once on the GUI thread afterwards. */
    @FunctionalInterface
    interface Runner {
        /**
         * @param forceEnded whether {@link #forceEnd} released this run before it finished
         */
        void run(String command, BooleanSupplier forceEnded, Runnable onFinished);
    }

    /** One run; identity tells a force-ended run's late completion from the current one. */
    private static final class Run {
        private volatile boolean forceEnded;
    }

    private final Runner runner;
    private final Consumer<QueuedCommand> enqueue;
    private final Runnable drainQueue;
    private Run current;

    BashModeSubmissions(Runner runner, Consumer<QueuedCommand> enqueue, Runnable drainQueue) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.enqueue = Objects.requireNonNull(enqueue, "enqueue");
        this.drainQueue = Objects.requireNonNull(drainQueue, "drainQueue");
    }

    /**
     * A typed {@code !command} (prefix already stripped). Only the queued copy is trimmed —
     * 236 enqueues {@code value: ee.trim()} but hands the idle path's value to
     * {@code processBashCommand} as typed.
     *
     * @param preExpansion  the command as typed, before pasted-text expansion
     * @param pasted        paste chips kept on the queue entry, as 236's submission carries them
     * @param otherwiseBusy a turn or a long-running local command is active
     */
    void submit(String command, String preExpansion, Map<Integer, PastedContent> pasted,
                boolean otherwiseBusy) {
        if (otherwiseBusy || isRunning()) {
            enqueue.accept(queueEntry(command, preExpansion, pasted));
            return;
        }
        run(command);
    }

    /** A {@code bash} entry taken off the queue once the session went idle. */
    void runQueued(String command) {
        run(command);
    }

    /** Whether a bash-mode command is executing, which holds the session busy. */
    synchronized boolean isRunning() {
        return current != null;
    }

    /**
     * Release the busy state of the running command now, as 236's {@code guard.forceEnd()};
     * its eventual completion no longer drains the queue.
     *
     * @return whether a command was running
     */
    boolean forceEnd() {
        Run run;
        synchronized (this) {
            run = current;
            if (run == null) return false;
            run.forceEnded = true;
        }
        release(run);
        return true;
    }

    static boolean isBashEntry(QueuedCommand command) {
        return command != null && Strings.CS.equals("bash", command.mode());
    }

    static QueuedCommand queueEntry(String command, String preExpansion,
                                    Map<Integer, PastedContent> pasted) {
        String trimmed = command.strip();
        String preview = preExpansion == null ? null : preExpansion.strip();
        return new QueuedCommand(trimmed, pasted, "bash", QueuePriority.NEXT,
            false, null, false, false, Strings.CS.equals(trimmed, preview) ? null : preview,
            null, null);
    }

    private void run(String command) {
        Run run = new Run();
        synchronized (this) {
            current = run;
        }
        runner.run(command, () -> run.forceEnded, () -> release(run));
    }

    private void release(Run run) {
        synchronized (this) {
            if (current != run) return;
            current = null;
        }
        drainQueue.run();
    }
}
