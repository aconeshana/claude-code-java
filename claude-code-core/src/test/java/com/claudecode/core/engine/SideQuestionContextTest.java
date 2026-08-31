package com.claudecode.core.engine;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SideQuestionContextTest {

    @Test
    void nestedScopesRestoreOuterValuesAndClearAfterFailure() {
        SideQuestionContext.Exchange outerExchange = new SideQuestionContext.Exchange("outer", "answer");
        SideQuestionContext.Exchange innerExchange = new SideQuestionContext.Exchange("inner", "answer");
        AbortController outerAbort = new AbortController();
        AbortController innerAbort = new AbortController();

        SideQuestionContext.withHistory(List.of(outerExchange), outerAbort, () -> {
            assertEquals(List.of(outerExchange), SideQuestionContext.history());
            assertSame(outerAbort, SideQuestionContext.abortController());
            SideQuestionContext.withHistory(List.of(innerExchange), innerAbort, () -> {
                assertEquals(List.of(innerExchange), SideQuestionContext.history());
                assertSame(innerAbort, SideQuestionContext.abortController());
                return null;
            });
            assertEquals(List.of(outerExchange), SideQuestionContext.history());
            assertSame(outerAbort, SideQuestionContext.abortController());
            return null;
        });

        assertThrows(IllegalStateException.class, () ->
            SideQuestionContext.withHistory(List.of(innerExchange), innerAbort, () -> {
                throw new IllegalStateException("boom");
            }));
        assertEquals(List.of(), SideQuestionContext.history());
        assertNull(SideQuestionContext.abortController());
    }

    @Test
    void concurrentVirtualThreadsKeepSideQuestionScopesIsolated() throws InterruptedException {
        SideQuestionContext.Exchange firstExchange = new SideQuestionContext.Exchange("first", "one");
        SideQuestionContext.Exchange secondExchange = new SideQuestionContext.Exchange("second", "two");
        AtomicReference<List<SideQuestionContext.Exchange>> firstSeen = new AtomicReference<>();
        AtomicReference<List<SideQuestionContext.Exchange>> secondSeen = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = contextThread(firstExchange, firstSeen, ready, release);
        Thread second = contextThread(secondExchange, secondSeen, ready, release);
        ready.await();
        release.countDown();
        first.join();
        second.join();

        assertEquals(List.of(firstExchange), firstSeen.get());
        assertEquals(List.of(secondExchange), secondSeen.get());
    }

    private static Thread contextThread(
            SideQuestionContext.Exchange exchange,
            AtomicReference<List<SideQuestionContext.Exchange>> seen,
            CountDownLatch ready,
            CountDownLatch release) {
        return Thread.startVirtualThread(() ->
            SideQuestionContext.withHistory(List.of(exchange), new AbortController(), () -> {
                ready.countDown();
                await(release);
                seen.set(SideQuestionContext.history());
                return null;
            }));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating side-question threads");
        }
    }
}
