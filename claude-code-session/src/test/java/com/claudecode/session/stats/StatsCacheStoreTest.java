package com.claudecode.session.stats;

import org.apache.commons.lang3.Strings;

import com.claudecode.session.stats.ClaudeCodeStats.DailyActivity;
import com.claudecode.session.stats.ClaudeCodeStats.DailyModelTokens;
import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;
import com.claudecode.session.stats.ClaudeCodeStats.SessionStats;
import com.claudecode.session.stats.StatsCacheStore.PersistedStatsCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trips {@link StatsCacheStore} and verifies the version-compat policy:
 * version-4 caches with the key-compatible schema
 * load as-is; out-of-range versions and malformed files degrade to empty.
 */
class StatsCacheStoreTest {

    @TempDir Path tmp;

    private StatsCacheStore store() {
        return new StatsCacheStore(tmp.resolve("stats-cache.json"));
    }

    private static PersistedStatsCache sample() {
        return new PersistedStatsCache(
            StatsCacheStore.STATS_CACHE_VERSION, "2026-07-10",
            List.of(new DailyActivity("2026-07-09", 10, 2, 5)),
            List.of(new DailyModelTokens("2026-07-09", Map.of("claude-opus-4-8", 1500L))),
            Map.of("claude-opus-4-8", new ModelUsage(1000, 500, 5, 7, 0, 0, 0, 0)),
            2, 10,
            new SessionStats("s1", 60_000, 8, "2026-07-09T02:00:00.000Z"),
            "2026-07-09T02:00:00.000Z",
            Map.of("2", 2L), 1234);
    }

    @Test
    void saveLoadRoundTrip() {
        StatsCacheStore s = store();
        s.save(sample());
        PersistedStatsCache loaded = s.load();
        assertEquals("2026-07-10", loaded.lastComputedDate());
        assertEquals(1, loaded.dailyActivity().size());
        assertEquals(10, loaded.dailyActivity().getFirst().messageCount());
        assertEquals(1500L, loaded.dailyModelTokens().getFirst().tokensByModel().get("claude-opus-4-8"));
        assertEquals(1000, loaded.modelUsage().get("claude-opus-4-8").inputTokens());
        assertEquals("s1", loaded.longestSession().sessionId());
        assertEquals(2L, loaded.hourCounts().get("2"));
        assertEquals(1234, loaded.totalSpeculationTimeSavedMs());
    }

    @Test
    void missingFileYieldsEmpty() {
        assertEquals(PersistedStatsCache.empty(), store().load());
    }

    @Test
    void malformedFileYieldsEmpty() throws Exception {
        Files.writeString(tmp.resolve("stats-cache.json"), "{ not json ");
        assertEquals(PersistedStatsCache.empty(), store().load());
    }

    @Test
    void officialV4CacheLoadsAsIs() throws Exception {
// Version-4 fixture using the same keys as version 3.
        Files.writeString(tmp.resolve("stats-cache.json"), """
            {"version":4,"lastComputedDate":"2026-07-09",
             "dailyActivity":[{"date":"2025-12-24","messageCount":732,"sessionCount":3,"toolCallCount":221}],
             "dailyModelTokens":[{"date":"2025-12-24","tokensByModel":{"claude-sonnet-4-5-20250929":9000}}],
             "modelUsage":{"claude-sonnet-4-5-20250929":{"inputTokens":1172361241,"outputTokens":585902,
               "cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0,
               "contextWindow":0,"maxOutputTokens":0}},
             "totalSessions":100,"totalMessages":5000,
             "longestSession":{"sessionId":"db6c3d25","duration":2150084747,"messageCount":1140,
               "timestamp":"2026-04-27T12:24:29.774Z"},
             "firstSessionDate":"2025-12-24T01:00:00.000Z",
             "hourCounts":{"0":5,"10":33},"totalSpeculationTimeSavedMs":0}
            """);
        PersistedStatsCache loaded = store().load();
        assertEquals("2026-07-09", loaded.lastComputedDate(), "v4 must be accepted, not recomputed");
        assertEquals(100, loaded.totalSessions());
        assertEquals(732, loaded.dailyActivity().getFirst().messageCount());
        assertEquals(1172361241L, loaded.modelUsage().get("claude-sonnet-4-5-20250929").inputTokens());
    }

