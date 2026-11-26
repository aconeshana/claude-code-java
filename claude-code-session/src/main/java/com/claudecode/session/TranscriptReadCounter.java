package com.claudecode.session;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in per-thread counter for transcript JSONL full-file read passes.
 *
 * <p>Disabled by default ({@code ThreadLocal} holds {@code null}) so production
 * runs pay a single thread-local null check with zero allocations. Tests open a
 * {@link #track()} scope to assert strict upper bounds on how many times a
 * restore or switch flow reads the same transcript from disk.
 */
public final class TranscriptReadCounter {

    private static final ThreadLocal<ConcurrentHashMap<Path, AtomicInteger>> ACTIVE =
        new ThreadLocal<>();

    private TranscriptReadCounter() {}

    /** Starts tracking transcript reads on the current thread until the returned scope closes. */
    public static Scope track() {
        ConcurrentHashMap<Path, AtomicInteger> previous = ACTIVE.get();
        ConcurrentHashMap<Path, AtomicInteger> current = new ConcurrentHashMap<>();
        ACTIVE.set(current);
        return new Scope(current, previous);
    }

    /** Records one full-file read pass over {@code path} if tracking is active on this thread. */
    public static void record(Path path) {
        if (path == null) return;
        ConcurrentHashMap<Path, AtomicInteger> counts = ACTIVE.get();
        if (counts == null) return;
        Path normalized = path.toAbsolutePath().normalize();
        counts.computeIfAbsent(normalized, _ -> new AtomicInteger()).incrementAndGet();
    }

    /** Active tracking scope bound to the thread that opened it. */
    public static final class Scope implements AutoCloseable {
        private final ConcurrentHashMap<Path, AtomicInteger> counts;
        private final ConcurrentHashMap<Path, AtomicInteger> previous;

        private Scope(
                ConcurrentHashMap<Path, AtomicInteger> counts,
                ConcurrentHashMap<Path, AtomicInteger> previous) {
            this.counts = counts;
            this.previous = previous;
        }

        /** Number of full-file read passes recorded for {@code path}. */
        public int countFor(Path path) {
            if (path == null) return 0;
            AtomicInteger counter = counts.get(path.toAbsolutePath().normalize());
            return counter == null ? 0 : counter.get();
        }

        /** Total full-file read passes recorded across all transcript files in this scope. */
        public int totalReads() {
            int sum = 0;
            for (AtomicInteger counter : counts.values()) {
                sum += counter.get();
            }
            return sum;
        }

        /** Immutable snapshot of path -> read count for diagnostics. */
        public Map<Path, Integer> snapshot() {
            Map<Path, Integer> copy = new LinkedHashMap<>();
            counts.forEach((path, counter) -> copy.put(path, counter.get()));
            return Map.copyOf(copy);
        }

        @Override
        public void close() {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
