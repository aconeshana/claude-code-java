package com.claudecode.services.insights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;


@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionMeta(
    String sessionId,
    String projectPath,
    String startTime,
    double durationMinutes,
    long userMessageCount,
    long assistantMessageCount,
    Map<String, Long> toolCounts,
    Map<String, Long> languages,
    long gitCommits,
    long gitPushes,
    long inputTokens,
    long outputTokens,
    String firstPrompt,
    String summary,                    // nullable
    long userInterruptions,
    List<Double> userResponseTimes,
    long toolErrors,
    Map<String, Long> toolErrorCategories,
    boolean usesTaskAgent,
    boolean usesMcp,
    boolean usesWebSearch,
    boolean usesWebFetch,
    long linesAdded,
    long linesRemoved,
    long filesModified,
    List<Integer> messageHours,
    List<String> userMessageTimestamps
) {
    public static Builder builder(String sessionId, String projectPath, String startTime) {
        return new Builder(sessionId, projectPath, startTime);
    }

    public static final class Builder {
        private final String sessionId;
        private final String projectPath;
        private final String startTime;
        private double durationMinutes;
        private long userMessageCount;
        private long assistantMessageCount;
        private Map<String, Long> toolCounts = Map.of();
        private Map<String, Long> languages = Map.of();
        private long gitCommits;
        private long gitPushes;
        private long inputTokens;
        private long outputTokens;
        private String firstPrompt = "";
        private String summary;
        private long userInterruptions;
        private List<Double> userResponseTimes = List.of();
        private long toolErrors;
        private Map<String, Long> toolErrorCategories = Map.of();
        private boolean usesTaskAgent;
        private boolean usesMcp;
        private boolean usesWebSearch;
        private boolean usesWebFetch;
        private long linesAdded;
        private long linesRemoved;
        private long filesModified;
        private List<Integer> messageHours = List.of();
        private List<String> userMessageTimestamps = List.of();

        private Builder(String sessionId, String projectPath, String startTime) {
            this.sessionId = sessionId;
            this.projectPath = projectPath;
            this.startTime = startTime;
        }

        public Builder durationMinutes(double value) { durationMinutes = value; return this; }
        public Builder userMessageCount(long value) { userMessageCount = value; return this; }
        public Builder assistantMessageCount(long value) { assistantMessageCount = value; return this; }
        public Builder toolCounts(Map<String, Long> value) { toolCounts = value; return this; }
        public Builder languages(Map<String, Long> value) { languages = value; return this; }
        public Builder gitCommits(long value) { gitCommits = value; return this; }
        public Builder gitPushes(long value) { gitPushes = value; return this; }
        public Builder inputTokens(long value) { inputTokens = value; return this; }
        public Builder outputTokens(long value) { outputTokens = value; return this; }
        public Builder firstPrompt(String value) { firstPrompt = value; return this; }
        public Builder summary(String value) { summary = value; return this; }
        public Builder userInterruptions(long value) { userInterruptions = value; return this; }
        public Builder userResponseTimes(List<Double> value) { userResponseTimes = value; return this; }
        public Builder toolErrors(long value) { toolErrors = value; return this; }
        public Builder toolErrorCategories(Map<String, Long> value) { toolErrorCategories = value; return this; }
        public Builder usesTaskAgent(boolean value) { usesTaskAgent = value; return this; }
        public Builder usesMcp(boolean value) { usesMcp = value; return this; }
        public Builder usesWebSearch(boolean value) { usesWebSearch = value; return this; }
        public Builder usesWebFetch(boolean value) { usesWebFetch = value; return this; }
        public Builder linesAdded(long value) { linesAdded = value; return this; }
        public Builder linesRemoved(long value) { linesRemoved = value; return this; }
        public Builder filesModified(long value) { filesModified = value; return this; }
        public Builder messageHours(List<Integer> value) { messageHours = value; return this; }
        public Builder userMessageTimestamps(List<String> value) { userMessageTimestamps = value; return this; }

        public SessionMeta build() {
            return new SessionMeta(sessionId, projectPath, startTime, durationMinutes,
                userMessageCount, assistantMessageCount, toolCounts, languages,
                gitCommits, gitPushes, inputTokens, outputTokens, firstPrompt,
                summary, userInterruptions, userResponseTimes, toolErrors,
                toolErrorCategories, usesTaskAgent, usesMcp, usesWebSearch,
                usesWebFetch, linesAdded, linesRemoved, filesModified,
                messageHours, userMessageTimestamps);
        }
    }
}
