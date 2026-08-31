package com.claudecode.session.stats;

import com.claudecode.session.stats.ClaudeCodeStats.DailyActivity;
import com.claudecode.session.stats.ClaudeCodeStats.DailyModelTokens;
import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;
import com.claudecode.session.stats.ClaudeCodeStats.SessionStats;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;


public final class StatsCacheStore {

    private static final Logger LOG = LoggerFactory.getLogger(StatsCacheStore.class);

    static final int STATS_CACHE_VERSION = 3;
    static final int MIN_MIGRATABLE_VERSION = 1;
/** Version 4 uses a key-compatible schema and is accepted on read. */
    static final int MAX_ACCEPTED_VERSION = 4;
    private static final String FILENAME = "stats-cache.json";

    private static final ReentrantLock LOCK = new ReentrantLock();
    private final Path cachePath;

/** Production wiring:. */
    public StatsCacheStore() {
        this(ClaudePaths.CLAUDE_HOME.resolve(FILENAME));
    }

    /** Explicit path (tests). */
    public StatsCacheStore(Path cachePath) {
        this.cachePath = cachePath;
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PersistedStatsCache(
        int version,
        String lastComputedDate,          // nullable YYYY-MM-DD
        List<DailyActivity> dailyActivity,
        List<DailyModelTokens> dailyModelTokens,
        Map<String, ModelUsage> modelUsage,
        long totalSessions,
        long totalMessages,
        SessionStats longestSession,      // nullable
        String firstSessionDate,          // nullable ISO timestamp
        Map<String, Long> hourCounts,     // "0".."23" → count
        long totalSpeculationTimeSavedMs
    ) {
        public static PersistedStatsCache empty() {
            return new PersistedStatsCache(STATS_CACHE_VERSION, null,
                List.of(), List.of(), Map.of(), 0, 0, null, null, Map.of(), 0);
        }
    }


    public static <T> T withLock(Supplier<T> fn) {
        LOCK.lock();
        try {
            return fn.get();
        } finally {
            LOCK.unlock();
        }
    }


    public PersistedStatsCache load() {
        if (!Files.isReadable(cachePath)) return PersistedStatsCache.empty();
        try {
            PersistedStatsCache parsed = JsonUtils.getMapper()
                .readValue(Files.readString(cachePath), PersistedStatsCache.class);
            if (parsed.version() < MIN_MIGRATABLE_VERSION || parsed.version() > MAX_ACCEPTED_VERSION) {
                LOG.debug("Stats cache version {} outside accepted range, returning empty cache", parsed.version());
                return PersistedStatsCache.empty();
            }

            // null-safes what it can, guard the rest).
            if (parsed.dailyActivity() == null || parsed.dailyModelTokens() == null) {
                LOG.debug("Stats cache has invalid structure, returning empty cache");
                return PersistedStatsCache.empty();
            }
            return normalize(parsed);
        } catch (Exception e) {
            LOG.debug("Failed to load stats cache: {}", e.getMessage());
            return PersistedStatsCache.empty();
        }
    }

    /** Nulls from older/foreign caches → empty collections, version pinned to ours. */
    private static PersistedStatsCache normalize(PersistedStatsCache c) {
        return new PersistedStatsCache(
            STATS_CACHE_VERSION,
            c.lastComputedDate(),
            c.dailyActivity(),
            c.dailyModelTokens(),
            c.modelUsage() != null ? c.modelUsage() : Map.of(),
            c.totalSessions(),
            c.totalMessages(),
            c.longestSession(),
            c.firstSessionDate(),
            c.hourCounts() != null ? c.hourCounts() : Map.of(),
            c.totalSpeculationTimeSavedMs());
    }


    public void save(PersistedStatsCache cache) {
        try {
            String content = JsonUtils.toPrettyJson(cache);
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            FileUtils.atomicReplace(cachePath, tempPath -> {
                try (var channel = FileChannel.open(tempPath,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    FileUtils.writeFully(channel, ByteBuffer.wrap(data));
                    channel.force(true);
                }
            });
            LOG.debug("Stats cache saved (lastComputedDate: {})", cache.lastComputedDate());
        } catch (IOException e) {
            LOG.warn("Failed to save stats cache: {}", e.getMessage());
        }
    }


    public static PersistedStatsCache merge(PersistedStatsCache existing,
                                            StatsAggregator.ProcessedStats newStats,
                                            String newLastComputedDate) {
        Map<String, DailyActivity> activityByDate = new TreeMap<>();
        for (DailyActivity day : existing.dailyActivity()) activityByDate.put(day.date(), day);
        for (DailyActivity day : newStats.dailyActivity()) activityByDate.merge(day.date(), day, DailyActivity::plus);

        Map<String, Map<String, Long>> tokensByDate = new TreeMap<>();
        for (DailyModelTokens day : existing.dailyModelTokens()) {
            tokensByDate.put(day.date(), new LinkedHashMap<>(day.tokensByModel()));
        }
        for (DailyModelTokens day : newStats.dailyModelTokens()) {
            Map<String, Long> row = tokensByDate.computeIfAbsent(day.date(), _ -> new LinkedHashMap<>());
            day.tokensByModel().forEach((model, tokens) -> row.merge(model, tokens, Long::sum));
        }

        Map<String, ModelUsage> modelUsage = new LinkedHashMap<>(existing.modelUsage());
        newStats.modelUsage().forEach((model, usage) -> modelUsage.merge(model, usage, ModelUsage::plus));

        Map<String, Long> hourCounts = new HashMap<>(existing.hourCounts());
        newStats.hourCounts().forEach((hour, count) -> hourCounts.merge(hour, count, Long::sum));

        long totalSessions = existing.totalSessions() + newStats.sessionStats().size();
        long totalMessages = existing.totalMessages()
            + newStats.sessionStats().stream().mapToLong(SessionStats::messageCount).sum();

        SessionStats longestSession = existing.longestSession();
        for (SessionStats session : newStats.sessionStats()) {
            if (longestSession == null || session.duration() > longestSession.duration()) {
                longestSession = session;
            }
        }

        String firstSessionDate = existing.firstSessionDate();
        for (SessionStats session : newStats.sessionStats()) {
            if (firstSessionDate == null || session.timestamp().compareTo(firstSessionDate) < 0) {
                firstSessionDate = session.timestamp();
            }
        }

        return new PersistedStatsCache(
            STATS_CACHE_VERSION,
            newLastComputedDate,
            List.copyOf(activityByDate.values()),
            toSortedDailyTokens(tokensByDate),
            modelUsage,
            totalSessions,
            totalMessages,
            longestSession,
            firstSessionDate,
            hourCounts,
            existing.totalSpeculationTimeSavedMs() + newStats.totalSpeculationTimeSavedMs());
    }

    static List<DailyModelTokens> toSortedDailyTokens(Map<String, Map<String, Long>> tokensByDate) {
        List<DailyModelTokens> out = new ArrayList<>(tokensByDate.size());
        tokensByDate.forEach((date, tokens) -> out.add(new DailyModelTokens(date, tokens)));
        out.sort(Comparator.comparing(DailyModelTokens::date));
        return out;
    }
}
