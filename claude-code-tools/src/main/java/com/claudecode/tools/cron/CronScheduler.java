package com.claudecode.tools.cron;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.claudecode.tools.loop.LoopPromptResolver;
import com.claudecode.tools.loop.LoopWakeupManager;

/**
 * Background scheduled-task worker shared by the REPL and headless SDK paths.
 */
public final class CronScheduler {

    private static final long CHECK_INTERVAL_MS = 1_000L;
    private static final long LOCK_PROBE_INTERVAL_MS = 5_000L;
    private static final long FILE_STABILITY_MS = 300L;
    private static final long FIVE_MINUTES_MS = 5 * 60_000L;
    private static final Pattern EVERY_N_MINUTES = Pattern.compile("^\\*/\\d+ \\* \\* \\* \\*$");
    public static final long DEFAULT_MAX_AGE_MS = CronJitterConfig.DEFAULT.recurringMaxAgeMs();

    private final BooleanSupplier isLoading;
    private final Consumer<FiredTask> onFire;
    private final LongSupplier nowMillis;
    private final LoopPromptResolver promptResolver;
    private final LoopWakeupManager loopWakeupManager;
    private final BooleanSupplier isKilled;
    private final BooleanSupplier assistantMode;
    private final Consumer<List<CronStore.CronJob>> onMissed;
    private final Predicate<CronStore.CronJob> filter;
    private final Supplier<CronJitterConfig> jitterConfigSupplier;
    private final Supplier<String> currentSessionId;
    private final BiPredicate<Long, String> processAlive;
    private final Path projectRoot;
    private final CronSchedulerLock schedulerLock;
    private final boolean deferUntilEnabled;
    private final ConcurrentHashMap<String, Long> nextFireAt = new ConcurrentHashMap<>();
    private final Set<String> missedAsked = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean started;
    private volatile boolean owner = true;
    private volatile String claimedSessionId;
    private ScheduledExecutorService executor;
    private ScheduledExecutorService enablePollExecutor;
    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService lockProbeExecutor;
    private CronFileReloadDebouncer fileReloadDebouncer;

