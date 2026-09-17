package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.ui.lanterna.bashmode.BashModeExecutor;
import com.claudecode.ui.lanterna.input.InputPanel;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The REPL's interrupt gestures, extracted from {@code LanternaReplScreen} so the
 * abort rule is testable without building a screen.
 *
 * <p>Dependencies arrive as suppliers because the screen assigns several of them
 * after construction, and as narrow functional types so a test can supply them
 * directly.
 */
final class ReplInterruptActions implements ReplExitController.InterruptActions {

    /**
     * The query-engine slice the interrupt gestures touch. Narrow on purpose:
     * {@code QuerySession} drags in five sub-graphs, which makes it impractical to
     * fake, and this is the surface the abort rule has to be testable over.
     */
    interface TurnAbortTarget {
        void interrupt();
        void softInterrupt();
        String sessionId();
        TranscriptSink transcriptSink();
    }

    private final Supplier<BashModeExecutor> bashModeExecutor;
    private final BooleanSupplier hasActiveTurn;
    private final TurnAbortTarget abortTarget;
    private final InteractionCoordinator interactionCoordinator;
    private final Supplier<InputPanel> inputPanel;
    private final Consumer<Runnable> onUi;
    private final Supplier<String> lastSubmittedInput;
    private final BooleanSupplier lastSubmittedInputWasStartupPrompt;

    ReplInterruptActions(
            Supplier<BashModeExecutor> bashModeExecutor,
            BooleanSupplier hasActiveTurn,
            TurnAbortTarget abortTarget,
            InteractionCoordinator interactionCoordinator,
            Supplier<InputPanel> inputPanel,
            Consumer<Runnable> onUi,
            Supplier<String> lastSubmittedInput,
            BooleanSupplier lastSubmittedInputWasStartupPrompt) {
        this.bashModeExecutor = bashModeExecutor;
        this.hasActiveTurn = hasActiveTurn;
        this.abortTarget = abortTarget;
        this.interactionCoordinator = interactionCoordinator;
        this.inputPanel = inputPanel;
        this.onUi = onUi;
        this.lastSubmittedInput = lastSubmittedInput;
        this.lastSubmittedInputWasStartupPrompt = lastSubmittedInputWasStartupPrompt;
    }

    @Override
    public boolean interruptBashIfRunning() {
        BashModeExecutor bash = bashModeExecutor.get();
        if (bash == null || !bash.isRunning()) return false;
        bash.interrupt();
        return true;
    }

    @Override
    public boolean interruptTurnIfRunning() {
        // Official 2.1.236 aborts whenever an un-aborted request is in flight and never asks
        // whether the spinner is showing; requiring a visible spinner here made Ctrl+C fall
        // through to clear-input / "press again to exit" for the whole stretch where assistant
        // text is streaming, because streaming text stops the spinner
        // (SpinnerStateMachine.onStreamTextVisibility). hasActiveTurn narrows "in flight" to
        // exclude the post-turn idle tail (deferred rewind/compact) instead, where interrupt()
        // is a no-op abort signal with nothing left to catch it — that used to swallow Ctrl+C
        // for the whole tail, leaving it completely unresponsive.
        if (!hasActiveTurn.getAsBoolean()) return false;
        abortTarget.interrupt();
        if (interactionCoordinator != null) {
            interactionCoordinator.cancelSession(abortTarget.sessionId());
        }
        return true;
    }

    @Override
    public void softInterruptTurnIfRunning() {
        String lastInput = lastSubmittedInput.get();
        TranscriptSink sink = abortTarget.transcriptSink();
        if (InterruptedPromptPolicy.shouldCacheSoftInterruptedPrompt(
                lastInput, lastSubmittedInputWasStartupPrompt.getAsBoolean())
                && sink != null) {
            sink.cacheLastPrompt(abortTarget.sessionId(), lastInput);
        }
        // Do not depend on the UI in-flight flag here. During the terminal
        // teardown race the query iterator can still be blocked in HTTP while
        // the UI has already cleared its visible busy state.
        abortTarget.softInterrupt();
    }

    @Override
    public boolean clearInputIfPresent() {
        InputPanel panel = inputPanel.get();
        if (panel == null || panel.getText() == null || panel.getText().isEmpty()) {
            return false;
        }
        onUi.accept(() -> {
            panel.setText("");
            panel.resetHistory();
        });
        return true;
    }

    @Override
    public void showExitHint(String text, int durationMs) {
        InputPanel panel = inputPanel.get();
        if (panel != null) {
            onUi.accept(() -> panel.showTransientHint(text, durationMs));
        }
    }
}
