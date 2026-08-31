package com.claudecode.tools.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CronFileReloadDebouncerTest {

    @Test
    void reloadsOnceAfterTheLatestWriteHasBeenStable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch fired = new CountDownLatch(1);
        try (CronFileReloadDebouncer debouncer = new CronFileReloadDebouncer(
                Duration.ofMillis(100), () -> {
                    calls.incrementAndGet();
                    fired.countDown();
                })) {
            debouncer.changed();
            Thread.sleep(60);
            debouncer.changed();

            assertFalse(fired.await(70, TimeUnit.MILLISECONDS),
                "the first event must not reload while a later write is still settling");
            assertTrue(fired.await(200, TimeUnit.MILLISECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void closeCancelsAPendingReload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CronFileReloadDebouncer debouncer = new CronFileReloadDebouncer(
            Duration.ofMillis(40), calls::incrementAndGet);
        debouncer.changed();

        debouncer.close();
        Thread.sleep(80);

        assertEquals(0, calls.get());
    }
}
