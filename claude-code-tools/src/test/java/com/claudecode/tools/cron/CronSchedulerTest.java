package com.claudecode.tools.cron;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.claudecode.tools.loop.LoopPromptResolver;
import com.claudecode.tools.loop.LoopWakeupManager;

class CronSchedulerTest {

    private final AtomicLong now = new AtomicLong(
        localMillis(8, 0));

    @AfterEach
    void resetStore() {
        CronStore.resetForTest();
    }

    @Test
    void firesResolvedLoopTaskAndRemovesOneShot() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, Path.of("."), Path.of("."));
        LoopWakeupManager manager = new LoopWakeupManager(
            () -> true, () -> false, now::get, ZoneOffset.UTC,
            7L * 24 * 60 * 60 * 1_000, 15_000, () -> 1);
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronStore.add("1 8 * * *", LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL,
            false, false, null, now.get(), "loop", "00000001");
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get, resolver, manager);

        now.set(localMillis(8, 1));
        scheduler.checkNow();

        assertEquals(1, fired.size());
        assertEquals("loop", fired.getFirst().kind());
        assertEquals(LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL,
            fired.getFirst().prompt());
        assertTrue(Strings.CS.startsWith(fired.getFirst().resolvedPrompt(), "# Autonomous loop check"));
        assertTrue(CronStore.list().isEmpty());
    }

    @Test
    void loadingGateDefersDueTaskUntilIdle() {
        AtomicBoolean loading = new AtomicBoolean(true);
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronStore.add("1 8 * * *", "check deploy", false, false,
            null, now.get(), null, "00000001");
        CronScheduler scheduler = new CronScheduler(
            loading::get, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled());
        now.set(localMillis(8, 1));

        scheduler.checkNow();
        assertTrue(fired.isEmpty());

        loading.set(false);
        scheduler.checkNow();
        assertEquals(1, fired.size());
    }

    @Test
    void idleCheckAndBackgroundTickCannotDoubleFireTheSameTask() throws Exception {
        AtomicInteger fireCount = new AtomicInteger();
        CountDownLatch firstFireEntered = new CountDownLatch(1);
        CountDownLatch secondFireEntered = new CountDownLatch(1);
        CronStore.add("1 8 * * *", "check deploy", false, false,
            null, now.get(), null, "00000001");
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {
                int count = fireCount.incrementAndGet();
                if (count == 1) {
                    firstFireEntered.countDown();
                    try {
                        secondFireEntered.await(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    secondFireEntered.countDown();
                }
            }, now::get, LoopPromptResolver.passthrough(), LoopWakeupManager.disabled());
        now.set(localMillis(8, 1));

        Thread idleCheck = Thread.startVirtualThread(scheduler::checkNow);
        assertTrue(firstFireEntered.await(1, TimeUnit.SECONDS));
        Thread timerTick = Thread.startVirtualThread(scheduler::checkNow);
        idleCheck.join();
        timerTick.join();

        assertEquals(1, fireCount.get(),
            "197 serializes checkNow with its interval on the JavaScript event loop");
    }

    @Test
    void killGateStopsExistingTasksWithoutRemovingThem() {
        AtomicBoolean killed = new AtomicBoolean(true);
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronStore.add("1 8 * * *", "check deploy", false, false,
            null, now.get(), null, "00000001");
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            killed::get, () -> false, _ -> {}, _ -> true);

        now.set(localMillis(8, 1));
        scheduler.checkNow();

        assertTrue(fired.isEmpty());
        assertEquals(1, CronStore.list().size());
    }

    @Test
    void filterHidesTasksFromSchedulingAndFiring() {
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronStore.add("1 8 * * *", "hidden", false, false,
            null, now.get(), null, "00000001");
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> false, _ -> {}, _ -> false);

        now.set(localMillis(8, 1));
        scheduler.checkNow();

        assertTrue(fired.isEmpty());
        assertEquals(1, CronStore.list().size());
    }

    @Test
    void recurringTaskExpiresAtOfficialBoundaryButPermanentTaskDoesNot() {
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        long created = now.get() - CronScheduler.DEFAULT_MAX_AGE_MS;
        CronStore.add("1 8 * * *", "aged", true, false,
            null, created, null, "00000001");
        CronStore.add("1 8 * * *", "permanent", true, true,
            null, created, null, "00000002", null, true);
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> false, _ -> {}, _ -> true);

        now.set(localMillis(8, 1));
        scheduler.checkNow();

        assertEquals(2, fired.size());
        assertTrue(CronStore.list().stream().noneMatch(t -> Strings.CS.equals("00000001", t.id())));
        assertTrue(CronStore.list().stream().anyMatch(t -> Strings.CS.equals("00000002", t.id())));
    }

    @Test
    void startupSurfacesMissedOneShotAndRemovesIt(@TempDir Path projectRoot) throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        long created = localMillis(7, 0);
        Files.writeString(CronStore.durablePath(), """
            {"tasks":[{"id":"abc12345","cron":"1 8 * * *","prompt":"missed",
              "createdAt":%d}]}
            """.formatted(created));
        List<List<CronStore.CronJob>> missed = new ArrayList<>();
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {}, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> false, missed::add, _ -> true);

        now.set(localMillis(8, 2));
        scheduler.start();

        assertEquals(1, missed.size());
        assertEquals("abc12345", missed.getFirst().getFirst().id());
        assertTrue(CronStore.list().isEmpty());
        scheduler.stop();
    }

    @Test
    void interactiveStartupWaitsForEnableInsteadOfCreatingClaudeDirectory(
            @TempDir Path projectRoot) throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {}, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> false, _ -> {}, _ -> true,
            () -> CronJitterConfig.DEFAULT, projectRoot, "session-a",
            () -> "session-a", CronSchedulerLock::sameProcess, true);

        scheduler.start();
        try {
            assertFalse(Files.exists(projectRoot.resolve(".claude")),
                "197 only runs the enable poll when no task has enabled scheduling");

            CronStore.add("1 8 * * *", "session task", false, false);
            long deadline = System.nanoTime() + 2_500_000_000L;
            while (!Files.isDirectory(projectRoot.resolve(".claude"))
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Files.isDirectory(projectRoot.resolve(".claude")),
                "CronCreate-style session work should enable the scheduler on its next poll");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void enabledSchedulerAcquiresProjectLeaseEvenBeforeTasksExist(
            @TempDir Path projectRoot) {
        CronStore.configureProjectRootForTest(projectRoot);
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {}, projectRoot, "session-a", () -> false);
        CronSchedulerLock contender = new CronSchedulerLock(projectRoot, "session-b");

        scheduler.start();
        try {
            assertFalse(contender.tryAcquire(),
                "197 acquires the scheduler lock during enable(), not on first task");
        } finally {
            scheduler.stop();
        }
        assertTrue(contender.tryAcquire());
        contender.release();
    }

    @Test
    void passiveSchedulerWaitsForFiveSecondLockProbeInsteadOfTakingOverOnEveryCheck(
            @TempDir Path projectRoot) {
        CronStore.configureProjectRootForTest(projectRoot);
        CronStore.add("1 8 * * *", "owned elsewhere", true, true,
            null, now.get(), null, "abc12345", null, false);
        CronSchedulerLock firstOwner = new CronSchedulerLock(projectRoot, "session-a");
        assertTrue(firstOwner.tryAcquire());
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> true, _ -> {}, _ -> true,
            () -> new CronJitterConfig(0, 0, 0, 0, 30,
                CronScheduler.DEFAULT_MAX_AGE_MS, 0), projectRoot, "session-b",
            () -> "session-b", CronSchedulerLock::sameProcess);

        scheduler.start();
        firstOwner.release();
        try {
            now.set(localMillis(8, 1));
            scheduler.checkNow();
            assertTrue(fired.isEmpty(),
                "197 only retries a lost project lease on its 5 second probe timer");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void successfulLockProbeStopsItsReleasedFiveSecondTimer(@TempDir Path projectRoot) {
        CronStore.configureProjectRootForTest(projectRoot);
        CronSchedulerLock firstOwner = new CronSchedulerLock(projectRoot, "session-a");
        assertTrue(firstOwner.tryAcquire());
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {}, projectRoot, "session-b", () -> false);

        scheduler.start();
        firstOwner.release();
        try {
            scheduler.probeLockNowForTest();
            assertFalse(scheduler.lockProbeRunningForTest(),
                "197 clears the 5 second takeover interval after acquiring the lease");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void currentSessionMissedTaskIsConfirmedEvenWhenAnotherSessionHoldsTheLock(
            @TempDir Path projectRoot) throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        Files.writeString(CronStore.durablePath(), """
            {"tasks":[{"id":"abc12345","cron":"1 8 * * *","prompt":"missed",
              "createdAt":%d,"createdBySessionId":"session-b","createdByPid":%d,
              "createdByProcStart":"%s"}]}
            """.formatted(localMillis(7, 0), ProcessHandle.current().pid(),
                CronSchedulerLock.currentProcessStartToken()));
        CronSchedulerLock otherSession = new CronSchedulerLock(projectRoot, "session-a");
        assertTrue(otherSession.tryAcquire());
        List<List<CronStore.CronJob>> missed = new ArrayList<>();
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> {}, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled(),
            () -> false, () -> false, missed::add, _ -> true,
            () -> CronJitterConfig.DEFAULT, projectRoot, "session-b",
            () -> "session-b", CronSchedulerLock::sameProcess);
        now.set(localMillis(8, 2));

        try {
            scheduler.start();
            assertEquals(1, missed.size(),
                "197 applies creator affinity before the owner lock gate");
            assertEquals("abc12345", missed.getFirst().getFirst().id());
        } finally {
            scheduler.stop();
            otherSession.release();
        }
    }

    @Test
    void defaultMissedHandlerBuildsTheOfficialConfirmationPrompt(@TempDir Path projectRoot)
            throws Exception {
        CronStore.configureProjectRootForTest(projectRoot);
        Files.createDirectories(CronStore.durablePath().getParent());
        Files.writeString(CronStore.durablePath(), """
            {"tasks":[{"id":"abc12345","cron":"1 8 * * *","prompt":"run it",
              "createdAt":%d}]}
            """.formatted(localMillis(7, 0)));
        List<CronScheduler.FiredTask> fired = new ArrayList<>();
        CronScheduler scheduler = new CronScheduler(
            () -> false, fired::add, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled());

        now.set(localMillis(8, 2));
        scheduler.start();

        assertEquals(1, fired.size());
        assertTrue(Strings.CS.contains(fired.getFirst().resolvedPrompt(), 
            "First use the AskUserQuestion tool"));
        assertTrue(Strings.CS.contains(fired.getFirst().resolvedPrompt(), "run it"));
        scheduler.stop();
    }

    @Test
    void deterministicJitterMatchesReleasedDefaultFractionAndCap() {
        long from = localMillis(8, 0);
        long first = CronScheduler.jitteredNextCronRunMs(
            "0 * * * *", from, "00000000", CronJitterConfig.DEFAULT);
        long jittered = CronScheduler.jitteredNextCronRunMs(
            "0 * * * *", from, "ffffffff", CronJitterConfig.DEFAULT);

        assertEquals(localMillis(9, 0), first);
        assertTrue(jittered >= localMillis(9, 29));
        assertTrue(jittered < localMillis(9, 30));
    }

    @Test
    void fiveMinuteRecurringScheduleUsesReleasedCacheLeadBoundary() {
        long from = localMillis(8, 0);

        long next = CronScheduler.jitteredNextCronRunMs(
            "*/5 * * * *", from, "ffffffff", CronJitterConfig.DEFAULT);

        assertEquals(from + 5 * 60_000L - CronJitterConfig.DEFAULT.cacheLeadMs(), next);
    }

    @Test
    void failedDispatchLeavesOneShotAvailableForRetry() {
        CronStore.add("1 8 * * *", "retry me", false, false,
            null, now.get(), null, "00000001");
        CronScheduler scheduler = new CronScheduler(
            () -> false, _ -> { throw new IllegalStateException("queue unavailable"); }, now::get,
            LoopPromptResolver.passthrough(), LoopWakeupManager.disabled());
        now.set(localMillis(8, 1));

        assertThrows(IllegalStateException.class, scheduler::checkNow);

        assertEquals(1, CronStore.list().size());
        assertEquals("00000001", CronStore.list().getFirst().id());
    }

    @Test
    void releasedCreatorOwnershipDefersToLiveCreatorThenAllowsLockTakeover() {
        CronStore.CronJob ownedByOtherSession = new CronStore.CronJob(
            "abc12345", "1 8 * * *", "owned", false, true, now.get(), null,
            false, null, null, "session-a", 123L, "proc-token");
        BiPredicate<Long, String> creatorAlive = (_, _) -> true;

        assertFalse(CronScheduler.shouldProcess(
            ownedByOtherSession, true, "session-b", creatorAlive));
        assertTrue(CronScheduler.shouldProcess(
            ownedByOtherSession, true, "session-a", creatorAlive));
        assertTrue(CronScheduler.shouldProcess(
            ownedByOtherSession, true, "session-b", (_, _) -> false));
        assertFalse(CronScheduler.shouldProcess(
            ownedByOtherSession, false, "session-b", (_, _) -> false));
    }

    @Test
    void missedTaskNotificationRendersCreatedAtInLocalTime() {

        var fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.US)
            .withZone(ZoneId.of("UTC"));
        // 2026-07-31T07:04:05Z
        long createdAt = Instant.parse("2026-07-31T07:04:05Z").toEpochMilli();
        var job = new CronStore.CronJob(
            "abc12345", "1 8 * * *", "run it", false, false, createdAt, null, false, null, "oneshot");

        String notification = CronScheduler.buildMissedTaskNotification(
            List.of(job), fmt);

        // Java's US SHORT format renders "7/31/26, 7:04<nbsp>AM" (narrow
        // no-break space before AM), so match the stable fragments instead of
        // the exact literal. The key assertions: local date+time present, and
        // no UTC ISO-8601 instant.
        assertTrue(Strings.CS.contains(notification, "7/31/26"),
            "created timestamp must show the local date, was: " + notification);
        assertTrue(Strings.CS.contains(notification, "7:04")
                && Strings.CS.contains(notification, "AM"),
            "created timestamp must show the local time, was: " + notification);
        assertFalse(Strings.CS.contains(notification, "2026-07-31T07:04:05Z"),
            "must not emit UTC ISO-8601 instant");
        assertTrue(Strings.CS.contains(notification, "run it"));
    }

    private static long localMillis(int hour, int minute) {
        return LocalDateTime.of(2026, 7, 31, hour, minute)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
