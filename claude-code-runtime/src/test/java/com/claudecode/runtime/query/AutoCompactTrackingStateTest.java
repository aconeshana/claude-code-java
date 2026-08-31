package com.claudecode.runtime.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Locks the auto-compact circuit-breaker behavior of {@link AutoCompactTrackingState}, which.
 */
class AutoCompactTrackingStateTest {

    @Test
    void initialIsZeroedAndRetriable() {
        AutoCompactTrackingState s = AutoCompactTrackingState.initial();
        assertFalse(s.compacted());
        assertEquals(0, s.turnCounter());
        assertEquals("", s.turnId());
        assertEquals(0, s.consecutiveFailures());
        assertTrue(s.shouldRetry());
    }

    @Test
    void maxThresholdMatchesOriginal() {

        assertEquals(3, AutoCompactTrackingState.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES);
    }

    @Test
    void successResetsFailuresAndMarksCompacted() {
        AutoCompactTrackingState failing =
            AutoCompactTrackingState.initial().withFailure(2);
        AutoCompactTrackingState ok = failing.withSuccess("turn-xyz");

        assertTrue(ok.compacted());
        assertEquals(0, ok.consecutiveFailures());
        assertEquals(0, ok.turnCounter());
        assertEquals("turn-xyz", ok.turnId());
        assertTrue(ok.shouldRetry());
    }

    @Test
    void failurePreservesTurnIdentityAndBumpsCount() {

        // so a failed attempt preserves compacted/turnId/turnCounter from the last success.
        AutoCompactTrackingState s =
            AutoCompactTrackingState.initial().withSuccess("t1");
        AutoCompactTrackingState failed = s.withFailure(1);

        assertTrue(failed.compacted(), "compacted is preserved from the prior success");
        assertEquals("t1", failed.turnId(), "turnId must survive a failure");
        assertEquals(0, failed.turnCounter(), "turnCounter must survive a failure");
        assertEquals(1, failed.consecutiveFailures());
        assertTrue(failed.shouldRetry());
    }

    @Test
    void breakerOpensExactlyAtMax() {
        AutoCompactTrackingState s = AutoCompactTrackingState.initial();
        // 1st failure
        s = s.withFailure(s.consecutiveFailures() + 1);
        assertTrue(s.shouldRetry(), "1 failure must still retry");
        // 2nd failure
        s = s.withFailure(s.consecutiveFailures() + 1);
        assertTrue(s.shouldRetry(), "2 failures must still retry");
        // 3rd failure — reaches the threshold, breaker opens
        s = s.withFailure(s.consecutiveFailures() + 1);
        assertEquals(3, s.consecutiveFailures());
        assertFalse(s.shouldRetry(), "3 consecutive failures must stop retrying");

        // A 4th failure keeps the breaker open (never goes positive again).
        s = s.withFailure(s.consecutiveFailures() + 1);
        assertEquals(4, s.consecutiveFailures());
        assertFalse(s.shouldRetry());
    }

    @Test
    void successAfterFailuresReclosesBreaker() {
        AutoCompactTrackingState s = AutoCompactTrackingState.initial()
            .withFailure(1)
            .withFailure(2)
            .withFailure(3);
        assertFalse(s.shouldRetry(), "breaker open at 3");

        s = s.withSuccess("recovery");
        assertTrue(s.shouldRetry(), "a successful compact recloses the breaker");
        assertEquals(0, s.consecutiveFailures());
    }
}