    public CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire) {
        this(isLoading, onFire, System::currentTimeMillis,
            LoopPromptResolver.global(), LoopWakeupManager.global(),
            () -> false, () -> false, null, _ -> true,
            defaultJitterConfigSupplier(), defaultProjectRoot(), UUID.randomUUID().toString(),
            () -> null, CronSchedulerLock::sameProcess, true);
    }

    /** Interactive constructor whose session identity follows in-place resume/branch. */
    public CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                         Supplier<String> currentSessionId) {
        this(isLoading, onFire, System::currentTimeMillis,
            LoopPromptResolver.global(), LoopWakeupManager.global(),
            () -> false, () -> false, null, _ -> true,
            defaultJitterConfigSupplier(), defaultProjectRoot(),
            currentSessionId == null ? null : currentSessionId.get(),
            currentSessionId, CronSchedulerLock::sameProcess, true);
    }

    CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                  LongSupplier nowMillis, LoopPromptResolver promptResolver,
                  LoopWakeupManager loopWakeupManager) {
        this(isLoading, onFire, nowMillis, promptResolver, loopWakeupManager,
            () -> false, () -> false, null, _ -> true,
            defaultJitterConfigSupplier(), defaultProjectRoot(), UUID.randomUUID().toString(),
            () -> null, CronSchedulerLock::sameProcess, false);
    }

    CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                  LongSupplier nowMillis, LoopPromptResolver promptResolver,
                  LoopWakeupManager loopWakeupManager, BooleanSupplier isKilled,
                  BooleanSupplier assistantMode,
                  Consumer<List<CronStore.CronJob>> onMissed,
                  Predicate<CronStore.CronJob> filter) {
        this(isLoading, onFire, nowMillis, promptResolver, loopWakeupManager,
            isKilled, assistantMode, onMissed, filter,
            defaultJitterConfigSupplier(), defaultProjectRoot(), UUID.randomUUID().toString(),
            () -> null, CronSchedulerLock::sameProcess, false);
    }

    /** Headless/SDK constructor with an explicit project root and stable owner key. */
    public CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                         Path projectRoot, String lockIdentity,
                         BooleanSupplier isKilled) {
        this(isLoading, onFire, System::currentTimeMillis,
            LoopPromptResolver.global(), LoopWakeupManager.global(), isKilled,
            () -> true, null, _ -> true,
            () -> CronJitterConfig.DEFAULT, projectRoot, lockIdentity,
            () -> lockIdentity, CronSchedulerLock::sameProcess, false);
    }

    CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                  LongSupplier nowMillis, LoopPromptResolver promptResolver,
                  LoopWakeupManager loopWakeupManager, BooleanSupplier isKilled,
                  BooleanSupplier assistantMode,
                  Consumer<List<CronStore.CronJob>> onMissed,
                  Predicate<CronStore.CronJob> filter,
                  CronJitterConfig jitterConfig, Path projectRoot,
                  String lockIdentity) {
        this(isLoading, onFire, nowMillis, promptResolver, loopWakeupManager,
            isKilled, assistantMode, onMissed, filter,
            () -> jitterConfig == null ? CronJitterConfig.DEFAULT : jitterConfig,
            projectRoot, lockIdentity, () -> lockIdentity, CronSchedulerLock::sameProcess, false);
    }

    CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                  LongSupplier nowMillis, LoopPromptResolver promptResolver,
                  LoopWakeupManager loopWakeupManager, BooleanSupplier isKilled,
                  BooleanSupplier assistantMode,
                  Consumer<List<CronStore.CronJob>> onMissed,
                  Predicate<CronStore.CronJob> filter,
                  Supplier<CronJitterConfig> jitterConfigSupplier,
                  Path projectRoot, String lockIdentity,
                  Supplier<String> currentSessionId,
                  BiPredicate<Long, String> processAlive) {
        this(isLoading, onFire, nowMillis, promptResolver, loopWakeupManager,
            isKilled, assistantMode, onMissed, filter, jitterConfigSupplier,
            projectRoot, lockIdentity, currentSessionId, processAlive, false);
    }

    CronScheduler(BooleanSupplier isLoading, Consumer<FiredTask> onFire,
                  LongSupplier nowMillis, LoopPromptResolver promptResolver,
                  LoopWakeupManager loopWakeupManager, BooleanSupplier isKilled,
                  BooleanSupplier assistantMode,
                  Consumer<List<CronStore.CronJob>> onMissed,
                  Predicate<CronStore.CronJob> filter,
                  Supplier<CronJitterConfig> jitterConfigSupplier,
                  Path projectRoot, String lockIdentity,
                  Supplier<String> currentSessionId,
                  BiPredicate<Long, String> processAlive,
                  boolean deferUntilEnabled) {
        this.isLoading = isLoading == null ? () -> false : isLoading;
        this.onFire = onFire == null ? _ -> { } : onFire;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
        this.promptResolver = promptResolver == null ? LoopPromptResolver.passthrough() : promptResolver;
        this.loopWakeupManager = loopWakeupManager == null
            ? LoopWakeupManager.disabled() : loopWakeupManager;
        this.isKilled = isKilled == null ? () -> false : isKilled;
        this.assistantMode = assistantMode == null ? () -> false : assistantMode;
        this.onMissed = onMissed == null
            ? jobs -> {
                String notification = buildMissedTaskNotification(jobs);
                this.onFire.accept(new FiredTask(
                    "__missed__", notification, notification, "cron", false, null));
            }
            : onMissed;
        this.filter = filter == null ? _ -> true : filter;
        this.jitterConfigSupplier = jitterConfigSupplier == null
            ? () -> CronJitterConfig.DEFAULT : jitterConfigSupplier;
        this.currentSessionId = currentSessionId == null ? () -> null : currentSessionId;
        this.processAlive = processAlive == null
            ? CronSchedulerLock::sameProcess : processAlive;
        this.projectRoot = (projectRoot == null ? defaultProjectRoot() : projectRoot)
            .toAbsolutePath().normalize();
        this.schedulerLock = new CronSchedulerLock(this.projectRoot, lockIdentity);
        this.deferUntilEnabled = deferUntilEnabled;
    }

    /** Start polling, durable-file watching, and the project lease. */
    public synchronized void start() {
        if (started) return;
        started = true;
        CronStore.configureProjectRootForRuntime(projectRoot);
        if (deferUntilEnabled && !assistantMode.getAsBoolean()
                && !CronStore.scheduledTasksEnabled()
                && !CronStore.hasDurableTasksSync()) {
            startEnablePoll();
            return;
        }
        enableScheduler();
    }

    private synchronized void enableScheduler() {
        if (!started || executor != null) return;
        if (enablePollExecutor != null) {
            enablePollExecutor.shutdownNow();
            enablePollExecutor = null;
        }
        CronStore.loadDurable();
        refreshCreatorAffinity();
        owner = schedulerLock.tryAcquire();

        surfaceMissedTasks();
        if (!owner) startLockProbe();
        startWatcher();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("cron-scheduler");
            return t;
        });
        executor.scheduleAtFixedRate(this::safeCheck, CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startEnablePoll() {
        enablePollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("cron-scheduler-enable");
            return t;
        });
        enablePollExecutor.scheduleAtFixedRate(() -> {
            if (CronStore.scheduledTasksEnabled()) enableScheduler();
        }, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop polling, watcher/lease probes, and release the project lease. */
    public synchronized void stop() {
        started = false;
        if (enablePollExecutor != null) {
            enablePollExecutor.shutdownNow();
            enablePollExecutor = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (lockProbeExecutor != null) {
            lockProbeExecutor.shutdownNow();
            lockProbeExecutor = null;
        }
        if (watchThread != null) watchThread.interrupt();
        watchThread = null;
        if (fileReloadDebouncer != null) {
            fileReloadDebouncer.close();
            fileReloadDebouncer = null;
        }
        if (watchService != null) {
            try { watchService.close(); } catch (IOException _) { }
            watchService = null;
        }
        if (owner) schedulerLock.release();
        owner = true;
    }

    /** Explicit durable reload hook used by tests and composition roots. */
    public synchronized void reloadNow() {
        CronStore.loadDurable();
        refreshCreatorAffinity();
        nextFireAt.keySet().removeIf(id -> CronStore.list().stream()
            .noneMatch(task -> task.id().equals(id)));
    }

    /** Epoch millis of the next scheduled fire, or {@code null}. */
    public Long getNextFireTime() {
        return nextFireAt.values().stream().filter(value -> value < Long.MAX_VALUE)
            .min(Long::compareTo).orElse(null);
    }

    /** Core one-second check. Public for deterministic tests and adapters. */
    public synchronized void checkNow() {
        if (isKilled.getAsBoolean()) return;
        if (isLoading.getAsBoolean() && !assistantMode.getAsBoolean()) return;
        String sessionId = currentSessionId.get();
        if (!Objects.equals(sessionId, claimedSessionId)) refreshCreatorAffinity();
        long now = nowMillis.getAsLong();
        CronJitterConfig jitterConfig = currentJitterConfig();
        Set<String> seen = new HashSet<>();
        List<String> firedDurableRecurring = new ArrayList<>();
        for (CronStore.CronJob job : CronStore.list()) {
            if (!filter.test(job)) continue;
            if (!shouldProcess(job, owner, currentSessionId.get(), processAlive)) continue;
            seen.add(job.id());
            if (inFlight.contains(job.id())) continue;

            Long fireAt = nextFireAt.get(job.id());
            if (fireAt == null) {
                long anchor = job.recurring() && job.lastFiredAt() != null
                    ? job.lastFiredAt() : job.createdAt();
                fireAt = job.recurring()
                    ? jitteredNextCronRunMs(job.cron(), anchor, job.id(), jitterConfig)
                    : oneShotJitteredNextCronRunMs(job.cron(), anchor, job.id(), jitterConfig);
                if (fireAt == null) fireAt = Long.MAX_VALUE;
                nextFireAt.put(job.id(), fireAt);
            }
            if (now < fireAt || fireAt == Long.MAX_VALUE) continue;

            if (Strings.CS.equals("loop", job.kind())) loopWakeupManager.markLoopTaskFired(job.prompt());
            onFire.accept(new FiredTask(job.id(), job.prompt(),
                promptResolver.resolve(job.prompt()), job.kind(),
                job.recurring(), job.agentId()));

            boolean aged = job.recurring() && !job.permanent()
                && jitterConfig.recurringMaxAgeMs() > 0
                && now - job.createdAt() >= jitterConfig.recurringMaxAgeMs();
            if (job.recurring() && !aged) {
                Long next = jitteredNextCronRunMs(job.cron(), now, job.id(), jitterConfig);
                nextFireAt.put(job.id(), next == null ? Long.MAX_VALUE : next);
                if (job.durable()) firedDurableRecurring.add(job.id());
            } else {
                CronStore.remove(job.id());
                nextFireAt.remove(job.id());
            }
        }
        if (!firedDurableRecurring.isEmpty() && owner) {
            Set<String> firedIds = Set.copyOf(firedDurableRecurring);
            inFlight.addAll(firedIds);
            try {
                CronStore.markFired(firedDurableRecurring, now);
            } finally {
                inFlight.removeAll(firedIds);
            }
        }
        nextFireAt.keySet().removeIf(id -> !seen.contains(id));
    }

    private CronJitterConfig currentJitterConfig() {
        try {
            CronJitterConfig config = jitterConfigSupplier.get();
            return config == null ? CronJitterConfig.DEFAULT : config;
        } catch (RuntimeException _) {
            return CronJitterConfig.DEFAULT;
        }
    }

    private void refreshCreatorAffinity() {
        String sessionId = currentSessionId.get();
        claimedSessionId = sessionId;
        if (StringUtils.isBlank(sessionId)) return;
        CronStore.refreshCreatorProcess(sessionId, ProcessHandle.current().pid(),
            CronSchedulerLock.currentProcessStartToken());
    }


    static boolean shouldProcess(CronStore.CronJob job, boolean owner,
                                 String currentSessionId,
                                 BiPredicate<Long, String> processAlive) {
        if (job == null || !job.durable()) return true;
        if (StringUtils.isEmpty(job.createdBySessionId())) return owner;
        if (job.createdBySessionId().equals(currentSessionId)) return true;
        if (!owner) return false;
        if (job.createdByPid() == null) return true;
        return !processAlive.test(job.createdByPid(), job.createdByProcStart());
    }

    private static Supplier<CronJitterConfig> defaultJitterConfigSupplier() {
        return () -> CronFeatureGate.system().jitterConfig();
    }

    private void safeCheck() {
        // A failed enqueue must leave the task untouched for the next tick.
        // ScheduledExecutorService suppresses future runs when an exception
        // escapes, so contain it here after checkNow has aborted before the
        // reschedule/delete phase.
        try { checkNow(); } catch (RuntimeException _) { }
    }

    private void surfaceMissedTasks() {
        long now = nowMillis.getAsLong();
        List<CronStore.CronJob> missed = CronStore.list().stream()
            .filter(job -> filter.test(job) && !job.recurring())
            .filter(job -> shouldProcess(job, owner, currentSessionId.get(), processAlive))
            .filter(job -> !missedAsked.contains(job.id()))
            .filter(job -> {
                Long next = CronUtils.nextRunAfterMs(job.cron(), job.createdAt());
                return next != null && next < now;
            }).toList();
        if (missed.isEmpty()) return;
        missed.forEach(job -> {
            missedAsked.add(job.id());
            nextFireAt.put(job.id(), Long.MAX_VALUE);
        });
        onMissed.accept(missed);
        missed.forEach(job -> CronStore.remove(job.id()));
    }


    static String buildMissedTaskNotification(List<CronStore.CronJob> missed) {
        return buildMissedTaskNotification(missed, CREATED_AT_FORMATTER);
    }

    /**
     * Package-private testing seam: {@code created} is rendered with the given
     * formatter so a test can pin a fixed {@link java.time.ZoneId} /
     * {@link java.util.Locale} instead of the JVM default.
     */
    static String buildMissedTaskNotification(List<CronStore.CronJob> missed, DateTimeFormatter createdAtFormatter) {
        boolean plural = missed != null && missed.size() > 1;
        StringBuilder message = new StringBuilder()
            .append("The following one-shot scheduled task")
            .append(plural ? "s were" : " was")
            .append(" missed while Claude was not running. ")
            .append(plural ? "They have" : "It has")
            .append(" already been removed from .claude/scheduled_tasks.json.\n\n")
            .append("Do NOT execute ")
            .append(plural ? "these prompts" : "this prompt")
            .append(" yet. First use the AskUserQuestion tool to ask whether to run ")
            .append(plural ? "each one" : "it")
            .append(" now. Only execute if the user confirms.");
        if (missed == null) return message.toString();
        message.append("\n\n");
        for (int i = 0; i < missed.size(); i++) {
            CronStore.CronJob job = missed.get(i);
            if (i > 0) message.append("\n\n");
            String prompt = job.prompt() == null ? "" : job.prompt();
            int longest = 0;
            Matcher matcher = Pattern.compile("`+").matcher(prompt);
            while (matcher.find()) longest = Math.max(longest, matcher.end() - matcher.start());
            String fence = "`".repeat(Math.max(3, longest + 1));
            message.append("[").append(CronUtils.toHuman(job.cron()))
                .append(", created ").append(createdAtFormatter.format(Instant.ofEpochMilli(job.createdAt())))
                .append("]\n").append(fence).append("\n")
                .append(prompt).append("\n").append(fence);
        }
        return message.toString();
    }


    private static final DateTimeFormatter CREATED_AT_FORMATTER =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault());

    private void startLockProbe() {
        lockProbeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("cron-scheduler-lock-probe");
            return t;
        });
        lockProbeExecutor.scheduleAtFixedRate(this::probeLock,
            LOCK_PROBE_INTERVAL_MS, LOCK_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void probeLock() {
        if (!started || owner) return;
        if (!schedulerLock.tryAcquire()) return;
        owner = true;
        if (lockProbeExecutor != null) {
            lockProbeExecutor.shutdown();
            lockProbeExecutor = null;
        }
    }

    void probeLockNowForTest() { probeLock(); }

    synchronized boolean lockProbeRunningForTest() { return lockProbeExecutor != null; }

    private void startWatcher() {
        Path directory = projectRoot.resolve(".claude");
        try {
            Files.createDirectories(directory);
            fileReloadDebouncer = new CronFileReloadDebouncer(
                Duration.ofMillis(FILE_STABILITY_MS), this::reloadNow);
            watchService = FileSystems.getDefault().newWatchService();
            directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            WatchService service = watchService;
            watchThread = Thread.startVirtualThread(() -> {
                while (started) {
                    try {
                        WatchKey key = service.take();
                        boolean changed = false;
                        boolean deleted = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changedPath = (Path) event.context();
                            if (Strings.CS.equals("scheduled_tasks.json", changedPath.toString())) {
                                if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) deleted = true;
                                else changed = true;
                            }
                        }
                        key.reset();
                        if (deleted) reloadNow();
                        else if (changed && fileReloadDebouncer != null) {
                            fileReloadDebouncer.changed();
                        }
                    } catch (InterruptedException | ClosedWatchServiceException _) {
                        return;
                    }
                }
            });
            watchThread.setName("cron-scheduler-watch");
        } catch (IOException _) { }
    }

    static Long jitteredNextCronRunMs(String cron, long fromMs, String taskId,
                                      CronJitterConfig config) {
        Long first = CronUtils.nextRunAfterMs(cron, fromMs);
        if (first == null) return null;
        Long second = CronUtils.nextRunAfterMs(cron, first);
        if (second == null) return first;
        long interval = second - first;
        long cacheLeadMs = config.cacheLeadMs();
        if (EVERY_N_MINUTES.matcher(cron).matches()
                && cacheLeadMs > 0L && cacheLeadMs < interval
                && interval >= FIVE_MINUTES_MS
                && interval - cacheLeadMs < FIVE_MINUTES_MS) {
            return fromMs + interval - cacheLeadMs;
        }
        long jitter = Math.min((long) (jitterFraction(taskId)
            * config.recurringFrac() * interval), config.recurringCapMs());
        return first + jitter;
    }

    static Long oneShotJitteredNextCronRunMs(String cron, long fromMs, String taskId,
                                             CronJitterConfig config) {
        Long first = CronUtils.nextRunAfterMs(cron, fromMs);
        if (first == null) return null;
        int minute = Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).getMinute();
        if (minute % config.oneShotMinuteMod() != 0) return first;
        long lead = config.oneShotFloorMs() + (long) (jitterFraction(taskId)
            * (config.oneShotMaxMs() - config.oneShotFloorMs()));
        return Math.max(first - lead, fromMs);
    }

    private static double jitterFraction(String taskId) {
        if (StringUtils.isEmpty(taskId)) return 0d;
        try {
            String prefix = taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
            long value = Long.parseUnsignedLong(prefix, 16);
            return value / 4_294_967_296d;
        } catch (NumberFormatException _) {
            return 0d;
        }
    }

    private static Path defaultProjectRoot() {
        Path durable = CronStore.durablePath();
        return durable.getParent().getParent();
    }

    /** Scheduler callback payload; raw sentinel and resolved prompt stay distinct. */
    public record FiredTask(String id, String prompt, String resolvedPrompt, String kind,
                            boolean recurring, String agentId) { }
}
