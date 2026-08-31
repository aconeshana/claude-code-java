package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpinnerStateMachine} — drives it with a synchronous
 * {@code uiInvoker} (so {@link SpinnerComponent} mutations happen inline) and
 * asserts the observable contract: "meaningful content" tracking (what the
 * executor's auto-restore depends on), verb switching, and spinner start/stop.
 */
class SpinnerStateMachineTest {

    /** Runs UI work inline on the calling thread — no Lanterna GUI needed. */
    private static final Consumer<Runnable> SYNC = Runnable::run;

    private final SpinnerComponent spinner = new SpinnerComponent();

    private SpinnerStateMachine newMachine() {
        return new SpinnerStateMachine(SYNC, spinner);
    }

    @AfterEach
    void stopSpinner() {
        spinner.stop(); // cancel any animation scheduled by start
    }

    @Test
    void gotMeaningfulContent_startsFalse() {
        assertFalse(newMachine().gotMeaningfulContent());
    }

    @Test
    void thinkingAndStopHooks_areNotMeaningfulContent() {
        SpinnerStateMachine m = newMachine();
        m.onStreamEvent("thinking_delta", "");
        m.onStreamEvent("stop_hook_run_start", "");
        m.onStreamEvent("stop_hook_run_done", "");
        assertFalse(m.gotMeaningfulContent(),
            "thinking / stop-hook events must not count as meaningful content");
    }

    @Test
    void toolStreamingStart_entersToolInputAndClearsThinkingPresentation() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("thinking_delta", "thinking");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-1|msg-1");

        assertTrue(m.gotMeaningfulContent());
        assertEquals(SpinnerVerbs.forTool("Agent"), spinner.getCurrentVerb());
        assertFalse(spinner.isToolUseMode(),
            "content_block_start(tool_use) is 197's tool-input mode, not tool-use yet");
        assertFalse(spinner.isRequestingMode());
    }

    @Test
    void messageStop_entersToolUseAfterToolBlock() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-1|msg-1");

        m.onStreamEvent("message_stop", "");

        assertTrue(spinner.isToolUseMode(),
            "197 switches to tool-use while foreground tools/subagents execute");
        assertFalse(spinner.isRequestingMode());
    }

    @Test
    void streamRequestStart_entersRequestingForNextModelRound() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-1|msg-1");
        m.onStreamEvent("message_stop", "");

        m.onStreamEvent("stream_request_start", "");

        assertTrue(spinner.isRequestingMode());
        assertFalse(spinner.isToolUseMode());
    }

    @Test
    void thinkingDelta_leavesRequestingMode() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("stream_request_start", "");

        m.onStreamEvent("thinking_delta", "thinking");

        assertFalse(spinner.isRequestingMode());
    }

    @Test
    void parallelTools_keepToolUseModeUntilEveryResultArrives() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-1|msg-1");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-2|msg-1");
        m.onStreamEvent("tool_streaming_start", "Agent|tool-3|msg-1");
        m.onStreamEvent("message_stop", "");

        m.onStreamEvent("tool_result_success", "tool-1");
        m.onStreamEvent("tool_result_error", "tool-2");
        assertTrue(spinner.isToolUseMode(),
            "one completed subagent must not end the parent tool-use state while siblings run");

        m.onStreamEvent("tool_result_success", "tool-3");
        assertFalse(spinner.isToolUseMode());
    }

    @Test
    void contentBlockDelta_marksMeaningful_butOnlyWindowClosesSpinner() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        assertTrue(spinner.isSpinning(), "startTurn shows the spinner");

        // A raw delta alone records content and length; it must NOT hide the spinner —
        // visibility is driven by the render-layer window via onStreamTextVisibility.
        m.onStreamEvent("content_block_delta", "hello");
        assertTrue(m.gotMeaningfulContent());
        assertTrue(spinner.isSpinning(),
            "the per-delta event must not drive spinner visibility");

        // Opening the visible-streaming-text window (the dispatcher's streamedThisTurn)
        // is what yields the spinner to the rendered text.
        m.onStreamTextVisibility(true);
        assertFalse(spinner.isSpinning(), "streamed text window stops the spinner");
    }

    @Test
    void legacyToolCallStart_doesNotControlSpinnerLifecycle() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        String originalVerb = spinner.getCurrentVerb();
        m.onStreamEvent("tool_call_start", "Bash|{\"command\":\"ls\"}");
        assertEquals(originalVerb, spinner.getCurrentVerb(),
            "production lifecycle is driven by provider-neutral streaming events");
    }

    @Test
    void toolStreamingStart_reshowsSpinnerAfterTextWindowClosesMidTurn() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamTextVisibility(true); // text window open — spinner yields
        assertFalse(spinner.isSpinning());

        // Multi-step turn: a tool starts while text was visible — spinner comes back
        // (tool_streaming_start re-shows it regardless of window state).
        m.onStreamEvent("tool_streaming_start", "Read");
        assertTrue(spinner.isSpinning());
    }

    @Test
    void toolStreamingStart_reshowsSpinner_EvenBeforeWindowClose() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamTextVisibility(true);
        assertFalse(spinner.isSpinning());

        // tool_streaming_start re-shows immediately; the later window-close must not
        // double-stop it (window close with no tools left leaves it hidden).
        m.onStreamEvent("tool_streaming_start", "Read");
        assertTrue(spinner.isSpinning());

        // Still no tool finished — pendingToolCount > 0, so closing the window keeps it.
        m.onStreamTextVisibility(false);
        assertTrue(spinner.isSpinning(),
            "window close with an active tool must keep the spinner");
    }

    @Test
    void reshowingSpinnerDoesNotRestartTheTurnClock() throws Exception {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        long turnStart = spinner.turnStartMillis();

        m.onStreamTextVisibility(true); // text window open
        assertFalse(spinner.isSpinning());
        Thread.sleep(25);

        m.onStreamEvent("stream_request_start", "");

        assertTrue(spinner.isSpinning());
        assertEquals(turnStart, spinner.turnStartMillis(),
            "a later model/tool phase may re-show the animation, but the completion "
                + "duration must remain anchored to the original user submission");
    }

    @Test
    void turnClockStartsSynchronouslyBeforeQueuedPresentationRuns() {
        List<Runnable> queuedUi = new ArrayList<>();
        SpinnerStateMachine machine = new SpinnerStateMachine(queuedUi::add, spinner);
        long beforeSubmit = System.currentTimeMillis();

        machine.startTurn("tip", "");

        assertTrue(spinner.turnStartMillis() >= beforeSubmit,
            "the authoritative turn clock must be anchored at submission, not when the GUI "
                + "event queue later paints the spinner");
        assertFalse(spinner.isVisible(),
            "only the visual spinner presentation should remain queued");
        assertEquals(1, queuedUi.size());
    }

    @Test
    void permissionPauseAccountingSurvivesSpinnerHideAndReshow() throws Exception {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        Thread.sleep(100);

        spinner.pauseTimer();
        Thread.sleep(150);
        m.onStreamTextVisibility(true); // text window open hides the spinner
        m.onStreamEvent("stream_request_start", "");
        Thread.sleep(40);
        spinner.resumeTimer();
        Thread.sleep(20);

        long activeElapsed = spinner.adjustedElapsedMsForTranscript();
        assertTrue(activeElapsed >= 90,
            "active time before the spinner was hidden must not be discarded: " + activeElapsed);
        assertTrue(activeElapsed < 260,
            "time spent in the permission dialog must remain excluded: " + activeElapsed);
    }

    @Test
    void streamingTextWindow_keepsSpinnerHiddenThenToolRestoresIt() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("tool_streaming_start", "Bash|tool-1|msg-1");
        m.onStreamEvent("message_stop", ""); // bash now executing — toolUseMode on
        assertTrue(spinner.isToolUseMode());

        // A parallel/second text block renders while bash runs: the window opens and the
        // spinner yields to the text (197: visibleStreamingText beats the spinner).
        m.onStreamTextVisibility(true);
        assertFalse(spinner.isSpinning(),
            "visible streamed text yields the spinner even during tool use");
        assertEquals(SpinnerVerbs.forTool("Bash"), spinner.getCurrentVerb());

        // The tool is still executing, and the text window closes (block committed) —
        // B2 restores the spinner only because a tool is still in flight.
        m.onStreamTextVisibility(false);
        assertTrue(spinner.isSpinning(),
            "window close with an in-flight tool must restore the spinner");
        assertEquals(SpinnerVerbs.forTool("Bash"), spinner.getCurrentVerb());
    }

    @Test
    void streamingTextWindowCloseWithoutTool_leavesSpinnerHidden() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamTextVisibility(true);
        assertFalse(spinner.isSpinning());

        // Pure-text tail: the window closes with no tool running and no more model
        // rounds — the spinner must NOT come back (the turn completes and stops it).
        m.onStreamTextVisibility(false);
        assertFalse(spinner.isSpinning(),
            "a pure-text tail never re-shows the spinner when the window closes");
    }

    @Test
    void sequentialToolRounds_withTextBetween_neverFlicker() {
        // The regression this fix is built against: across sequential tool rounds the
        // message area streams text in the gap, and the old per-delta pendingToolCount==0
        // guard stopped→restarted the spinner on every gap. With B2 the window's close
        // restores the spinner only while tools still run; the restart is a single
        // transition, not a stop→start bounce.
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("tool_streaming_start", "Bash|tool-1|msg-1");
        m.onStreamEvent("message_stop", "");

        // Tool A result arrives, then the model streams text (window opens) while
        // tool B is queued/starting.
        m.onStreamEvent("tool_result_success", "tool-1");
        m.onStreamTextVisibility(true); // text streams in the gap
        assertFalse(spinner.isSpinning(), "text yields the spinner");

        // Tool B begins — re-shown once.
        m.onStreamEvent("tool_streaming_start", "Read|tool-2|msg-2");
        assertTrue(spinner.isSpinning());
        m.onStreamTextVisibility(false); // text window closes
        assertTrue(spinner.isSpinning(),
            "B2: closing the text window with tool-2 still in flight must NOT restart-stop-restart");
    }

    @Test
    void unknownEvent_isIgnored() {
        SpinnerStateMachine m = newMachine();
        m.startTurn("tip", "");
        m.onStreamEvent("some_unrelated_event", "payload");
        // No state change, no exception.
        assertFalse(m.gotMeaningfulContent());
        assertTrue(spinner.isSpinning());
    }
}
