package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionResumeGenerationTest {

    @Test
    void onlyTheLatestResumeRequestMayCommit() {
        SessionResumeGeneration generation = new SessionResumeGeneration();

        long first = generation.begin();
        long second = generation.begin();

        assertFalse(generation.isCurrent(first));
        assertTrue(generation.isCurrent(second));
    }

    @Test
    void invalidationRejectsAnInFlightResumeWithoutStartingAnother() {
        SessionResumeGeneration generation = new SessionResumeGeneration();
        long request = generation.begin();

        generation.invalidate();

        assertFalse(generation.isCurrent(request));
    }
}
