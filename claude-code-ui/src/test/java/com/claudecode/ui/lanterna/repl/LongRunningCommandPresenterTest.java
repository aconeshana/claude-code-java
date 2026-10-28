package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 236's loading state during a local command's await: spinner, placeholder, force-end. */
class LongRunningCommandPresenterTest {

    private final SpinnerComponent spinner = new SpinnerComponent();
    private final MessagePanel messagePanel = new MessagePanel();
    private final LongRunningCommandPresenter presenter =
        new LongRunningCommandPresenter(spinner, messagePanel, new InputPanel(), Runnable::run);

    @AfterEach
    void stopSpinner() {
        spinner.stop();
    }

    @Test
    void spinnerRunsForTheWholeCommandAndStopsAfterIt() {
        long token = presenter.started("/recap", () -> true);
        assertTrue(spinner.isSpinning(), "the command is visibly working, not hung");

        assertTrue(presenter.finished(token, false));
        assertFalse(spinner.isSpinning());
    }

    @Test
    void aPromptResultHandsTheSpinnerToTheTurnItStarted() {
        long token = presenter.started("/recap", () -> true);

        presenter.finished(token, true);
        assertTrue(spinner.isSpinning(), "the started turn owns the spinner now");
    }

    @Test
    void aSpinnerAlreadyRunningIsLeftToItsOwner() {
        spinner.start("Compacting");
        long token = presenter.started("/compact", () -> false);

        presenter.finished(token, false);
        assertTrue(spinner.isSpinning());
    }

    @Test
    void forceEndReleasesTheLoadingStateEvenWhenTheCommandCannotCancel() {
        int[] cancels = {0};
        long token = presenter.started("/rename", () -> ++cancels[0] < 0);

        assertFalse(presenter.forceEnd(), "the command could not cancel");
        assertFalse(spinner.isSpinning(), "236's cancel proceeds whenever the guard is active");
        assertFalse(presenter.isActive());
        assertEquals(1, cancels[0], "the command is still asked to cancel");

        assertFalse(presenter.finished(token, false),
            "the late completion no longer holds the loading state");
        assertFalse(presenter.forceEnd(), "nothing is left to end");
    }

    @Test
    void forceEndReportsAnAcceptedCancel() {
        presenter.started("/recap", () -> true);

        assertTrue(presenter.forceEnd());
        assertFalse(presenter.isActive());
    }

    @Test
    void aStaleCompletionDoesNotReleaseANewerCommand() {
        long first = presenter.started("/recap", () -> true);
        presenter.forceEnd();
        presenter.started("/compact", () -> true);

        assertFalse(presenter.finished(first, false));
        assertTrue(presenter.isActive());
        assertTrue(spinner.isSpinning());
    }

    @Test
    void placeholderIsRemovedWhenTheCommandFinishes() {
        int before = messagePanel.snapshotLineCount();
        long token = presenter.started("/recap", () -> false);
        presenter.finished(token, false);
        assertEquals(before, messagePanel.snapshotLineCount(),
            "the placeholder never became a transcript line");
    }
}
