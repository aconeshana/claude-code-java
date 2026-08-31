package com.claudecode.session.stats;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.session.stats.ClaudeCodeStats.DailyActivity;
import com.claudecode.session.stats.ClaudeCodeStats.DailyModelTokens;
import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;
import com.claudecode.session.stats.ClaudeCodeStats.SessionStats;
import com.claudecode.session.stats.ClaudeCodeStats.StreakInfo;
import com.claudecode.session.stats.StatsCacheStore.PersistedStatsCache;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public final class StatsAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(StatsAggregator.class);


    private static final int BATCH_SIZE = 20;

    private static final long START_DATE_PEEK_THRESHOLD = 65_536;
    private static final long ESTIMATED_READER_BUDGET_BYTES = 8L * 1024 * 1024;
    private static final int DEFAULT_FILE_SCAN_CACHE_ENTRIES = 8_192;

    private final SessionFileEnumerator enumerator;
    private final StatsCacheStore cacheStore;
    private final ZoneId zone;
    private final int scanConcurrency;
    private final int fileScanCacheLimit;
    private final Consumer<Path> scanStarted;
    private final TranscriptStatsScanner transcriptScanner;
    private final AtomicInteger scanCount = new AtomicInteger();
    @Explanation("Caches compact transcript aggregates by size and mtime across /stats views.")
    private final Map<Path, CachedFileScan> fileScanCache =
        new LinkedHashMap<>(256, 0.75f, true);

    public StatsAggregator() {
        this(new SessionFileEnumerator(), new StatsCacheStore(), ZoneId.systemDefault());
    }

    public StatsAggregator(SessionFileEnumerator enumerator, StatsCacheStore cacheStore, ZoneId zone) {
        this(enumerator, cacheStore, zone, defaultScanConcurrency(),
            DEFAULT_FILE_SCAN_CACHE_ENTRIES, _ -> {});
    }

    StatsAggregator(SessionFileEnumerator enumerator, StatsCacheStore cacheStore, ZoneId zone,
                    int scanConcurrency, int fileScanCacheLimit, Consumer<Path> scanStarted) {
        if (scanConcurrency <= 0) throw new IllegalArgumentException("scanConcurrency must be positive");
        if (fileScanCacheLimit <= 0) {
            throw new IllegalArgumentException("fileScanCacheLimit must be positive");
        }
        this.enumerator = enumerator;
        this.cacheStore = cacheStore;
        this.zone = zone;
        this.scanConcurrency = Math.min(BATCH_SIZE, scanConcurrency);
        this.fileScanCacheLimit = fileScanCacheLimit;
        this.scanStarted = scanStarted;
        this.transcriptScanner = new TranscriptStatsScanner();
    }


    @Explanation("Caps the 20-file stats batch according to the JVM heap budget.")
    static int defaultScanConcurrency() {
        long readerBudget = Math.max(ESTIMATED_READER_BUDGET_BYTES,
            Runtime.getRuntime().maxMemory() / 4);
        long readers = Math.max(1, readerBudget / ESTIMATED_READER_BUDGET_BYTES);
        return (int) Math.min(BATCH_SIZE, readers);
    }


    public enum StatsDateRange { SEVEN_DAYS, THIRTY_DAYS, ALL }


    public record ProcessedStats(
        List<DailyActivity> dailyActivity,
        List<DailyModelTokens> dailyModelTokens,
        Map<String, ModelUsage> modelUsage,
        List<SessionStats> sessionStats,
        Map<String, Long> hourCounts,
        long totalMessages,
        long totalSpeculationTimeSavedMs
    ) {
        boolean isEmpty() {
            return sessionStats.isEmpty() && dailyActivity.isEmpty();
        }
    }

    // ── public entry points ─────────────────────────────────────────────────


    public ClaudeCodeStats aggregateAll() {
        List<Path> files = enumerator.listAllSessionFiles();
        if (files.isEmpty()) return ClaudeCodeStats.empty();

        PersistedStatsCache updatedCache = StatsCacheStore.withLock(() -> {
            PersistedStatsCache cache = cacheStore.load();
            String yesterday = StatsDates.yesterday();

            if (cache.lastComputedDate() == null) {
                LOG.debug("Stats cache empty, processing all historical data");
                ProcessedStats historical = processSessionFiles(files, null, yesterday);
                if (!historical.isEmpty()) {
                    PersistedStatsCache merged = StatsCacheStore.merge(cache, historical, yesterday);
                    cacheStore.save(merged);
                    return merged;
                }
                return cache;
            }
            if (StatsDates.isBefore(cache.lastComputedDate(), yesterday)) {
                String nextDay = StatsDates.nextDay(cache.lastComputedDate());
                LOG.debug("Stats cache stale ({}), processing {} to {}", cache.lastComputedDate(), nextDay, yesterday);
                ProcessedStats newStats = processSessionFiles(files, nextDay, yesterday);
                PersistedStatsCache merged = newStats.isEmpty()
                    ? withLastComputedDate(cache, yesterday)
                    : StatsCacheStore.merge(cache, newStats, yesterday);
                cacheStore.save(merged);
                return merged;
            }
            return cache;
        });

        String today = StatsDates.today();
        ProcessedStats todayStats = processSessionFiles(files, today, today);
        return cacheToStats(updatedCache, todayStats);
    }


    public ClaudeCodeStats aggregateForRange(StatsDateRange range) {
        if (range == StatsDateRange.ALL) return aggregateAll();

        List<Path> files = enumerator.listAllSessionFiles();
        if (files.isEmpty()) return ClaudeCodeStats.empty();

        int daysBack = range == StatsDateRange.SEVEN_DAYS ? 7 : 30;

        String fromDate = StatsDates.toDateString(Instant.now().minus(daysBack - 1L, ChronoUnit.DAYS));
        ProcessedStats stats = processSessionFiles(files, fromDate, null);
        return processedToStats(stats);
    }



    /** One file's extracted contribution — merged sequentially in file order. */
    private record FileScan(
        Path file, boolean skipped, boolean subagent, String sessionId,
        long speculationMs, long mainCount,
        String firstTimestamp, String lastTimestamp,
        long toolUseCount, Map<String, ModelUsage> usageByModel, long totalTokens
    ) {
        static FileScan skippedFile(Path file) {
            return new FileScan(file, true, false, null, 0, 0, null, null, 0, Map.of(), 0);
        }
    }

    ProcessedStats processSessionFiles(List<Path> sessionFiles, String fromDate, String toDate) {
        Map<String, DailyActivity> dailyActivity = new TreeMap<>();
        Map<String, Map<String, Long>> dailyModelTokens = new TreeMap<>();
        Map<String, ModelUsage> modelUsage = new LinkedHashMap<>();
        List<SessionStats> sessions = new ArrayList<>();
        Map<String, Long> hourCounts = new HashMap<>();
        long totalMessages = 0;
        long speculationMs = 0;

        pruneFileScanCache(sessionFiles);
        try (ExecutorService pool = Executors.newFixedThreadPool(
                scanConcurrency, Thread.ofVirtual().name("stats-file-", 0).factory())) {
            for (int i = 0; i < sessionFiles.size(); i += BATCH_SIZE) {
                List<Path> batch = sessionFiles.subList(i, Math.min(i + BATCH_SIZE, sessionFiles.size()));
                List<Future<FileScan>> futures = new ArrayList<>(batch.size());
                for (Path file : batch) {
                    futures.add(pool.submit(() -> scanFile(file, fromDate)));
                }
                for (Future<FileScan> future : futures) {
                    FileScan scan;
                    try {
                        scan = future.get();
                    } catch (Exception _) {
                        continue;
                    }
                    if (scan.skipped()) continue;

                    // Speculation time counts regardless of the session-date filter

                    speculationMs += scan.speculationMs();
                    if (scan.mainCount() == 0) continue;

                    Instant first = StatsDates.parseFlexible(scan.firstTimestamp());
                    Instant last = StatsDates.parseFlexible(scan.lastTimestamp());
                    if (first == null || last == null) {
                        LOG.debug("Skipping session with invalid timestamp: {}", scan.file());
                        continue;
                    }
                    String dateKey = StatsDates.toDateString(first);
                    if (fromDate != null && StatsDates.isBefore(dateKey, fromDate)) continue;
                    if (toDate != null && StatsDates.isBefore(toDate, dateKey)) continue;

                    DailyActivity existing = dailyActivity.getOrDefault(dateKey,
                        new DailyActivity(dateKey, 0, 0, 0));

                    if (!scan.subagent()) {
                        long duration = last.toEpochMilli() - first.toEpochMilli();
                        sessions.add(new SessionStats(scan.sessionId(), duration,
                            scan.mainCount(), scan.firstTimestamp()));
                        totalMessages += scan.mainCount();
                        existing = new DailyActivity(dateKey,
                            existing.messageCount() + scan.mainCount(),
                            existing.sessionCount() + 1,
                            existing.toolCallCount());
                        String hour = Integer.toString(first.atZone(zone).getHour());
                        hourCounts.merge(hour, 1L, Long::sum);
                    }

                    // Subagent files only contribute to an already-created day row

                    boolean dayExists = dailyActivity.containsKey(dateKey);
                    if (!scan.subagent() || dayExists) {
                        dailyActivity.put(dateKey, existing);
                    }

                    // Use the already-fetched, provably-non-null `existing` row instead of
                    // Map.compute's @Nullable old-value, which IntelliJ flags as a possible NPE.
                    if (scan.toolUseCount() > 0 && dailyActivity.containsKey(dateKey)) {
                        dailyActivity.put(dateKey, new DailyActivity(dateKey,
                            existing.messageCount(), existing.sessionCount(),
                            existing.toolCallCount() + scan.toolUseCount()));
                    }

                    scan.usageByModel().forEach((model, usage) ->
                        modelUsage.merge(model, usage, ModelUsage::plus));
                    if (scan.totalTokens() > 0) {
                        Map<String, Long> dayTokens =
                            dailyModelTokens.computeIfAbsent(dateKey, _ -> new LinkedHashMap<>());
                        scan.usageByModel().forEach((model, usage) -> {
                            long tokens = usage.inputTokens() + usage.outputTokens();
                            if (tokens > 0) dayTokens.merge(model, tokens, Long::sum);
                        });
                    }
                }
            }
        }

        return new ProcessedStats(
            List.copyOf(dailyActivity.values()),
            StatsCacheStore.toSortedDailyTokens(dailyModelTokens),
            modelUsage, sessions, hourCounts, totalMessages, speculationMs);
    }

    /** Streams one transcript and extracts its contribution. */
    private FileScan scanFile(Path file, String fromDate) {

        BasicFileAttributes attributes = null;
        if (fromDate != null) {
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
                String modifiedDate = StatsDates.toDateString(attributes.lastModifiedTime().toInstant());
                if (StatsDates.isBefore(modifiedDate, fromDate)) return FileScan.skippedFile(file);
            } catch (IOException _) {

            }
            if (attributes != null && attributes.size() > START_DATE_PEEK_THRESHOLD) {
                String startDate = SessionFileEnumerator.readSessionStartDate(file);
                if (startDate != null && StatsDates.isBefore(startDate, fromDate)) {
                    return FileScan.skippedFile(file);
                }
            }
        }

        boolean subagent = SessionFileEnumerator.isSubagentFile(file);
        String fileName = file.getFileName().toString();
        String sessionId = Strings.CS.endsWith(fileName, ".jsonl")
            ? fileName.substring(0, fileName.length() - ".jsonl".length()) : fileName;

        if (attributes == null) {
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (IOException _) {
                // Read without caching when the fingerprint cannot be obtained.
            }
        }

        Path cacheKey = file.toAbsolutePath().normalize();
        FileFingerprint fingerprint = attributes != null
            ? new FileFingerprint(attributes.size(), attributes.lastModifiedTime().toMillis()) : null;
        FileScan cached = fingerprint != null ? cachedFileScan(cacheKey, fingerprint) : null;
        if (cached != null) return cached;

        try {
            scanStarted.accept(file);
            scanCount.incrementAndGet();
            TranscriptStatsScanner.ScanResult result = transcriptScanner.scan(file, subagent);
            FileScan scanned = new FileScan(file, false, subagent, sessionId,
                result.speculationMs(), result.mainCount(), result.firstTimestamp(),
                result.lastTimestamp(), result.toolUseCount(), result.usageByModel(),
                result.totalTokens());
            if (fingerprint != null) cacheFileScan(cacheKey, fingerprint, scanned);
            return scanned;
        } catch (IOException e) {
            LOG.debug("Failed to read session file {}: {}", file, e.getMessage());
            return FileScan.skippedFile(file);
        }
    }

    private FileScan cachedFileScan(Path path, FileFingerprint fingerprint) {
        synchronized (fileScanCache) {
            CachedFileScan cached = fileScanCache.get(path);
            if (cached != null && cached.fingerprint().equals(fingerprint)) return cached.scan();
            if (cached != null) fileScanCache.remove(path);
            return null;
        }
    }

    private void cacheFileScan(Path path, FileFingerprint fingerprint, FileScan scan) {
        synchronized (fileScanCache) {
            fileScanCache.put(path, new CachedFileScan(fingerprint, scan));
            while (fileScanCache.size() > fileScanCacheLimit) {
                Path eldest = fileScanCache.keySet().iterator().next();
                fileScanCache.remove(eldest);
            }
        }
    }

    private void pruneFileScanCache(List<Path> currentFiles) {
        if (fileScanCache.isEmpty()) return;
        Set<Path> live = new HashSet<>(currentFiles.size());
        for (Path file : currentFiles) live.add(file.toAbsolutePath().normalize());
        synchronized (fileScanCache) {
            fileScanCache.keySet().removeIf(path -> !live.contains(path));
        }
    }

    int scanCountForTests() { return scanCount.get(); }

    int fileScanCacheSizeForTests() {
        synchronized (fileScanCache) {
            return fileScanCache.size();
        }
    }

    private record FileFingerprint(long size, long modifiedMillis) {}

    private record CachedFileScan(FileFingerprint fingerprint, FileScan scan) {}




    ClaudeCodeStats cacheToStats(PersistedStatsCache cache, ProcessedStats todayStats) {
        Map<String, DailyActivity> activityByDate = new TreeMap<>();
        for (DailyActivity day : cache.dailyActivity()) activityByDate.put(day.date(), day);
        for (DailyActivity day : todayStats.dailyActivity()) {
            activityByDate.merge(day.date(), day, DailyActivity::plus);
        }

        Map<String, Map<String, Long>> tokensByDate = new TreeMap<>();
        for (DailyModelTokens day : cache.dailyModelTokens()) {
            tokensByDate.put(day.date(), new LinkedHashMap<>(day.tokensByModel()));
        }
        for (DailyModelTokens day : todayStats.dailyModelTokens()) {
            Map<String, Long> row = tokensByDate.computeIfAbsent(day.date(), _ -> new LinkedHashMap<>());
            day.tokensByModel().forEach((model, tokens) -> row.merge(model, tokens, Long::sum));
        }

        Map<String, ModelUsage> modelUsage = new LinkedHashMap<>(cache.modelUsage());
        todayStats.modelUsage().forEach((model, usage) -> modelUsage.merge(model, usage, ModelUsage::plus));

        Map<String, Long> hourCounts = new HashMap<>(cache.hourCounts());
        todayStats.hourCounts().forEach((hour, count) -> hourCounts.merge(hour, count, Long::sum));

        List<DailyActivity> dailyActivity = List.copyOf(activityByDate.values());
        StreakInfo streaks = calculateStreaks(dailyActivity);

        long totalSessions = cache.totalSessions() + todayStats.sessionStats().size();
        long totalMessages = cache.totalMessages() + todayStats.totalMessages();

        SessionStats longestSession = cache.longestSession();
        for (SessionStats session : todayStats.sessionStats()) {
            if (longestSession == null || session.duration() > longestSession.duration()) {
                longestSession = session;
            }
        }

        String firstSessionDate = cache.firstSessionDate();
        String lastSessionDate = null;
        for (SessionStats session : todayStats.sessionStats()) {
            if (firstSessionDate == null || session.timestamp().compareTo(firstSessionDate) < 0) {
                firstSessionDate = session.timestamp();
            }
            if (lastSessionDate == null || session.timestamp().compareTo(lastSessionDate) > 0) {
                lastSessionDate = session.timestamp();
            }
        }
        if (lastSessionDate == null && !dailyActivity.isEmpty()) {
            lastSessionDate = dailyActivity.getLast().date();
        }

        return new ClaudeCodeStats(
            totalSessions, totalMessages,
            totalDaysBetween(firstSessionDate, lastSessionDate),
            activityByDate.size(), streaks, dailyActivity,
            StatsCacheStore.toSortedDailyTokens(tokensByDate),
            longestSession, modelUsage, firstSessionDate, lastSessionDate,
            peakActivityDay(dailyActivity), peakActivityHour(hourCounts),
            cache.totalSpeculationTimeSavedMs() + todayStats.totalSpeculationTimeSavedMs());
    }


    ClaudeCodeStats processedToStats(ProcessedStats stats) {
        List<DailyActivity> dailyActivity = stats.dailyActivity();  // already date-sorted
        StreakInfo streaks = calculateStreaks(dailyActivity);

        SessionStats longestSession = null;
        String firstSessionDate = null;
        String lastSessionDate = null;
        for (SessionStats session : stats.sessionStats()) {
            if (longestSession == null || session.duration() > longestSession.duration()) {
                longestSession = session;
            }
            if (firstSessionDate == null || session.timestamp().compareTo(firstSessionDate) < 0) {
                firstSessionDate = session.timestamp();
            }
            if (lastSessionDate == null || session.timestamp().compareTo(lastSessionDate) > 0) {
                lastSessionDate = session.timestamp();
            }
        }

        return new ClaudeCodeStats(
            stats.sessionStats().size(), stats.totalMessages(),
            totalDaysBetween(firstSessionDate, lastSessionDate),
            dailyActivity.size(), streaks, dailyActivity,
            stats.dailyModelTokens(), longestSession, stats.modelUsage(),
            firstSessionDate, lastSessionDate,
            peakActivityDay(dailyActivity), peakActivityHour(stats.hourCounts()),
            stats.totalSpeculationTimeSavedMs());
    }


    StreakInfo calculateStreaks(List<DailyActivity> dailyActivity) {
        if (dailyActivity.isEmpty()) return StreakInfo.EMPTY;

        TreeSet<String> activeDates = new TreeSet<>();
        for (DailyActivity day : dailyActivity) activeDates.add(day.date());

        long currentStreak = 0;
        String currentStreakStart = null;
        LocalDate cursor = LocalDate.now(zone);
        while (true) {
            String dateStr = StatsDates.localMidnightUtcDate(cursor, zone);
            if (!activeDates.contains(dateStr)) break;
            currentStreak++;
            currentStreakStart = dateStr;
            cursor = cursor.minusDays(1);
        }

        long longestStreak = 0;
        String longestStreakStart = null;
        String longestStreakEnd = null;
        List<String> sorted = new ArrayList<>(activeDates);
        long tempStreak = 1;
        String tempStart = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            long dayDiff = ChronoUnit.DAYS.between(
                LocalDate.parse(sorted.get(i - 1)), LocalDate.parse(sorted.get(i)));
            if (dayDiff == 1) {
                tempStreak++;
            } else {
                if (tempStreak > longestStreak) {
                    longestStreak = tempStreak;
                    longestStreakStart = tempStart;
                    longestStreakEnd = sorted.get(i - 1);
                }
                tempStreak = 1;
                tempStart = sorted.get(i);
            }
        }
        if (tempStreak > longestStreak) {
            longestStreak = tempStreak;
            longestStreakStart = tempStart;
            longestStreakEnd = sorted.getLast();
        }

        return new StreakInfo(currentStreak, longestStreak,
            currentStreakStart, longestStreakStart, longestStreakEnd);
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static PersistedStatsCache withLastComputedDate(PersistedStatsCache c, String date) {
        return new PersistedStatsCache(c.version(), date, c.dailyActivity(), c.dailyModelTokens(),
            c.modelUsage(), c.totalSessions(), c.totalMessages(), c.longestSession(),
            c.firstSessionDate(), c.hourCounts(), c.totalSpeculationTimeSavedMs());
    }


    private static long totalDaysBetween(String firstSessionDate, String lastSessionDate) {
        Instant first = StatsDates.parseFlexible(firstSessionDate);
        Instant last = StatsDates.parseFlexible(lastSessionDate);
        if (first == null || last == null) return 0;
        long diffMs = last.toEpochMilli() - first.toEpochMilli();
        return (long) Math.ceil(diffMs / 86_400_000.0) + 1;
    }

    private static String peakActivityDay(List<DailyActivity> dailyActivity) {
        DailyActivity max = null;
        for (DailyActivity day : dailyActivity) {
            if (max == null || day.messageCount() > max.messageCount()) max = day;
        }
        return max != null ? max.date() : null;
    }

    private static Integer peakActivityHour(Map<String, Long> hourCounts) {
        if (hourCounts.isEmpty()) return null;
        List<Map.Entry<String, Long>> entries = new ArrayList<>(hourCounts.entrySet());
        entries.sort(Comparator.comparingInt(e -> Integer.parseInt(e.getKey())));
        Map.Entry<String, Long> max = entries.getFirst();
        for (Map.Entry<String, Long> e : entries) {
            if (e.getValue() > max.getValue()) max = e;
        }
        return Integer.parseInt(max.getKey());
    }
}
