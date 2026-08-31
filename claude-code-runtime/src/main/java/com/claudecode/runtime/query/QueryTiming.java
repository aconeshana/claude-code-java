package com.claudecode.runtime.query;


import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Monotonic first-milestone timing for one SDK query.
 */
final class QueryTiming {

    private final LongSupplier nanoTime;
    private final AtomicLong startNanos = new AtomicLong();
    private final AtomicLong requestStartedNanos = new AtomicLong();
    private final AtomicLong streamEventNanos = new AtomicLong();
    private final AtomicLong outputNanos = new AtomicLong();

    QueryTiming() {
        this(System::nanoTime);
    }

    QueryTiming(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        reset();
    }

    void reset() {
        startNanos.set(nanoTime.getAsLong());
        requestStartedNanos.set(0);
        streamEventNanos.set(0);
        outputNanos.set(0);
    }

    void markRequestStarted() {
        markFirst(requestStartedNanos);
    }

    void markStreamEvent() {
        markFirst(streamEventNanos);
    }

    void markOutput() {
        markFirst(outputNanos);
    }

    Snapshot snapshot() {
        long start = startNanos.get();
        return new Snapshot(
            elapsedMillis(start, outputNanos.get()),
            elapsedMillis(start, streamEventNanos.get()),
            elapsedMillis(start, requestStartedNanos.get()));
    }

    private void markFirst(AtomicLong target) {
        target.compareAndSet(0, nanoTime.getAsLong());
    }

    private static long elapsedMillis(long start, long milestone) {
        if (start == 0 || milestone == 0 || milestone < start) return 0;
        return (milestone - start) / 1_000_000L;
    }

    record Snapshot(long ttftMs, long ttftStreamMs, long timeToRequestMs) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0);
    }
}
