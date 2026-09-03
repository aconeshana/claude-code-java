package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolExecutionContext;

/**
 * Show/teardown pairing for the "this call can be backgrounded" affordance.
 *
 * <p>Upstream drives this with {@code setToolJSX({jsx:<BackgroundHint/>, …})} on a timer and
 * {@code setToolJSX(null)} in a {@code finally}, so success, failure, interrupt, and
 * "user already backgrounded it" all tear the affordance down through one statement. The Java
 * port routes both halves through the progress sink instead: {@code show()} emits
 * {@link ToolExecutionContext.ProgressUpdate#agentBackgroundHint()} and {@code clear()} emits a
 * {@code complete(true)} update, which the presentation layer already treats as "drop this
 * tool's progress affordances".
 *
 * <p>{@code show()} and {@link #disarm()} share one monitor. Cancelling the scheduled task is
 * not enough on its own: {@code ScheduledFuture.cancel(false)} cannot stop a callback that has
 * already entered {@code run()}, so without the mutual exclusion a hint could still be emitted
 * after teardown and stick around forever. Upstream gets that atomicity free from a
 * single-threaded event loop.
 *
 * <p>{@code clear()} stays silent unless a hint was actually shown, so a short-lived call never
 * clears a concurrently running tool's progress affordances.
 *
 * <ul>
 *   <li>{@code src/tools/BashTool/UI.tsx} — the {@code BackgroundHint} component this drives;
 *       the wording and indentation live in the renderer, never on the event.</li>
 *   <li>{@code src/tools/BashTool/BashTool.tsx} — the elapsed-threshold {@code setToolJSX}
 *       site and its {@code finally { setToolJSX(null) }} teardown.</li>
 *   <li>{@code src/tools/AgentTool/AgentTool.tsx} — the same pairing around a foreground
 *       sub-agent run.</li>
 * </ul>
 */
public final class BackgroundHint {

    private enum State { ARMED, SHOWN, CLEARED }

    private final ToolExecutionContext context;
    private State state = State.ARMED;

    public BackgroundHint(ToolExecutionContext context) {
        this.context = context;
    }

    /** Emits the affordance once. A no-op after {@link #disarm()} or a previous {@code show()}. */
    public synchronized void show() {
        if (state != State.ARMED) {
            return;
        }
        state = State.SHOWN;
        context.reportProgress(ToolExecutionContext.ProgressUpdate.agentBackgroundHint());
    }

    /**
     * Closes the window on any further {@link #show()} and reports whether an affordance was
     * left visible. Callers that already emit their own terminal progress update use this
     * directly; everyone else wants {@link #clear()}.
     */
    public synchronized boolean disarm() {
        boolean visible = state == State.SHOWN;
        state = State.CLEARED;
        return visible;
    }

    /** Disarms and, if an affordance was visible, tells the presentation layer to drop it. */
    public void clear() {
        if (disarm()) {
            context.reportProgress(
                ToolExecutionContext.ProgressUpdate.builder().complete(true).build());
        }
    }
}
