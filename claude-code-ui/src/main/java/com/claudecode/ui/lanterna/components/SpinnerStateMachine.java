package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Translates streaming {@code SDKMessage.StreamEvent}s into {@link SpinnerComponent} state
 * transitions for one query turn: thinking start/stop, tool-use sinusoidal mode, verb changes,
 * response-length accumulation, and "meaningful content" tracking.
 */
public final class SpinnerStateMachine {

    private final Consumer<Runnable> uiInvoker;
    private final SpinnerComponent spinner;

    // Per-turn transient state (was the three boxed locals in executeQuery).
    // gotMeaningfulContent is read from the executor's finally (a different
    // thread than onStreamEvent posts to), hence volatile.
    private volatile boolean gotMeaningfulContent = false;
    private long thinkingStartMs = 0;
    private int  totalResponseChars = 0;
    private final AtomicInteger pendingToolCount = new AtomicInteger();
    private boolean responseHasTools = false;
    /**
     * Mirrors 197's {@code visibleStreamingText}: true while the message area is
     * rendering streamed text. The render layer opens it on the first streamed
     * delta and closes it when a tool stream starts (197 clears {@code
     * streamingText} on every {@code content_block_start}), when the text
     * commits, or at turn reset. While true the spinner yields to the text; it
     * is re-shown only when the text phase ends AND the turn is still mid-work
     * (a tool is executing or more model rounds remain). A pure-text tail never
     * re-shows — the turn completes and stops the spinner itself.
     */
    private volatile boolean textVisible = false;

    /**
     * How long visible streamed text may stop advancing before the spinner is
     * shown again.
     *
     * <p>197 hides the spinner for the whole visible-text phase because its
     * first-party stream advances fast enough to be its own progress indicator.
     * A user-configured endpoint can stream at a couple of tokens per second
     * with multi-second gaps between deltas, and there the same rule leaves the
     * screen with no motion at all — no spinner, no visibly growing text — for
     * minutes while the turn is in fact healthy. Re-showing the spinner once the
     * text stalls restores the indicator without touching the fast path, which
     * never idles this long.
     */
    private static final long TEXT_STALL_MS = 2_000L;
    /** Stall-probe period; a fraction of {@link #TEXT_STALL_MS} to bound detection lag. */
    private static final long TEXT_STALL_POLL_MS = 400L;

