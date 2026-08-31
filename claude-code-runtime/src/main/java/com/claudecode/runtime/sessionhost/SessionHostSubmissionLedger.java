package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Bounded process-lifetime idempotency ledger for remote user submissions.
 */
@Explanation("Deduplicates retried IM turns before they reach the native UI")
public final class SessionHostSubmissionLedger {

    static final int DEFAULT_MAX_ENTRIES = 4_096;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private record Key(String sessionId, String messageId) {}

    private static final class Entry {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private long completedAtNanos;
    }

    private final int maxEntries;
    private final long ttlNanos;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public SessionHostSubmissionLedger() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL);
    }

    SessionHostSubmissionLedger(int maxEntries, Duration ttl) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
    }

    /** Executes {@code submitter} at most once for a non-empty message ID. */
    public CompletionStage<Void> submit(
            String sessionId,
            String messageId,
            Supplier<? extends CompletionStage<Void>> submitter) {
        Objects.requireNonNull(submitter, "submitter");
        String normalizedSession = normalize(sessionId);
        String normalizedMessage = normalize(messageId);
        if (normalizedMessage.isEmpty()) return invoke(submitter);

        Key key = new Key(normalizedSession, normalizedMessage);
        Entry owned;
        synchronized (entries) {
            long now = System.nanoTime();
            removeExpiredCompleted(now);
            Entry existing = entries.get(key);
            if (existing != null) return existing.result;
            evictCompletedToMakeRoom();
            if (entries.size() >= maxEntries) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("too many remote submissions are in flight"));
            }
            owned = new Entry();
            entries.put(key, owned);
        }

        CompletionStage<Void> submitted;
        try {
            submitted = Objects.requireNonNull(submitter.get(), "submitter result");
        } catch (Throwable failure) {
            completeFailure(key, owned, failure);
            return owned.result;
        }
        submitted.whenComplete((_, failure) -> {
            if (failure != null) {
                completeFailure(key, owned, failure);
            } else {
                synchronized (entries) {
                    owned.completedAtNanos = System.nanoTime();
                }
                owned.result.complete(null);
            }
        });
        return owned.result;
    }

    private static CompletionStage<Void> invoke(
            Supplier<? extends CompletionStage<Void>> submitter) {
        try {
            return Objects.requireNonNull(submitter.get(), "submitter result");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void completeFailure(Key key, Entry owned, Throwable failure) {
        synchronized (entries) {
            if (entries.get(key) == owned) entries.remove(key);
        }
        owned.result.completeExceptionally(failure);
    }

    private void removeExpiredCompleted(long now) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.completedAtNanos != 0 && now - entry.completedAtNanos >= ttlNanos) {
                iterator.remove();
            }
        }
    }

    private void evictCompletedToMakeRoom() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() >= maxEntries && iterator.hasNext()) {
            if (iterator.next().getValue().completedAtNanos != 0) iterator.remove();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
