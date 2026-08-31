package com.claudecode.session.stats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;


public record ClaudeCodeStats(
    long totalSessions,
    long totalMessages,
    long totalDays,
    long activeDays,
    StreakInfo streaks,
    List<DailyActivity> dailyActivity,
    List<DailyModelTokens> dailyModelTokens,
    SessionStats longestSession,          // nullable
    Map<String, ModelUsage> modelUsage,
    String firstSessionDate,              // nullable — full ISO timestamp
    String lastSessionDate,               // nullable — ISO timestamp or YYYY-MM-DD
    String peakActivityDay,               // nullable — YYYY-MM-DD
    Integer peakActivityHour,             // nullable — 0..23, local time
    long totalSpeculationTimeSavedMs
) {


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyActivity(String date, long messageCount, long sessionCount, long toolCallCount) {

        DailyActivity plus(DailyActivity other) {
            return new DailyActivity(date,
                messageCount + other.messageCount,
                sessionCount + other.sessionCount,
                toolCallCount + other.toolCallCount);
        }
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailyModelTokens(String date, Map<String, Long> tokensByModel) {}


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreakInfo(
        long currentStreak,
        long longestStreak,
        String currentStreakStart,
        String longestStreakStart,
        String longestStreakEnd
    ) {
        public static final StreakInfo EMPTY = new StreakInfo(0, 0, null, null, null);
    }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionStats(String sessionId, long duration, long messageCount, String timestamp) {}


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        long webSearchRequests,
        double costUSD,
        long contextWindow,
        long maxOutputTokens
    ) {
        public static final ModelUsage ZERO = new ModelUsage(0, 0, 0, 0, 0, 0, 0, 0);

        ModelUsage plus(ModelUsage o) {
            return new ModelUsage(
                inputTokens + o.inputTokens,
                outputTokens + o.outputTokens,
                cacheReadInputTokens + o.cacheReadInputTokens,
                cacheCreationInputTokens + o.cacheCreationInputTokens,
                webSearchRequests + o.webSearchRequests,
                costUSD + o.costUSD,
                Math.max(contextWindow, o.contextWindow),
                Math.max(maxOutputTokens, o.maxOutputTokens));
        }
    }


    public static ClaudeCodeStats empty() {
        return new ClaudeCodeStats(0, 0, 0, 0, StreakInfo.EMPTY,
            List.of(), List.of(), null, Map.of(), null, null, null, null, 0);
    }
}
