package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.UserMessageStyle;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shows that a long-running slash command ({@code /recap}, {@code /compact}, …) is working while
 * its {@code execute} blocks off the GUI thread, and routes the interrupt gesture to it.
 *
 * <p>2.1.236 gets this for free from its submit pipeline: {@code handlePromptSubmit} reserves
 * the {@code QueryGuard} ({@code S.reserve()}) before {@code processUserInput} awaits a local
 * command, so {@code isLoading} — and with it {@code showSpinner} and the Esc-cancel binding —
 * holds for the whole await, and {@code processUserInput} sets {@code userInputOnProcessing}
 * so the typed input is echoed as a placeholder until the command's real transcript rows land.
 * Java runs commands on a virtual thread outside the turn engine, so this presenter supplies the
 * same three signals explicitly.
 *
 * <p>The interrupt gesture ends the loading state at once, whether or not the command can
 * cancel: 236's {@code cancel} proceeds whenever the guard is active, calls
 * {@code guard.forceEnd()} and {@code resetLoadingState()}, and only then aborts the
 * controller. A command that ignores the abort finishes in the background and its result is
 * still rendered; it just no longer holds the prompt.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/handlePromptSubmit.ts} — {@code queryGuard.reserve()} ahead of
 *       {@code processUserInput}: loading state (spinner + Esc cancel) during a local
 *       command's await.</li>
 *   <li>{@code utils/processUserInput/processUserInput.ts} + {@code screens/REPL.tsx} —
 *       {@code setUserInputOnProcessing} and the {@code placeholderText} user row shown until
 *       the real messages arrive.</li>
 *   <li>{@code screens/REPL.tsx} — {@code onCancel}: {@code queryGuard.forceEnd()} +
 *       {@code resetLoadingState()} before {@code abortController.abort("user-cancel")}.</li>
 * </ul>
 */
final class LongRunningCommandPresenter {

    private static final BooleanSupplier NOTHING_TO_CANCEL = () -> false;

    private final SpinnerComponent spinner;
    private final MessagePanel messagePanel;
    private final InputPanel inputPanel;
    private final Consumer<Runnable> onUi;
    private BooleanSupplier cancel = NOTHING_TO_CANCEL;
    /** True when {@link #started} showed the spinner and therefore owns stopping it. */
    private boolean ownsSpinner;
    /** Identifies the command currently holding the loading state; 0 when none does. */
    private long active;
    private long generation;

    LongRunningCommandPresenter(SpinnerComponent spinner, MessagePanel messagePanel,
                                InputPanel inputPanel, Consumer<Runnable> onUi) {
        this.spinner = Objects.requireNonNull(spinner, "spinner");
        this.messagePanel = Objects.requireNonNull(messagePanel, "messagePanel");
        this.inputPanel = Objects.requireNonNull(inputPanel, "inputPanel");
        this.onUi = Objects.requireNonNull(onUi, "onUi");
    }

    /**
     * GUI thread, before the command's virtual thread starts.
     *
     * @return the token the command's completion hands back to {@link #finished}
     */
    synchronized long started(String input, BooleanSupplier cancel) {
        this.cancel = cancel == null ? NOTHING_TO_CANCEL : cancel;
        active = ++generation;
        messagePanel.showTransientRows(UserMessageStyle.promptRows(input));
        inputPanel.setIsLoading(true);
        ownsSpinner = !spinner.isSpinning();
        if (ownsSpinner) spinner.start();
        return active;
    }

    /**
     * GUI thread, after the command's result has been rendered. A result that started a turn
     * (a prompt-type command) hands the spinner and loading state over to that turn.
     *
     * @return {@code false} when the command no longer held the loading state because the
     *         interrupt gesture already released it
     */
    synchronized boolean finished(long token, boolean turnInFlight) {
        if (token != active) return false;
        release(turnInFlight);
        return true;
    }

    /**
     * Interrupt gesture while a command runs: releases the loading state now and asks the
     * command to cancel. Safe from any thread; the UI work is marshalled through {@code onUi}.
     *
     * @return {@code true} when a running command accepted the cancel request and will report
     *         its cancellation shortly; {@code false} when none was running or it cannot cancel
     */
    boolean forceEnd() {
        BooleanSupplier requested;
        boolean stopSpinner;
        synchronized (this) {
            if (active == 0) return false;
            active = 0;
            requested = cancel;
            cancel = NOTHING_TO_CANCEL;
            stopSpinner = ownsSpinner;
            ownsSpinner = false;
        }
        onUi.accept(() -> {
            messagePanel.clearTransientTail();
            inputPanel.setIsLoading(false);
            if (stopSpinner) spinner.stop();
        });
        return requested.getAsBoolean();
    }

    synchronized boolean isActive() {
        return active != 0;
    }

    private void release(boolean turnInFlight) {
        active = 0;
        cancel = NOTHING_TO_CANCEL;
        messagePanel.clearTransientTail();
        boolean stopSpinner = ownsSpinner && !turnInFlight;
        ownsSpinner = false;
        if (turnInFlight) return;
        inputPanel.setIsLoading(false);
        if (stopSpinner) spinner.stop();
    }
}
