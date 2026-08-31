package com.claudecode.ui.lanterna.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LatestTaskRunnerTest {

    @Test
    void debounceRunsOnlyTheLatestQueuedTask() throws Exception {
        AtomicInteger value = new AtomicInteger();
        try (LatestTaskRunner runner = new LatestTaskRunner("suggest-test", Duration.ofMillis(50))) {
            runner.submit(_ -> value.set(1));
            runner.submit(_ -> value.set(2));
            runner.submit(_ -> value.set(3));

            assertTrue(runner.awaitIdle(Duration.ofSeconds(1)));
            assertEquals(3, value.get());
        }
    }

    @Test
    void newerSubmissionInterruptsRunningStaleTask() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstStopped = new CountDownLatch(1);
        AtomicBoolean latestRan = new AtomicBoolean();
        try (LatestTaskRunner runner = new LatestTaskRunner("suggest-test", Duration.ZERO)) {
            runner.submit(cancelled -> {
                firstStarted.countDown();
                while (!cancelled.getAsBoolean()) {
                    Thread.onSpinWait();
                }
                firstStopped.countDown();
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            runner.submit(_ -> latestRan.set(true));

            assertTrue(firstStopped.await(1, TimeUnit.SECONDS));
            assertTrue(runner.awaitIdle(Duration.ofSeconds(1)));
            assertTrue(latestRan.get());
        }
    }

    @Test
    void closeCancelsQueuedWork() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        LatestTaskRunner runner = new LatestTaskRunner("suggest-test", Duration.ofSeconds(1));
        runner.submit(_ -> ran.set(true));

        runner.close();

        assertTrue(runner.awaitIdle(Duration.ofSeconds(1)));
        assertFalse(ran.get());
    }

    @Test
    void cancelInvalidatesQueuedWorkWithoutReplacement() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        try (LatestTaskRunner runner = new LatestTaskRunner(
                "suggest-test", Duration.ofMillis(100))) {
            runner.submit(_ -> ran.set(true));

            runner.cancel();

            assertTrue(runner.awaitIdle(Duration.ofSeconds(1)));
            assertFalse(ran.get());
        }
    }
}
