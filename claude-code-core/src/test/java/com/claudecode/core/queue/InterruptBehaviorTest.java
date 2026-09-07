package com.claudecode.core.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ordinal contract of the interrupt-behavior union — the processing order must stay
 * stable because the streaming executor persists no numeric value, but tests and
 * comparisons rely on the {@code CANCEL}/{@code BLOCK} distinction remaining closed.
 */
class InterruptBehaviorTest {

    @Test
    @DisplayName("exposes exactly the two released behaviors")
    void exposesExactlyTwoBehaviors() {
        assertEquals(2, InterruptBehavior.values().length);
    }

    @Test
    @DisplayName("CANCEL is a distinct value from BLOCK")
    void cancelDiffersFromBlock() {
        assertEquals(InterruptBehavior.CANCEL, InterruptBehavior.valueOf("CANCEL"));
        assertEquals(InterruptBehavior.BLOCK, InterruptBehavior.valueOf("BLOCK"));
    }
}
