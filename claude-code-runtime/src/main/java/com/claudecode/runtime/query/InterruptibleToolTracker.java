package com.claudecode.runtime.query;

import com.claudecode.core.queue.InterruptBehavior;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mid-turn steer flag over the set of currently executing tools — the runtime twin
 * of {@code StreamingToolExecutor.updateInterruptibleState}. The flag is true only
 * while at least one tool executes AND every executing tool reports
 * {@link InterruptBehavior#CANCEL}; any executing {@code BLOCK} tool keeps it down,
 * so a mid-turn user submission only queues instead of aborting the turn.
 *
 * <p>Transitions are driven from the tool runner's started/completed points; reads
 * race with them by design (a last-moment BLOCK completion only defers the steer
 * to the ordinary end-of-turn drain, which is the safe direction).
 *
 * <ul>
 *   <li>{@code services/tools/StreamingToolExecutor.ts} — {@code updateInterruptibleState}
 *       recompute on every started/completed transition, and the {@code executing.length > 0
 *       && executing.every(t => getToolInterruptBehavior(t) === 'cancel')} formula.</li>
 *   <li>{@code screens/REPL.tsx} — {@code setHasInterruptibleToolInProgress} stores the
 *       flag in a ref read at submit time.</li>
 * </ul>
 */
final class InterruptibleToolTracker {

    private final AtomicInteger executing = new AtomicInteger();
    private final AtomicInteger executingBlockers = new AtomicInteger();

    /** A tool began executing with the given interrupt behavior. */
    void onToolStarted(InterruptBehavior behavior) {
        executing.incrementAndGet();
        if (behavior == InterruptBehavior.BLOCK) executingBlockers.incrementAndGet();
    }

    /** A tool finished executing (any outcome) with the given interrupt behavior. */
    void onToolFinished(InterruptBehavior behavior) {
        executing.decrementAndGet();
        if (behavior == InterruptBehavior.BLOCK) executingBlockers.decrementAndGet();
    }

    /** True while only cancellable tools are executing (and at least one is). */
    boolean hasInterruptibleToolInProgress() {
        return executing.get() > 0 && executingBlockers.get() == 0;
    }
}