    private static final ScheduledExecutorService STALL_PROBE =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "spinner-text-stall");
            thread.setDaemon(true);
            return thread;
        });

    private final AtomicLong lastTextDeltaMs = new AtomicLong();
    private final AtomicReference<ScheduledFuture<?>> stallProbe = new AtomicReference<>();
    /** True while the spinner is visible only because the streamed text stalled. */
    private volatile boolean stallSpinnerShown;
    private final long textStallMs;
    private final long textStallPollMs;

    public SpinnerStateMachine(Consumer<Runnable> uiInvoker, SpinnerComponent spinner) {
        this(uiInvoker, spinner, TEXT_STALL_MS, TEXT_STALL_POLL_MS);
    }

    /** Test seam: shortens the stall window so a test need not sleep two seconds. */
    SpinnerStateMachine(Consumer<Runnable> uiInvoker, SpinnerComponent spinner,
                        long textStallMs, long textStallPollMs) {
        this.uiInvoker = uiInvoker;
        this.spinner = spinner;
        this.textStallMs = textStallMs;
        this.textStallPollMs = textStallPollMs;
    }

    /**
     * Start the spinner for a new turn: set the tip + effort suffix, then start (picks one random verb
     * and keeps it.
     */
    public void startTurn(String tip, String effortSuffix) {

        spinner.beginTurnClock();
        textVisible = false;
        disarmTextStallProbe();
        uiInvoker.accept(() -> {
            spinner.setSpinnerTip(tip);
            spinner.setEffortSuffix(effortSuffix);
            spinner.showTurnSpinner();
        });
    }

    /** True once a tool_use block or streamed text has arrived this turn. */
    public boolean gotMeaningfulContent() {
        return gotMeaningfulContent;
    }

    /**
     * Feed one stream event. Called on the api-query virtual thread; all
     * {@link SpinnerComponent} mutations hop to the UI thread via {@code uiInvoker}.
     */
    public void onStreamEvent(String eventType, String evData) {
        switch (eventType) {
            case "stream_request_start" -> {
                responseHasTools = false;
                pendingToolCount.set(0);
                uiInvoker.accept(() -> {
                    if (!spinner.isSpinning()) {
                        spinner.start(spinner.getCurrentVerb());
                    }
                    spinner.setRequestingMode(true);
                });
            }
            case "tool_streaming_start" -> {
// A tool_use block started streaming.
                gotMeaningfulContent = true;
                responseHasTools = true;
                pendingToolCount.incrementAndGet();
                String toolName = Strings.CS.contains(evData, "|")
                    ? evData.substring(0, evData.indexOf('|')) : evData;
                String toolVerb = SpinnerVerbs.forTool(toolName);
                final long thinkDuration = takeThinkingDuration();
                // Re-show spinner if a previous content_block_delta stopped it.
                uiInvoker.accept(() -> {
                    if (!spinner.isSpinning()) {
                        spinner.start(toolVerb);
                    }
                    spinner.setVerb(toolVerb);

                    spinner.setRequestingMode(false);
                    spinner.setToolUseMode(false);
                    if (thinkDuration > 0) {
                        spinner.setThinkingDuration(thinkDuration);
                        scheduleThinkingClear();
                    }
                });
            }
            case "thinking_delta" -> {

                if (thinkingStartMs == 0) {
                    thinkingStartMs = System.currentTimeMillis();
                    uiInvoker.accept(() -> {
                        // Re-show spinner if a previous content_block_delta stopped
                        // it. Subsequent rounds (multi-step tool use) need the spinner
                        // back when the model resumes thinking.
                        if (!spinner.isSpinning()) {
                            spinner.start(spinner.getCurrentVerb());
                        }
                        spinner.setRequestingMode(false);
                        spinner.setThinking(true);
                    });
                }
            }
            case "message_stop" -> {
                if (responseHasTools) {
                    uiInvoker.accept(() -> {
                        spinner.setRequestingMode(false);
                        spinner.setToolUseMode(true);
                    });
                }
            }
            case "tool_result_success", "tool_result_error" -> {
                boolean toolsRemain = pendingToolCount.updateAndGet(
                    count -> Math.max(0, count - 1)) > 0;
                uiInvoker.accept(() -> {
                    spinner.setToolUseMode(toolsRemain);
                    spinner.setVerb(spinner.getCurrentVerb());
                });
            }
            case "content_block_delta" -> {
                // Text is streaming — meaningful content arrived. Record the length
                // for the token estimate. The visible-streaming-text phase that drives
                // the spinner stop comes from the render layer via
                // onStreamTextVisibility, NOT from raw per-delta events — a delta can
                // never bounce a mid-tool spinner off and on the way the old
                // pendingToolCount==0 guard did across sequential tool rounds.
                gotMeaningfulContent = true;
                totalResponseChars += evData.length();
                final int chars = totalResponseChars;
                noteTextAdvanced();

                final long thinkDuration = takeThinkingDuration();
                uiInvoker.accept(() -> {
                    if (thinkDuration > 0) {
                        spinner.setThinkingDuration(thinkDuration);
                        scheduleThinkingClear();
                    }
                    spinner.setResponseLength(chars);
                });
            }
            case "stop_hook_run_start" -> // Stop hooks running — show spinner suffix while hooks execute.
                uiInvoker.accept(() -> {
                    spinner.setSuffix("running stop hook");
                    if (!spinner.isSpinning()) spinner.start("Running");
                });
            case "stop_hook_run_done" -> // Stop hooks finished — clear suffix; spinner will stop on Result.
                uiInvoker.accept(() -> spinner.setSuffix(""));
            default -> { /* other stream events don't affect the spinner */ }
        }
    }

    /**
     * Drives spinner visibility from the render layer's visible-streaming-text
     * phase: mirrors 197's {@code !visibleStreamingText || isBriefOnly}.
     *
     * <p>When {@code visible} is true a text phase is streaming — the spinner stops
     * and yields to the rendered text. When it ends (a tool stream started, the
     * text committed, or the turn reset), the spinner is re-shown ONLY if a tool
     * is still executing right now ({@code pendingToolCount > 0}); a pure-text
     * tail is left hidden and the turn-complete path stops it. Restoration for
     * the "more model rounds" case is handled by the {@code tool_streaming_start}
     * / {@code thinking_delta} / {@code stream_request_start} event handlers,
     * which already re-start a stopped spinner.
     */
    public void onStreamTextVisibility(boolean visible) {
        if (visible) {
            if (!textVisible) {
                textVisible = true;
                uiInvoker.accept(spinner::stop);
                armTextStallProbe();
            }
        } else {
            if (textVisible) {
                textVisible = false;
                disarmTextStallProbe();
                if (pendingToolCount.get() > 0 && !spinner.isSpinning()) {
                    uiInvoker.accept(() -> {
                        if (!spinner.isSpinning()) spinner.start(spinner.getCurrentVerb());
                    });
                }
            }
        }
    }

    /**
     * Records that streamed text just advanced. While the spinner is only up
     * because the text had stalled, resuming text hands the screen back to it —
     * the same yield {@link #onStreamTextVisibility} performs when the phase opens.
     */
    private void noteTextAdvanced() {
        lastTextDeltaMs.set(System.currentTimeMillis());
        if (stallSpinnerShown) {
            stallSpinnerShown = false;
            uiInvoker.accept(spinner::stop);
        }
    }

    /**
     * Polls for a stalled visible-text phase. The probe cancels itself once the
     * phase closes, so a turn that ends without {@code onStreamTextVisibility(false)}
     * cannot leave it running against the next turn's spinner.
     */
    private void armTextStallProbe() {
        lastTextDeltaMs.set(System.currentTimeMillis());
        stallSpinnerShown = false;
        ScheduledFuture<?> probe = STALL_PROBE.scheduleWithFixedDelay(this::checkTextStall,
            textStallPollMs, textStallPollMs, TimeUnit.MILLISECONDS);
        cancel(stallProbe.getAndSet(probe));
    }

    private void disarmTextStallProbe() {
        cancel(stallProbe.getAndSet(null));
        stallSpinnerShown = false;
    }

    private void checkTextStall() {
        if (!textVisible || !spinner.isTurnClockActive()) {
            disarmTextStallProbe();
            return;
        }
        if (stallSpinnerShown) return;
        if (System.currentTimeMillis() - lastTextDeltaMs.get() < textStallMs) return;
        stallSpinnerShown = true;
        uiInvoker.accept(() -> {
            // Re-check on the UI thread: a delta may have landed while this task
            // was queued, in which case noteTextAdvanced already cleared the flag.
            if (!stallSpinnerShown || !textVisible) return;
            if (!spinner.isTurnClockActive()) return;
            if (!spinner.isSpinning()) spinner.start(spinner.getCurrentVerb());
        });
    }

    private static void cancel(ScheduledFuture<?> probe) {
        if (probe != null) probe.cancel(false);
    }

    /** Consume the pending thinking window, returning its elapsed ms (0 if none). */
    private long takeThinkingDuration() {
        long thinkMs = thinkingStartMs;
        thinkingStartMs = 0;
        return thinkMs > 0 ? System.currentTimeMillis() - thinkMs : 0;
    }

/**
     * Clear the thinking indicator 2s after a thinking→work transition.
     */
    private void scheduleThinkingClear() {
        CompletableFuture
            .delayedExecutor(2, TimeUnit.SECONDS)
            .execute(() -> uiInvoker.accept(() -> spinner.setThinking(false)));
    }
}
