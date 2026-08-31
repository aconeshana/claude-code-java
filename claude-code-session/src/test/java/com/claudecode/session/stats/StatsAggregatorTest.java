package com.claudecode.session.stats;

import com.claudecode.session.stats.ClaudeCodeStats.DailyActivity;
import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;
import com.claudecode.session.stats.ClaudeCodeStats.StreakInfo;
import com.claudecode.session.stats.StatsAggregator.ProcessedStats;
import com.claudecode.session.stats.StatsAggregator.StatsDateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link StatsAggregator}'s scan + aggregation math against
 * synthetic transcripts laid out in the supported
 * {@code ~/.claude/projects} tree — sidechain filtering, subagent
 * token-only contribution, synthetic-model skip, date bucketing (UTC),
 * streaks, and the cache-backed {@code aggregateAll} flow.
 */
class StatsAggregatorTest {

    @TempDir Path tmp;

    private Path projectsDir;
    private StatsAggregator aggregator;
    private StatsCacheStore cacheStore;

    @BeforeEach
    void setUp() throws IOException {
        projectsDir = tmp.resolve("projects");
        Files.createDirectories(projectsDir.resolve("-Users-x-proj"));
        cacheStore = new StatsCacheStore(tmp.resolve("stats-cache.json"));
        aggregator = new StatsAggregator(
            new SessionFileEnumerator(projectsDir), cacheStore, ZoneOffset.UTC);
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private Path projectDir() { return projectsDir.resolve("-Users-x-proj"); }

    private static String iso(Instant t) {
        return DateTimeFormatter.ISO_INSTANT.format(t);
    }

    private static String userEntry(String ts, boolean sidechain) {
        return "{\"type\":\"user\",\"timestamp\":\"" + ts + "\",\"isSidechain\":" + sidechain
            + ",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}";
    }

    private static String assistantEntry(String ts, String model, long in, long out, int toolUses) {
        StringBuilder content = new StringBuilder("[");
        for (int i = 0; i < toolUses; i++) {
            if (i > 0) content.append(',');
            content.append("{\"type\":\"tool_use\",\"id\":\"t").append(i).append("\",\"name\":\"Bash\",\"input\":{}}");
        }
        content.append("]");
        return "{\"type\":\"assistant\",\"timestamp\":\"" + ts + "\",\"isSidechain\":false,"
            + "\"message\":{\"role\":\"assistant\",\"model\":\"" + model + "\",\"content\":" + content
            + ",\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out
            + ",\"cache_read_input_tokens\":5,\"cache_creation_input_tokens\":7}}}";
    }

    private Path writeSession(String sessionId, String... lines) throws IOException {
        Path file = projectDir().resolve(sessionId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private Path writeSubagent(String parentSessionId, String agentId, String... lines) throws IOException {
        Path dir = projectDir().resolve(parentSessionId).resolve("subagents");
        Files.createDirectories(dir);
        Path file = dir.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private ProcessedStats scanAll() {
        return aggregator.processSessionFiles(
            new SessionFileEnumerator(projectsDir).listAllSessionFiles(), null, null);
    }

    // ── processSessionFiles ─────────────────────────────────────────────────

    @Test
    void aggregatesBasicSession() throws IOException {
        writeSession("s1",
            "{\"type\":\"mode\",\"mode\":\"normal\"}",                       // non-transcript prefix
            userEntry("2026-07-01T02:00:00.000Z", false),
            assistantEntry("2026-07-01T02:00:10.000Z", "claude-opus-4-8", 100, 50, 2));

        ProcessedStats stats = scanAll();

        assertEquals(1, stats.sessionStats().size());
        assertEquals(2, stats.totalMessages());
        DailyActivity day = stats.dailyActivity().getFirst();
        assertEquals("2026-07-01", day.date());
        assertEquals(2, day.messageCount());
        assertEquals(1, day.sessionCount());
        assertEquals(2, day.toolCallCount());

        ModelUsage usage = stats.modelUsage().get("claude-opus-4-8");
        assertEquals(100, usage.inputTokens());
        assertEquals(50, usage.outputTokens());
        assertEquals(5, usage.cacheReadInputTokens());
        assertEquals(7, usage.cacheCreationInputTokens());
        assertEquals(150L, stats.dailyModelTokens().getFirst().tokensByModel().get("claude-opus-4-8"));
        // duration = 10s
        assertEquals(10_000, stats.sessionStats().getFirst().duration());
        assertEquals(Long.valueOf(1L), stats.hourCounts().get("2"));
    }

    @Test
    void sidechainMessagesExcludedFromMainTranscripts() throws IOException {
        writeSession("s1",
            userEntry("2026-07-01T02:00:00.000Z", false),
            userEntry("2026-07-01T02:00:05.000Z", true),     // sidechain — dropped
            assistantEntry("2026-07-01T02:00:10.000Z", "m", 10, 5, 0));

        ProcessedStats stats = scanAll();
        assertEquals(2, stats.totalMessages(), "sidechain row must not count");
    }

    @Test
    void subagentFilesContributeTokensButNotSessions() throws IOException {
        writeSession("s1",
            userEntry("2026-07-01T02:00:00.000Z", false),
            assistantEntry("2026-07-01T02:00:10.000Z", "m", 10, 5, 1));
        // Subagent same UTC day: all rows sidechain-marked, still counted wholesale.
        writeSubagent("s1", "abc123",
            "{\"type\":\"user\",\"timestamp\":\"2026-07-01T03:00:00.000Z\",\"isSidechain\":true,\"message\":{}}",
            "{\"type\":\"assistant\",\"timestamp\":\"2026-07-01T03:00:05.000Z\",\"isSidechain\":true,"
                + "\"message\":{\"model\":\"m\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"Read\",\"input\":{}}],"
                + "\"usage\":{\"input_tokens\":30,\"output_tokens\":20}}}");

        ProcessedStats stats = scanAll();

        assertEquals(1, stats.sessionStats().size(), "subagent file is not a session");
        assertEquals(2, stats.totalMessages(), "subagent messages don't add to totals");
        DailyActivity day = stats.dailyActivity().getFirst();
        assertEquals(1, day.sessionCount());
        assertEquals(2, day.toolCallCount(), "subagent tool calls land on the parent's day row");
        ModelUsage usage = stats.modelUsage().get("m");
        assertEquals(40, usage.inputTokens(), "10 main + 30 subagent");
        assertEquals(25, usage.outputTokens());
        assertEquals(65L, stats.dailyModelTokens().getFirst().tokensByModel().get("m"));
    }

    @Test
    void syntheticModelSkipped() throws IOException {
        writeSession("s1",
            userEntry("2026-07-01T02:00:00.000Z", false),
            assistantEntry("2026-07-01T02:00:10.000Z", "<synthetic>", 999, 999, 0));

        ProcessedStats stats = scanAll();
        assertTrue(stats.modelUsage().isEmpty(), "synthetic model must not appear");
        assertTrue(stats.dailyModelTokens().isEmpty());
        assertEquals(2, stats.totalMessages(), "message still counts, just not its usage");
    }

    @Test
    void invalidTimestampSessionSkipped() throws IOException {
        writeSession("s1",
            "{\"type\":\"user\",\"isSidechain\":false,\"message\":{}}",   // no timestamp
            assistantEntry("2026-07-01T02:00:10.000Z", "m", 10, 5, 0));

        ProcessedStats stats = scanAll();
        assertTrue(stats.sessionStats().isEmpty(), "first main message without timestamp → session skipped");
        assertTrue(stats.dailyActivity().isEmpty());
    }

    @Test
    void dateRangeFilterAppliesAndSpeculationSurvivesIt() throws IOException {
        writeSession("old",
            "{\"type\":\"speculation-accept\",\"timeSavedMs\":1234}",
            userEntry("2026-06-01T02:00:00.000Z", false),
            assistantEntry("2026-06-01T02:00:10.000Z", "m", 10, 5, 0));
        writeSession("recent",
            userEntry("2026-07-05T02:00:00.000Z", false),
            assistantEntry("2026-07-05T02:00:10.000Z", "m", 10, 5, 0));

        ProcessedStats stats = aggregator.processSessionFiles(
            new SessionFileEnumerator(projectsDir).listAllSessionFiles(), "2026-07-01", null);

        assertEquals(1, stats.sessionStats().size(), "old session filtered out by fromDate");
        assertEquals("recent", stats.sessionStats().getFirst().sessionId());

        // but the OLD file gets mtime-skipped only when its mtime is old; here the
        // file was just written (today's mtime) so it IS read and speculation counts.
        assertEquals(1234, stats.totalSpeculationTimeSavedMs());
    }

    @Test
    void utcDateBucketing() throws IOException {
        // 23:30Z July 1 and 00:30Z July 2 land on different UTC days.
        writeSession("a", userEntry("2026-07-01T23:30:00.000Z", false));
        writeSession("b", userEntry("2026-07-02T00:30:00.000Z", false));

        ProcessedStats stats = scanAll();
        assertEquals(List.of("2026-07-01", "2026-07-02"),
            stats.dailyActivity().stream().map(DailyActivity::date).toList());
    }

    // ── streaks ──────────────────────────────────────────────────────────────

    @Test
    void longestStreakFound() {
        List<DailyActivity> days = List.of(
            new DailyActivity("2026-06-01", 1, 1, 0),
            new DailyActivity("2026-06-02", 1, 1, 0),
            new DailyActivity("2026-06-03", 1, 1, 0),
            new DailyActivity("2026-06-10", 1, 1, 0));
        StreakInfo streaks = aggregator.calculateStreaks(days);
        assertEquals(3, streaks.longestStreak());
        assertEquals("2026-06-01", streaks.longestStreakStart());
        assertEquals("2026-06-03", streaks.longestStreakEnd());
    }

    @Test
    void currentStreakWalksBackFromToday() {
        // Build activity for "today" (UTC, since aggregator zone is UTC) and 2 days back.
        String today = StatsDates.today();
        String d1 = LocalDate.parse(today).minusDays(1).toString();
        String d2 = LocalDate.parse(today).minusDays(2).toString();
        List<DailyActivity> days = List.of(
            new DailyActivity(d2, 1, 1, 0),
            new DailyActivity(d1, 1, 1, 0),
            new DailyActivity(today, 1, 1, 0));
        StreakInfo streaks = aggregator.calculateStreaks(days);
        assertEquals(3, streaks.currentStreak());
        assertEquals(d2, streaks.currentStreakStart());
    }

    @Test
    void emptyStreaks() {
        assertEquals(StreakInfo.EMPTY, aggregator.calculateStreaks(List.of()));
    }

    // ── aggregateAll (cache-backed) ─────────────────────────────────────────

    @Test
    void aggregateAll_buildsCacheThenAddsTodayLive() throws IOException {
        String yesterdayTs = iso(Instant.now().minus(1, ChronoUnit.DAYS));
        String todayTs = iso(Instant.now());
        writeSession("hist", userEntry(yesterdayTs, false),
            assistantEntry(yesterdayTs, "m", 100, 50, 1));
        writeSession("live", userEntry(todayTs, false),
            assistantEntry(todayTs, "m", 10, 5, 0));

        ClaudeCodeStats stats = aggregator.aggregateAll();

        assertEquals(2, stats.totalSessions());
        assertEquals(4, stats.totalMessages());
        assertEquals(2, stats.activeDays());
        ModelUsage usage = stats.modelUsage().get("m");
        assertEquals(110, usage.inputTokens());

        // Cache persisted historical (yesterday) but NOT today.
        StatsCacheStore.PersistedStatsCache cache = cacheStore.load();
        assertEquals(StatsDates.yesterday(), cache.lastComputedDate());
        assertEquals(1, cache.totalSessions(), "only yesterday's session is cached");

        // Second run must not double-count (cache hit + today live).
        ClaudeCodeStats again = aggregator.aggregateAll();
        assertEquals(2, again.totalSessions());
        assertEquals(110, again.modelUsage().get("m").inputTokens());
    }

    @Test
    void aggregateForRange_bypassesCacheAndDoesNotWrite() throws IOException {
        String todayTs = iso(Instant.now());
        writeSession("live", userEntry(todayTs, false),
            assistantEntry(todayTs, "m", 10, 5, 0));

        ClaudeCodeStats stats = aggregator.aggregateForRange(StatsDateRange.SEVEN_DAYS);
        assertEquals(1, stats.totalSessions());
        assertFalse(Files.exists(tmp.resolve("stats-cache.json")),
            "range queries must never write the cache");
    }

    @Test
    void emptyProjectsDirYieldsEmptyStats() {
        assertEquals(ClaudeCodeStats.empty(),
            new StatsAggregator(new SessionFileEnumerator(tmp.resolve("nope")),
                cacheStore, ZoneOffset.UTC).aggregateAll());
    }

    @Test
    void unchangedTranscriptReusesFingerprintCacheAndChangedFileInvalidatesIt() throws IOException {
        Path session = writeSession("cached",
            userEntry("2026-08-12T02:00:00.000Z", false),
            assistantEntry("2026-08-12T02:00:10.000Z", "m", 10, 5, 0));

        scanAll();
        assertEquals(1, aggregator.scanCountForTests());

        ProcessedStats cached = scanAll();
        assertEquals(1, cached.sessionStats().size());
        assertEquals(1, aggregator.scanCountForTests(),
            "same size + mtime should reuse the compact FileScan result");

        String extra = userEntry("2026-08-12T02:00:20.000Z", false) + "\n";
        Files.writeString(session, extra, StandardOpenOption.APPEND);
        Files.setLastModifiedTime(session, FileTime.from(Instant.now().plusSeconds(2)));

        ProcessedStats changed = scanAll();
        assertEquals(3, changed.totalMessages());
        assertEquals(2, aggregator.scanCountForTests(),
            "size/mtime change must invalidate the cached file aggregate");
    }

    @Test
    void sameFileScanCanBeReusedAcrossDateRanges() throws IOException {
        Path session = writeSession("range-cache",
            userEntry("2026-08-12T02:00:00.000Z", false),
            assistantEntry("2026-08-12T02:00:10.000Z", "m", 10, 5, 0));

        ProcessedStats all = aggregator.processSessionFiles(List.of(session), null, null);
        ProcessedStats recent = aggregator.processSessionFiles(
            List.of(session), "2026-08-01", "2026-08-31");

        assertEquals(1, all.sessionStats().size());
        assertEquals(1, recent.sessionStats().size());
        assertEquals(1, aggregator.scanCountForTests(),
            "date filters are applied after the compact per-file scan result is reused");
    }

    @Test
    void fingerprintCacheIsBounded() throws IOException {
        int cacheLimit = 8;
        StatsAggregator bounded = new StatsAggregator(
            new SessionFileEnumerator(projectsDir), cacheStore, ZoneOffset.UTC,
            2, cacheLimit, _ -> {});
        for (int i = 0; i < cacheLimit + 5; i++) {
            writeSession("cache-" + i,
                userEntry("2026-08-12T02:00:00.000Z", false));
        }

        bounded.processSessionFiles(
            new SessionFileEnumerator(projectsDir).listAllSessionFiles(), null, null);

        assertEquals(cacheLimit, bounded.fileScanCacheSizeForTests());
    }

    @Test
    void scanConcurrencyIsBoundedByConfiguredBudget() throws IOException {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            files.add(writeSession("parallel-" + i,
                userEntry("2026-08-12T02:00:00.000Z", false)));
        }

        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        StatsAggregator budgeted = new StatsAggregator(
            new SessionFileEnumerator(projectsDir), cacheStore, ZoneOffset.UTC,
            3, 128, _ -> {
                int current = active.incrementAndGet();
                peak.accumulateAndGet(current, Math::max);
                try {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    active.decrementAndGet();
                }
            });

        budgeted.processSessionFiles(files, null, null);

        assertTrue(peak.get() > 1, "independent files should still scan concurrently");
        assertTrue(peak.get() <= 3, "configured scan budget must cap concurrent readers");
    }
}
