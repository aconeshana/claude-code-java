package com.claudecode.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiRetryEventsTest {

    @Test
    void nestedObserversRestoreTheOuterBindingAndClearAfterFailure() {
        ApiRetryEvents.Event outerFirst = event(1);
        ApiRetryEvents.Event inner = event(2);
        ApiRetryEvents.Event outerLast = event(3);
        List<ApiRetryEvents.Event> outerEvents = new ArrayList<>();
        List<ApiRetryEvents.Event> innerEvents = new ArrayList<>();

        ApiRetryEvents.observe(outerEvents::add, () -> {
            ApiRetryEvents.emit(outerFirst);
            ApiRetryEvents.observe(innerEvents::add, () -> {
                ApiRetryEvents.emit(inner);
                return null;
            });
            ApiRetryEvents.emit(outerLast);
            return null;
        });

        assertEquals(List.of(outerFirst, outerLast), outerEvents);
        assertEquals(List.of(inner), innerEvents);
        assertThrows(IllegalStateException.class, () ->
            ApiRetryEvents.observe(outerEvents::add, () -> {
                throw new IllegalStateException("boom");
            }));
        ApiRetryEvents.emit(event(4));
        assertEquals(List.of(outerFirst, outerLast), outerEvents);
    }

    @Test
    void concurrentVirtualThreadsKeepObserversIsolated() throws InterruptedException {
        ApiRetryEvents.Event firstEvent = event(1);
        ApiRetryEvents.Event secondEvent = event(2);
        AtomicReference<ApiRetryEvents.Event> firstSeen = new AtomicReference<>();
        AtomicReference<ApiRetryEvents.Event> secondSeen = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread first = observerThread(firstSeen, firstEvent, ready, release);
        Thread second = observerThread(secondSeen, secondEvent, ready, release);
        ready.await();
        release.countDown();
        first.join();
        second.join();

        assertEquals(firstEvent, firstSeen.get());
        assertEquals(secondEvent, secondSeen.get());
    }

    private static Thread observerThread(AtomicReference<ApiRetryEvents.Event> seen,
                                         ApiRetryEvents.Event event,
                                         CountDownLatch ready,
                                         CountDownLatch release) {
        return Thread.startVirtualThread(() -> ApiRetryEvents.observe(seen::set, () -> {
            ready.countDown();
            await(release);
            ApiRetryEvents.emit(event);
            return null;
        }));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while coordinating observer threads");
        }
    }

    private static ApiRetryEvents.Event event(int attempt) {
        return new ApiRetryEvents.Event(429, attempt, 3, attempt * 10L);
    }
}
