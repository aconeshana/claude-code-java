package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.ui.lanterna.input.InputPanel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interrupt-gesture coverage that {@link ReplExitControllerTest} cannot reach:
 * that test fakes these actions out, so it never sees whether an abort is
 * actually issued.
 */
class ReplInterruptActionsTest {

    /** Records what the gestures did to the query engine. */
    private static final class FakeTarget implements ReplInterruptActions.TurnAbortTarget {
        int interrupts;
        int softInterrupts;

        @Override public void interrupt() { interrupts++; }
        @Override public void softInterrupt() { softInterrupts++; }
        @Override public String sessionId() { return "session-1"; }
        @Override public TranscriptSink transcriptSink() { return null; }
    }

    private static ReplInterruptActions actions(boolean turnInFlight, FakeTarget target) {
        return new ReplInterruptActions(
            () -> null,                 // no bash command running
            () -> turnInFlight,
            target,
            null,                       // no interaction coordinator
            () -> null,                 // no input panel
            Runnable::run,
            () -> null,
            () -> false);
    }

    @Test
    void inFlightTurnIsInterruptedWithoutConsultingTheSpinner() {
        // Regression guard. Assistant text streaming stops the spinner
        // (SpinnerStateMachine.onStreamTextVisibility), and gating the abort on
        // a visible spinner made Ctrl+C and ESC no-ops for that whole stretch.
        // Official 2.1.236 aborts whenever an un-aborted request is in flight,
        // so the gesture deliberately takes no spinner input at all — a test
        // that could pass a stopped spinner here would be testing the bug back
        // in.
        FakeTarget target = new FakeTarget();

        assertTrue(actions(true, target).interruptTurnIfRunning(),
            "a streaming turn aborts even though its spinner is stopped");
        assertEquals(1, target.interrupts);
    }

    @Test
    void idleSessionReportsNoTurnToInterrupt() {
        FakeTarget target = new FakeTarget();

        assertFalse(actions(false, target).interruptTurnIfRunning(),
            "with nothing in flight Ctrl+C must fall through to clear-input / exit");
        assertEquals(0, target.interrupts);
    }

    @Test
    void softInterruptSkipsTheInFlightFlagEntirely() {
        // Teardown races: the query iterator can still sit in HTTP after the UI
        // has cleared its busy state, so the soft path must not consult it.
        FakeTarget target = new FakeTarget();

        actions(false, target).softInterruptTurnIfRunning();

        assertEquals(1, target.softInterrupts);
        assertEquals(0, target.interrupts, "soft interrupt is not the hard abort");
    }

    @Test
    void clearInputIfPresentEmptiesADraftWithoutInterrupting() {
        FakeTarget target = new FakeTarget();
        InputPanel panel = new InputPanel();
        panel.setText("draft");
        ReplInterruptActions actions = new ReplInterruptActions(
            () -> null, () -> true, target, null, () -> panel, Runnable::run,
            () -> null, () -> false);

        assertTrue(actions.clearInputIfPresent(), "a draft is present");
        assertEquals("", panel.getText());
        assertEquals(0, target.interrupts, "clearing input is not an abort");
    }
}
