package com.claudecode.api;

/** Mutable request tag carrying the start of the most recent transport attempt. */
final class ApiRequestTiming {
    private volatile long lastAttemptStartMs;

    void markAttemptStarted() {
        lastAttemptStartMs = System.currentTimeMillis();
    }

    long lastAttemptStartMs() {
        return lastAttemptStartMs;
    }
}
