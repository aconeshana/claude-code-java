package com.claudecode.runtime.query;


/**
 * Circuit-breaker state for auto-compact, threaded across loop iterations.
 */
record AutoCompactTrackingState(
    /** Whether a compact has happened on this tracking instance. */
    boolean compacted,
    /** Number of turns since the most recent compact (reset to 0 on success). */
    int turnCounter,

    String turnId,
    /** Consecutive auto-compact failures; reset to 0 on success. The circuit breaker. */
    int consecutiveFailures) {

    /**
     * Stop retrying auto-compact after this many consecutive failures.
     */
    public static final int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;


    public static AutoCompactTrackingState initial() {
        return new AutoCompactTrackingState(false, 0, "", 0);
    }


    public AutoCompactTrackingState withSuccess(String turnId) {
        return new AutoCompactTrackingState(true, 0, turnId, 0);
    }


    public AutoCompactTrackingState withFailure(int consecutiveFailures) {
        return new AutoCompactTrackingState(compacted, turnCounter, turnId, consecutiveFailures);
    }

    /**
     * Circuit-breaker predicate: once {@link #consecutiveFailures} reaches
     * {@link #MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES} we stop retrying auto-compact.
     */
    public boolean shouldRetry() {
        return consecutiveFailures < MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES;
    }
}
