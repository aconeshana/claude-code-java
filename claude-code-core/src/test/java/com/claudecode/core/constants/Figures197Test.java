package com.claudecode.core.constants;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;


class Figures197Test {

    @Test
    void toolResultPrefixEndsWithNonBreakingSpace() {
        assertEquals("  ⎿ \u00a0", Figures.RESULT_PREFIX);
        assertEquals(5, Figures.RESULT_PREFIX.codePointCount(0, Figures.RESULT_PREFIX.length()));
    }
}