    @Test
    void futureVersionYieldsEmpty() throws Exception {
        Files.writeString(tmp.resolve("stats-cache.json"),
            "{\"version\":9,\"lastComputedDate\":\"2026-07-09\",\"dailyActivity\":[],\"dailyModelTokens\":[]," +
            "\"modelUsage\":{},\"totalSessions\":1,\"totalMessages\":1,\"hourCounts\":{},\"totalSpeculationTimeSavedMs\":0}");
        assertEquals(PersistedStatsCache.empty(), store().load());
    }

    @Test
    void unknownKeysIgnored() throws Exception {
        // v4+ may add fields we don't know; they must not break the read.
        Files.writeString(tmp.resolve("stats-cache.json"),
            "{\"version\":4,\"lastComputedDate\":\"2026-07-09\",\"dailyActivity\":[],\"dailyModelTokens\":[]," +
            "\"modelUsage\":{},\"totalSessions\":1,\"totalMessages\":1,\"hourCounts\":{}," +
            "\"totalSpeculationTimeSavedMs\":0,\"someNewV5Field\":{\"x\":1}}");
        assertEquals(1, store().load().totalSessions());
    }

    @Test
    void savedVersionIsOurs() throws Exception {
        StatsCacheStore s = store();
        s.save(sample());
        String raw = Files.readString(tmp.resolve("stats-cache.json"));
        assertTrue(Strings.CS.contains(raw, "\"version\" : " + StatsCacheStore.STATS_CACHE_VERSION),
            "writes must emit our snapshot version (official CLI migrates it up losslessly): " + raw.substring(0, 80));
    }

    @Test
    void merge_addsNewDaysWithoutTouchingOldOnes() {
        PersistedStatsCache existing = sample();
        var newStats = new StatsAggregator.ProcessedStats(
            List.of(new DailyActivity("2026-07-11", 4, 1, 2)),
            List.of(new DailyModelTokens("2026-07-11", Map.of("claude-opus-4-8", 300L))),
            Map.of("claude-opus-4-8", new ModelUsage(200, 100, 0, 0, 0, 0, 0, 0)),
            List.of(new SessionStats("s2", 120_000, 4, "2026-07-11T05:00:00.000Z")),
            Map.of("5", 1L), 4, 100);

        PersistedStatsCache merged = StatsCacheStore.merge(existing, newStats, "2026-07-11");

        assertEquals("2026-07-11", merged.lastComputedDate());
        assertEquals(2, merged.dailyActivity().size());
        assertEquals(10, merged.dailyActivity().getFirst().messageCount(), "old day untouched");
        assertEquals(4, merged.dailyActivity().get(1).messageCount());
        assertEquals(3, merged.totalSessions());
        assertEquals(14, merged.totalMessages());
        assertEquals(1200, merged.modelUsage().get("claude-opus-4-8").inputTokens());
        // longest session: s2 (120s) beats s1 (60s)
        assertEquals("s2", merged.longestSession().sessionId());
        assertEquals(1L, merged.hourCounts().get("5"));
        assertEquals(2L, merged.hourCounts().get("2"));
        assertEquals(1334, merged.totalSpeculationTimeSavedMs());
        assertEquals("2026-07-09T02:00:00.000Z", merged.firstSessionDate(), "earlier first date kept");
    }

    @Test
    void atomicSaveLeavesNoTempFiles() throws Exception {
        StatsCacheStore s = store();
        s.save(sample());
        try (var stream = Files.list(tmp)) {
            assertTrue(stream.allMatch(p -> Strings.CS.equals(p.getFileName().toString(), "stats-cache.json")),
                "no .tmp leftovers");
        }
    }
}
