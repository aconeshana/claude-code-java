package com.claudecode.runtime.sessionhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionHostSubmissionLedgerTest {

    @Test
    void concurrentRetriesShareOneNativeSubmission() {
        SessionHostSubmissionLedger ledger = new SessionHostSubmissionLedger(8, Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> nativeResult = new CompletableFuture<>();

        var first = ledger.submit("session-1", "om_1", () -> {
            calls.incrementAndGet();
            return nativeResult;
        });
        var retry = ledger.submit("session-1", "om_1", () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        assertEquals(1, calls.get());
        nativeResult.complete(null);
        first.toCompletableFuture().join();
        retry.toCompletableFuture().join();
        assertEquals(1, calls.get());
    }

    @Test
    void failedSubmissionCanBeRetried() {
        SessionHostSubmissionLedger ledger = new SessionHostSubmissionLedger(8, Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        var failed = ledger.submit("session-1", "om_1", () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("not accepted"));
        });
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join());

        ledger.submit("session-1", "om_1", () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();
        assertEquals(2, calls.get());
    }

    @Test
    void messageIdsAreScopedBySessionAndEmptyIdsAreNotDeduplicated() {
        SessionHostSubmissionLedger ledger = new SessionHostSubmissionLedger(8, Duration.ofMinutes(1));
        AtomicInteger calls = new AtomicInteger();

        ledger.submit("session-1", "om_1", () -> completed(calls)).toCompletableFuture().join();
        ledger.submit("session-2", "om_1", () -> completed(calls)).toCompletableFuture().join();
        ledger.submit("session-1", "", () -> completed(calls)).toCompletableFuture().join();
        ledger.submit("session-1", "", () -> completed(calls)).toCompletableFuture().join();

        assertEquals(4, calls.get());
    }

    private static CompletableFuture<Void> completed(AtomicInteger calls) {
        calls.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }
}
