package com.claudecode.tools.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DreamTaskDetailsTest {

    @Test
    void addTurn_tracksTouchedFilesAndKeepsThirtyMostRecentTurns() {
        DreamTaskDetails details = DreamTaskDetails.starting(7);

        for (int i = 0; i < 35; i++) {
            details = details.addTurn(
                new DreamTaskDetails.DreamTurn("turn-" + i, i % 3),
                i == 2 ? List.of("MEMORY.md", "MEMORY.md") : List.of());
        }

        assertEquals(DreamTaskDetails.DreamPhase.UPDATING, details.phase());
        assertEquals(7, details.sessionsReviewing());
        assertEquals(List.of("MEMORY.md"), details.filesTouched());
        assertEquals(30, details.turns().size());
        assertEquals("turn-5", details.turns().getFirst().text());
        assertEquals("turn-34", details.turns().getLast().text());
    }

    @Test
    void addTurn_skipsPureNoOp() {
        DreamTaskDetails details = DreamTaskDetails.starting(1);

        assertSame(details, details.addTurn(
            new DreamTaskDetails.DreamTurn("", 0), List.of()));
    }
}
