package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the gate ESC salvage consults before appending rescued thinking — the port of
 * 197's {@code thinkingStartedAt !== null}. Its falling edges are what stop a second ESC
 * from inserting the same thinking twice.
 */
class SpinnerThinkingActiveTest {

    @Test
    void gateIsClosedBeforeAnyThinking() {
        assertFalse(new SpinnerComponent().isThinkingActive());
    }

    @Test
    void gateOpensWhileThinkingIsLive() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setThinking(true);

        assertTrue(spinner.isThinkingActive());
    }

    @Test
    void reportingADurationClosesTheGate() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setThinking(true);
        spinner.setThinkingDuration(1_200L);

        assertFalse(spinner.isThinkingActive());
    }

    @Test
    void endingTheThinkingStretchClosesTheGate() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setThinking(true);
        spinner.setThinking(false);

        assertFalse(spinner.isThinkingActive());
    }

    @Test
    void stoppingTheSpinnerClosesTheGate() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setThinking(true);
        spinner.stop();

        assertFalse(spinner.isThinkingActive());
    }
}
