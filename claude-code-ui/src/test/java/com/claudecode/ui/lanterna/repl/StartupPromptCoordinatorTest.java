package com.claudecode.ui.lanterna.repl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the interactive startup handshake.
 */
class StartupPromptCoordinatorTest {

    @Test
    void waitsForSetupAndTranscriptThenSubmitsExactlyOnce() {
        List<Runnable> scheduled = new ArrayList<>();
        List<String> submitted = new ArrayList<>();
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
            "inspect this project", scheduled::add, submitted::add);

        coordinator.markSetupReady();
        assertEquals(List.of(), scheduled);

        coordinator.markTranscriptReady();
        assertEquals(1, scheduled.size());
        assertEquals(List.of(), submitted, "submission must run on the UI scheduler");

        scheduled.getFirst().run();
        coordinator.markSetupReady();
        coordinator.markTranscriptReady();

        assertEquals(List.of("inspect this project"), submitted);
        assertEquals(1, scheduled.size(), "the positional prompt is a one-shot startup value");
    }

    @Test
    void readinessOrderDoesNotMatter() {
        List<String> submitted = new ArrayList<>();
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
            "hello", Runnable::run, submitted::add);

        coordinator.markTranscriptReady();
        assertEquals(List.of(), submitted);

        coordinator.markSetupReady();
        assertEquals(List.of("hello"), submitted);
    }

    @Test
    void blankPromptNeverSchedulesSubmission() {
        List<Runnable> scheduled = new ArrayList<>();
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
            "  ", scheduled::add, _ -> { });

        coordinator.markSetupReady();
        coordinator.markTranscriptReady();

        assertEquals(List.of(), scheduled);
    }

    /** A target-less {@code -r} with no argv prompt still has to open the picker. */
    @Test
    void aRequestedPickerOpensEvenWithoutAPrompt() {
        List<Runnable> continuations = new ArrayList<>();
        List<String> submitted = new ArrayList<>();
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
            null, continuations::add, Runnable::run, submitted::add);

        coordinator.markSetupReady();
        coordinator.markTranscriptReady();

        assertEquals(1, continuations.size(), "the picker is the startup action here");
        continuations.getFirst().run();
        assertEquals(List.of(), submitted, "there is no prompt to submit");
    }

    /**
     * {@code claude "review this" -r} must not queue the prompt against the conversation the
     * picker is about to replace, so the prompt runs only through the picker's continuation.
     */
    @Test
    void aPromptWaitingBehindThePickerSubmitsOnlyAfterItSettles() {
        List<Runnable> continuations = new ArrayList<>();
        List<String> submitted = new ArrayList<>();
        StartupPromptCoordinator coordinator = new StartupPromptCoordinator(
            "review this", continuations::add, Runnable::run, submitted::add);

        coordinator.markSetupReady();
        coordinator.markTranscriptReady();
        assertEquals(1, continuations.size());
        assertEquals(List.of(), submitted, "the prompt must wait for the chosen session");

        continuations.getFirst().run();
        assertEquals(List.of("review this"), submitted);

        coordinator.markSetupReady();
        coordinator.markTranscriptReady();
        assertEquals(1, continuations.size(), "the startup action is a one-shot");
    }
}
