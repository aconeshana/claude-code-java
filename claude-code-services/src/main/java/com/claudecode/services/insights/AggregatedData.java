package com.claudecode.services.insights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;


@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AggregatedData(
    long totalSessions,
    Long totalSessionsScanned,
    long sessionsWithFacets,
    DateRange dateRange,
    long totalMessages,
    double totalDurationHours,
    long totalInputTokens,
    long totalOutputTokens,
    Map<String, Long> toolCounts,
    Map<String, Long> languages,
    long gitCommits,
    long gitPushes,
    Map<String, Long> projects,
    Map<String, Long> goalCategories,
    Map<String, Long> outcomes,
    Map<String, Long> satisfaction,
    Map<String, Long> helpfulness,
    Map<String, Long> sessionTypes,
    Map<String, Long> friction,
    Map<String, Long> success,
    List<SessionSummary> sessionSummaries,
    long totalInterruptions,
    long totalToolErrors,
    Map<String, Long> toolErrorCategories,
    List<Double> userResponseTimes,
    double medianResponseTime,
    double avgResponseTime,
    long sessionsUsingTaskAgent,
    long sessionsUsingMcp,
    long sessionsUsingWebSearch,
    long sessionsUsingWebFetch,
    long totalLinesAdded,
    long totalLinesRemoved,
    long totalFilesModified,
    long daysActive,
    double messagesPerDay,
    List<Integer> messageHours,
    MultiClauding multiClauding
) {

    public static Builder builder(long totalSessions, long sessionsWithFacets, DateRange dateRange) {
        return new Builder(totalSessions, sessionsWithFacets, dateRange);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }


    public AggregatedData withTotalSessionsScanned(long scanned) {
        return toBuilder().totalSessionsScanned(scanned).build();
    }

    public static final class Builder {
        private long totalSessions;
        private Long totalSessionsScanned;
        private long sessionsWithFacets;
        private DateRange dateRange;
        private long totalMessages;
        private double totalDurationHours;
        private long totalInputTokens;
        private long totalOutputTokens;
        private Map<String, Long> toolCounts = Map.of();
        private Map<String, Long> languages = Map.of();
        private long gitCommits;
        private long gitPushes;
        private Map<String, Long> projects = Map.of();
        private Map<String, Long> goalCategories = Map.of();
        private Map<String, Long> outcomes = Map.of();
        private Map<String, Long> satisfaction = Map.of();
        private Map<String, Long> helpfulness = Map.of();
        private Map<String, Long> sessionTypes = Map.of();
        private Map<String, Long> friction = Map.of();
        private Map<String, Long> success = Map.of();
        private List<SessionSummary> sessionSummaries = List.of();
        private long totalInterruptions;
        private long totalToolErrors;
        private Map<String, Long> toolErrorCategories = Map.of();
        private List<Double> userResponseTimes = List.of();
        private double medianResponseTime;
        private double avgResponseTime;
        private long sessionsUsingTaskAgent;
        private long sessionsUsingMcp;
        private long sessionsUsingWebSearch;
        private long sessionsUsingWebFetch;
        private long totalLinesAdded;
        private long totalLinesRemoved;
        private long totalFilesModified;
        private long daysActive;
        private double messagesPerDay;
        private List<Integer> messageHours = List.of();
        private MultiClauding multiClauding = new MultiClauding(0, 0, 0);

        private Builder(long totalSessions, long sessionsWithFacets, DateRange dateRange) {
            this.totalSessions = totalSessions;
            this.sessionsWithFacets = sessionsWithFacets;
            this.dateRange = dateRange;
        }

        private Builder(AggregatedData source) {
            totalSessions = source.totalSessions;
            totalSessionsScanned = source.totalSessionsScanned;
            sessionsWithFacets = source.sessionsWithFacets;
            dateRange = source.dateRange;
            totalMessages = source.totalMessages;
            totalDurationHours = source.totalDurationHours;
            totalInputTokens = source.totalInputTokens;
            totalOutputTokens = source.totalOutputTokens;
            toolCounts = source.toolCounts;
            languages = source.languages;
            gitCommits = source.gitCommits;
            gitPushes = source.gitPushes;
            projects = source.projects;
            goalCategories = source.goalCategories;
            outcomes = source.outcomes;
            satisfaction = source.satisfaction;
            helpfulness = source.helpfulness;
            sessionTypes = source.sessionTypes;
            friction = source.friction;
            success = source.success;
            sessionSummaries = source.sessionSummaries;
            totalInterruptions = source.totalInterruptions;
            totalToolErrors = source.totalToolErrors;
            toolErrorCategories = source.toolErrorCategories;
            userResponseTimes = source.userResponseTimes;
            medianResponseTime = source.medianResponseTime;
            avgResponseTime = source.avgResponseTime;
            sessionsUsingTaskAgent = source.sessionsUsingTaskAgent;
            sessionsUsingMcp = source.sessionsUsingMcp;
            sessionsUsingWebSearch = source.sessionsUsingWebSearch;
            sessionsUsingWebFetch = source.sessionsUsingWebFetch;
            totalLinesAdded = source.totalLinesAdded;
            totalLinesRemoved = source.totalLinesRemoved;
            totalFilesModified = source.totalFilesModified;
            daysActive = source.daysActive;
            messagesPerDay = source.messagesPerDay;
            messageHours = source.messageHours;
            multiClauding = source.multiClauding;
        }

        public Builder totalSessionsScanned(Long value) { totalSessionsScanned = value; return this; }
        public Builder totalMessages(long value) { totalMessages = value; return this; }
        public Builder totalDurationHours(double value) { totalDurationHours = value; return this; }
        public Builder totalInputTokens(long value) { totalInputTokens = value; return this; }
        public Builder totalOutputTokens(long value) { totalOutputTokens = value; return this; }
        public Builder toolCounts(Map<String, Long> value) { toolCounts = value; return this; }
        public Builder languages(Map<String, Long> value) { languages = value; return this; }
        public Builder gitCommits(long value) { gitCommits = value; return this; }
        public Builder gitPushes(long value) { gitPushes = value; return this; }
        public Builder projects(Map<String, Long> value) { projects = value; return this; }
        public Builder goalCategories(Map<String, Long> value) { goalCategories = value; return this; }
        public Builder outcomes(Map<String, Long> value) { outcomes = value; return this; }
        public Builder satisfaction(Map<String, Long> value) { satisfaction = value; return this; }
        public Builder helpfulness(Map<String, Long> value) { helpfulness = value; return this; }
        public Builder sessionTypes(Map<String, Long> value) { sessionTypes = value; return this; }
        public Builder friction(Map<String, Long> value) { friction = value; return this; }
        public Builder success(Map<String, Long> value) { success = value; return this; }
        public Builder sessionSummaries(List<SessionSummary> value) { sessionSummaries = value; return this; }
        public Builder totalInterruptions(long value) { totalInterruptions = value; return this; }
        public Builder totalToolErrors(long value) { totalToolErrors = value; return this; }
        public Builder toolErrorCategories(Map<String, Long> value) { toolErrorCategories = value; return this; }
        public Builder userResponseTimes(List<Double> value) { userResponseTimes = value; return this; }
        public Builder medianResponseTime(double value) { medianResponseTime = value; return this; }
        public Builder avgResponseTime(double value) { avgResponseTime = value; return this; }
        public Builder sessionsUsingTaskAgent(long value) { sessionsUsingTaskAgent = value; return this; }
        public Builder sessionsUsingMcp(long value) { sessionsUsingMcp = value; return this; }
        public Builder sessionsUsingWebSearch(long value) { sessionsUsingWebSearch = value; return this; }
        public Builder sessionsUsingWebFetch(long value) { sessionsUsingWebFetch = value; return this; }
        public Builder totalLinesAdded(long value) { totalLinesAdded = value; return this; }
        public Builder totalLinesRemoved(long value) { totalLinesRemoved = value; return this; }
        public Builder totalFilesModified(long value) { totalFilesModified = value; return this; }
        public Builder daysActive(long value) { daysActive = value; return this; }
        public Builder messagesPerDay(double value) { messagesPerDay = value; return this; }
        public Builder messageHours(List<Integer> value) { messageHours = value; return this; }
        public Builder multiClauding(MultiClauding value) { multiClauding = value; return this; }

        public AggregatedData build() {
            return new AggregatedData(totalSessions, totalSessionsScanned,
                sessionsWithFacets, dateRange, totalMessages, totalDurationHours,
                totalInputTokens, totalOutputTokens, toolCounts, languages,
                gitCommits, gitPushes, projects, goalCategories, outcomes,
                satisfaction, helpfulness, sessionTypes, friction, success,
                sessionSummaries, totalInterruptions, totalToolErrors,
                toolErrorCategories, userResponseTimes, medianResponseTime,
                avgResponseTime, sessionsUsingTaskAgent, sessionsUsingMcp,
                sessionsUsingWebSearch, sessionsUsingWebFetch, totalLinesAdded,
                totalLinesRemoved, totalFilesModified, daysActive, messagesPerDay,
                messageHours, multiClauding);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateRange(String start, String end) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionSummary(String id, String date, String summary, String goal) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiClauding(long overlapEvents, long sessionsInvolved, long userMessagesDuring) {}
}
