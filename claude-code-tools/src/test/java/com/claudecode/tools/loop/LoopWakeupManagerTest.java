package com.claudecode.tools.loop;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.claudecode.tools.cron.CronStore;

class LoopWakeupManagerTest {

    private final AtomicLong now = new AtomicLong(
        Instant.parse("2026-07-31T08:00:30Z").toEpochMilli());

    @AfterEach
    void resetStore() {
        CronStore.resetForTest();
    }

    @Test
    void clampsRoundsAndAnchorsOneShotCronToTheWholeMinute() {
        LoopWakeupManager manager = manager(true, false);

        LoopWakeupManager.ScheduleResult result =
            manager.schedule(1, "/loop check deploy", "watching deploy");

        assertEquals(60, result.clampedDelaySeconds());
        assertTrue(result.wasClamped());
        assertEquals(Instant.parse("2026-07-31T08:02:00Z").toEpochMilli(),
            result.scheduledFor());
        CronStore.CronJob job = CronStore.list().getFirst();
        assertEquals("2 8 * * *", job.cron());
        assertEquals("loop", job.kind());
        assertFalse(job.recurring());
        assertEquals(Instant.parse("2026-07-31T08:01:30Z").toEpochMilli(),
            job.createdAt());
    }

    @Test
    void cacheLeadMovesFiveMinuteWakeupBackUntilItStaysInsideCacheWindow() {
        now.set(Instant.parse("2026-07-31T08:00:10Z").toEpochMilli());
        LoopWakeupManager manager = manager(true, false);

        LoopWakeupManager.ScheduleResult result =
            manager.schedule(300, "/loop check deploy", "watching deploy");

        assertEquals(Instant.parse("2026-07-31T08:04:00Z").toEpochMilli(),
            result.scheduledFor());
        assertEquals(result.scheduledFor() - 1, CronStore.list().getFirst().createdAt());
    }

    @Test
    void schedulingSupersedesEveryPendingLoopWakeupButLeavesCronJobsAlone() {
        LoopWakeupManager manager = manager(true, false);
        CronStore.add("0 9 * * *", "ordinary", true, false);
        manager.schedule(60, "/loop first", "first");

        manager.schedule(120, "/loop second", "second");

        assertEquals(2, CronStore.list().size());
        assertEquals(1, CronStore.list().stream()
            .filter(job -> Strings.CS.equals("loop", job.kind())).count());
        assertEquals("/loop second", CronStore.list().stream()
            .filter(job -> Strings.CS.equals("loop", job.kind())).findFirst().orElseThrow().prompt());
    }

    @Test
    void dynamicLoopAgesOutAfterOfficialSevenDayMaximum() {
        LoopWakeupManager manager = manager(true, false);
        manager.schedule(60, "/loop check deploy", "first");
        for (int hour = 1; hour < 7 * 24; hour++) {
            now.addAndGet(60L * 60 * 1_000);
            assertTrue(manager.schedule(60, "/loop check deploy", "again") != null);
        }
        now.addAndGet(60L * 60 * 1_000);

        assertNull(manager.schedule(60, "/loop check deploy", "again"));
        assertFalse(manager.hasPendingLoopJobs());
    }

    @Test
    void oneKeepaliveFallbackIsArmedThenTheLoopStopsWhenModelOmitsAgain() {
        LoopWakeupManager manager = manager(true, true);
        manager.schedule(60, "/loop check deploy", "first");
        manager.markLoopTaskFired("/loop check deploy");
        CronStore.removeWhere(job -> Strings.CS.equals("loop", job.kind()));

        LoopWakeupManager.ScheduleResult fallback = manager.onTurnIdle();
        assertEquals(1200, fallback.clampedDelaySeconds());
        assertTrue(manager.hasPendingLoopJobs());

        manager.markLoopTaskFired("/loop check deploy");
        CronStore.removeWhere(job -> Strings.CS.equals("loop", job.kind()));
        assertNull(manager.onTurnIdle());
        assertFalse(manager.hasPendingLoopJobs());
    }

    @Test
    void userAbortCancelsPendingAndInFlightLoopState() {
        LoopWakeupManager manager = manager(true, true);
        manager.schedule(60, "/loop check deploy", "first");
        manager.markLoopTaskFired("/loop in flight");

        assertEquals(1, manager.cancelAll());
        assertFalse(manager.hasPendingLoopJobs());
        assertNull(manager.onTurnIdle());
    }

    private LoopWakeupManager manager(boolean dynamic, boolean keepalive) {
        return new LoopWakeupManager(
            () -> dynamic, () -> keepalive, now::get, ZoneOffset.UTC,
            7L * 24 * 60 * 60 * 1_000, 15_000, () -> 0x1234abcd);
    }
}
