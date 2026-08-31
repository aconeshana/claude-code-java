package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    public SpinnerStateMachine(Consumer<Runnable> uiInvoker, SpinnerComponent spinner) {
        this.uiInvoker = uiInvoker;
        this.spinner = spinner;
    }

    /**
     * Start the spinner for a new turn: set the tip + effort suffix, then start (picks one random verb
     * and keeps it.
     */
    public void startTurn(String tip, String effortSuffix) {

        spinner.beginTurnClock();
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
            }
        } else {
            if (textVisible) {
                textVisible = false;
                if (pendingToolCount.get() > 0 && !spinner.isSpinning()) {
                    uiInvoker.accept(() -> {
                        if (!spinner.isSpinning()) spinner.start(spinner.getCurrentVerb());
                    });
                }
            }
        }
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
