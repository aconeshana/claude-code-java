package com.claudecode.core.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionMetricsFormatTest {
    @Test
    void portsPinnedDeepSeekDisplayRounding() {
        assertEquals("45.2s", SessionMetricsFormat.formatDuration(45_249));
        assertEquals("2m42s", SessionMetricsFormat.formatDuration(161_600));
        assertEquals("34", SessionMetricsFormat.formatTokensPerSecond(34.4));
        assertEquals("10", SessionMetricsFormat.formatTokensPerSecond(9.96));
        assertEquals("3.1", SessionMetricsFormat.formatTokensPerSecond(3.14));
        assertEquals("12.2K", SessionMetricsFormat.formatTokens(12_200));
        assertEquals("517K", SessionMetricsFormat.formatTokens(517_000));
        assertEquals("1.2M", SessionMetricsFormat.formatTokens(1_200_000));
    }

    @Test
    void cacheHitNeverRoundsANonFullRatioToOneHundred() {
        assertEquals("99.5", SessionMetricsFormat.cacheHitPercent(snapshot(5, 995)));
        assertEquals("99.99", SessionMetricsFormat.cacheHitPercent(snapshot(1, 9_999)));
        assertEquals("100", SessionMetricsFormat.cacheHitPercent(snapshot(0, 100)));
        assertNull(SessionMetricsFormat.cacheHitPercent(snapshot(0, 0)));
    }

    private static SessionMetricsSnapshot snapshot(long missed, long cacheRead) {
        return new SessionMetricsSnapshot(true, 0, 0, 0, 0, 0, 0, 0, 0,
            missed, 0, 0, cacheRead);
    }
}
