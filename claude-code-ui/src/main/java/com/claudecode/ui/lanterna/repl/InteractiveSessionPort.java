package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Message;
import com.claudecode.tools.worktree.WorktreeSession;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

/**
 * Consumer-owned boundary for interactive session persistence and statistics.
 */
public interface InteractiveSessionPort {

    enum StatsDateRange { SEVEN_DAYS, THIRTY_DAYS, ALL }

    interface SessionListing {
        List<SessionEntry> loadMore(int count);
        boolean hasMore();

        static SessionListing of(List<SessionEntry> entries) {
            List<SessionEntry> snapshot = entries == null ? List.of() : List.copyOf(entries);
            return new SessionListing() {
                private int nextIndex;

                @Override public synchronized List<SessionEntry> loadMore(int count) {
                    if (count <= 0 || nextIndex >= snapshot.size()) return List.of();
                    int end = Math.min(snapshot.size(), nextIndex + count);
                    List<SessionEntry> result = new ArrayList<>(snapshot.subList(nextIndex, end));
                    nextIndex = end;
                    return result;
                }

                @Override public synchronized boolean hasMore() {
                    return nextIndex < snapshot.size();
                }
            };
        }
    }

    record SessionEntry(
        String id,
        long lastModified,
        Instant createdAt,
        int messageCount,
        String summary,
        String gitBranch,
        String cwd,
        String tag,
        Path transcriptPath,
        String projectPath,
        String customTitle,
        long fileSize,
        boolean alias
    ) {
        public SessionEntry(String id, Instant createdAt, int messageCount, String ignoredLastModel) {
            this(id, createdAt == null ? 0L : createdAt.toEpochMilli(), createdAt,
                messageCount, null, null, null, null, null, null, null, -1L, false);
        }

        public SessionEntry(String id, long lastModified, Instant createdAt, int messageCount,
                            String summary, String gitBranch, String cwd, String tag) {
            this(id, lastModified, createdAt, messageCount, summary, gitBranch, cwd, tag,
                null, cwd, null, -1L, false);
        }

        public SessionEntry(String id, long lastModified, Instant createdAt, int messageCount,
                            String summary, String gitBranch, String cwd, String tag,
                            Path transcriptPath, String projectPath, String customTitle,
                            long fileSize) {
            this(id, lastModified, createdAt, messageCount, summary, gitBranch, cwd, tag,
                transcriptPath, projectPath, customTitle, fileSize, false);
        }
    }

    record MetadataSnapshot(String customTitle, String agentName, String agentColor, String tag) {
        public static MetadataSnapshot empty() {
            return new MetadataSnapshot(null, null, null, null);
        }
    }

    record DailyActivity(String date, long messageCount, long sessionCount, long toolCallCount) {}
    record DailyModelTokens(String date, Map<String, Long> tokensByModel) {}
    record StreakInfo(long currentStreak, long longestStreak, String currentStreakStart,
                      String longestStreakStart, String longestStreakEnd) {}
    record SessionStats(String sessionId, long duration, long messageCount, String timestamp) {}
    record ModelUsage(long inputTokens, long outputTokens, long cacheReadInputTokens,
                      long cacheCreationInputTokens, long webSearchRequests, double costUSD,
                      long contextWindow, long maxOutputTokens) {}
    record StatsSnapshot(
        long totalSessions,
        long totalMessages,
        long totalDays,
        long activeDays,
        StreakInfo streaks,
        List<DailyActivity> dailyActivity,
        List<DailyModelTokens> dailyModelTokens,
        SessionStats longestSession,
        Map<String, ModelUsage> modelUsage,
        String firstSessionDate,
        String lastSessionDate,
        String peakActivityDay,
        Integer peakActivityHour,
        long totalSpeculationTimeSavedMs
    ) {
        public static StatsSnapshot empty() {
            return new StatsSnapshot(0, 0, 0, 0,
                new StreakInfo(0, 0, null, null, null), List.of(), List.of(), null,
                Map.of(), null, null, null, null, 0);
        }
    }

    default List<SessionEntry> recentSessions(String cwd, int limit) { return List.of(); }
    default List<SessionEntry> sameRepositorySessions(String cwd) { return List.of(); }
    default List<SessionEntry> allProjectSessions(String cwd, int limit) { return List.of(); }
    default SessionListing sameRepositorySessionListing(String cwd) {
        return SessionListing.of(sameRepositorySessions(cwd));
    }
    default SessionListing allProjectSessionListing(String cwd) {
        return SessionListing.of(allProjectSessions(cwd, Integer.MAX_VALUE));
    }
    default Optional<SessionEntry> findExactSession(String cwd, String sessionId) { return Optional.empty(); }
    default List<Message> readMessages(Path transcript) { return List.of(); }
    default List<Message> readAgentMessages(Path transcript, String agentId) {
        return readMessages(transcript);
    }
    /** Sidechain-owned suffix only; excludes any fork-context prefix from the parent. */
    default List<Message> readAgentSidechainMessages(Path transcript, String agentId) {
        return readAgentMessages(transcript, agentId);
    }
    default MetadataSnapshot scanMetadata(Path transcript) { return MetadataSnapshot.empty(); }
    default boolean deleteSession(SessionEntry session, String fallbackCwd) { return false; }
    default void saveCustomTitle(SessionEntry session, String title) {
        throw new IllegalStateException("interactive session persistence is not wired");
    }
    default Path sessionFile(String cwd, String sessionId) { return null; }
    default Path agentTranscriptPath(String cwd, String sessionId, String agentId) { return null; }
    default Path toolResultsDirectory(String cwd, String sessionId) { return null; }
    default Path workflowRunPath(String cwd, String sessionId, String runId) { return null; }
    default String readCustomTitle(String cwd, String sessionId) { return null; }
    default String parentSessionId(String cwd, String sessionId) { return null; }
    default void appendParentSession(String cwd, String sessionId, String parentSessionId, String reason) { }
    default void reAppendSessionMetadata(String cwd, String sessionId) { }
    default void releaseTranscriptState(TranscriptSink sink, String sessionId, long timeoutMillis) { }
    default StatsSnapshot aggregateStats(StatsDateRange range) { return StatsSnapshot.empty(); }
    default void persistWorktreeExit(WorktreeSession session) { }

    static InteractiveSessionPort none() { return new InteractiveSessionPort() { }; }
}
