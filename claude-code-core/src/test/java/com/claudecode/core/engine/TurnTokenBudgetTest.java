package com.claudecode.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TurnTokenBudgetTest {

    @Test
    void parsesReleasedShorthandAndVerboseForms() {
        assertEquals(500_000L, TurnTokenBudget.parseTarget("+500k audit everything"));
        assertEquals(1_500_000L, TurnTokenBudget.parseTarget("audit everything +1.5m."));
        assertEquals(2_000_000_000L, TurnTokenBudget.parseTarget("use 2b tokens for this"));
        assertEquals(75_000L, TurnTokenBudget.parseTarget("spend 75k tokens carefully"));
        assertNull(TurnTokenBudget.parseTarget("ordinary prompt"));
    }

    @Test
    void remainingNeverDropsBelowZero() {
        TurnTokenBudget budget = new TurnTokenBudget(10L);
        budget.addOutputTokens(14L);

        assertEquals(14L, budget.spent());
        assertEquals(0L, budget.remaining());
    }
}
