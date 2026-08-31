package com.claudecode.services.insights;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsAggregatorTest {

    /** Compact builder over the 27-component {@link SessionMeta} record. */
    private static final class MetaBuilder {
        String sessionId = "session-1";
        String projectPath = "/tmp/proj";
        String startTime = "2026-07-01T10:00:00.000Z";
        double durationMinutes = 0;
        long userMessageCount = 0;
        long assistantMessageCount = 0;
        Map<String, Long> toolCounts = Map.of();
        Map<String, Long> languages = Map.of();
        long gitCommits = 0;
        long gitPushes = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        String firstPrompt = "";
        String summary = null;
        long userInterruptions = 0;
        List<Double> userResponseTimes = List.of();
        long toolErrors = 0;
        Map<String, Long> toolErrorCategories = Map.of();
        boolean usesTaskAgent = false;
        boolean usesMcp = false;
        boolean usesWebSearch = false;
        boolean usesWebFetch = false;
        long linesAdded = 0;
        long linesRemoved = 0;
        long filesModified = 0;
        List<Integer> messageHours = List.of();
        List<String> userMessageTimestamps = List.of();

        SessionMeta build() {
            return SessionMeta.builder(sessionId, projectPath, startTime)
                .durationMinutes(durationMinutes).userMessageCount(userMessageCount)
                .assistantMessageCount(assistantMessageCount).toolCounts(toolCounts)
                .languages(languages).gitCommits(gitCommits).gitPushes(gitPushes)
                .inputTokens(inputTokens).outputTokens(outputTokens).firstPrompt(firstPrompt)
                .summary(summary).userInterruptions(userInterruptions)
                .userResponseTimes(userResponseTimes).toolErrors(toolErrors)
                .toolErrorCategories(toolErrorCategories).usesTaskAgent(usesTaskAgent)
                .usesMcp(usesMcp).usesWebSearch(usesWebSearch).usesWebFetch(usesWebFetch)
                .linesAdded(linesAdded).linesRemoved(linesRemoved).filesModified(filesModified)
                .messageHours(messageHours).userMessageTimestamps(userMessageTimestamps)
                .build();
        }
    }

    private static SessionFacets facets(String sessionId, Map<String, Long> goals, String outcome,
                                        Map<String, Long> satisfactionCounts, String helpfulness,
                                        String sessionType, Map<String, Long> frictionCounts,
                                        String primarySuccess, String briefSummary) {
        return new SessionFacets(sessionId, "goal of " + sessionId, goals, outcome,
            satisfactionCounts, helpfulness, sessionType, frictionCounts, "", primarySuccess,
            briefSummary, null);
    }

    @Test
    void aggregatesTotalsMediansAndRates() {
        MetaBuilder s1 = new MetaBuilder();
        s1.sessionId = "s1";
        s1.startTime = "2026-07-01T10:00:00.000Z";
        s1.durationMinutes = 60;
        s1.userMessageCount = 4;
        s1.toolCounts = new LinkedHashMap<>(Map.of("Bash", 2L));
        s1.userResponseTimes = List.of(10.0);
        s1.inputTokens = 100;
        s1.outputTokens = 50;
        s1.gitCommits = 1;
        s1.usesTaskAgent = true;
        s1.messageHours = List.of(10, 11);

        MetaBuilder s2 = new MetaBuilder();
        s2.sessionId = "s2";
        s2.startTime = "2026-07-02T09:00:00.000Z";
        s2.durationMinutes = 30;
        s2.userMessageCount = 3;
        Map<String, Long> tools2 = new LinkedHashMap<>();
        tools2.put("Bash", 1L);
        tools2.put("Edit", 1L);
        s2.toolCounts = tools2;
        s2.userResponseTimes = List.of(2.0, 30.0);
        s2.inputTokens = 200;
        s2.outputTokens = 25;
        s2.gitPushes = 2;

        AggregatedData data = InsightsAggregator.aggregate(List.of(s1.build(), s2.build()), Map.of());

        assertEquals(2, data.totalSessions());
        assertEquals(0, data.sessionsWithFacets());
        assertEquals(7, data.totalMessages());
        assertEquals(1.5, data.totalDurationHours(), 1e-9);
        assertEquals(300, data.totalInputTokens());
        assertEquals(75, data.totalOutputTokens());
        assertEquals(1, data.gitCommits());
        assertEquals(2, data.gitPushes());
        assertEquals("2026-07-01", data.dateRange().start());
        assertEquals("2026-07-02", data.dateRange().end());
        assertEquals(3L, data.toolCounts().get("Bash"));
        assertEquals(1L, data.toolCounts().get("Edit"));
        assertEquals(2L, data.projects().get("/tmp/proj"));
        assertEquals(1, data.sessionsUsingTaskAgent());
        assertEquals(0, data.sessionsUsingMcp());
        // Response times [10, 2, 30] → sorted [2, 10, 30], median = idx floor(3/2)=1 → 10
        assertEquals(10.0, data.medianResponseTime(), 1e-9);
        assertEquals(14.0, data.avgResponseTime(), 1e-9);
        assertEquals(List.of(10.0, 2.0, 30.0), data.userResponseTimes());
        // 7 messages over 2 active days → round(3.5 * 10) / 10 = 3.5
        assertEquals(2, data.daysActive());
        assertEquals(3.5, data.messagesPerDay(), 1e-9);
        assertEquals(List.of(10, 11), data.messageHours());
        assertNull(data.totalSessionsScanned());
    }

    @Test
    void mergesFacetCountsSkippingZeroesAndNoneSuccess() {
        MetaBuilder s1 = new MetaBuilder();
        s1.sessionId = "s1";
        MetaBuilder s2 = new MetaBuilder();
        s2.sessionId = "s2";
        s2.startTime = "2026-07-01T12:00:00.000Z";

        Map<String, Long> goals1 = new LinkedHashMap<>();
        goals1.put("fix_bug", 2L);
        goals1.put("write_docs", 0L); // zero → excluded
        Map<String, SessionFacets> byId = new LinkedHashMap<>();
        byId.put("s1", facets("s1", goals1, "fully_achieved", Map.of("satisfied", 1L),
            "very_helpful", "single_task", Map.of("buggy_code", 1L), "correct_code_edits", "b1"));
        byId.put("s2", facets("s2", Map.of("fix_bug", 1L), "fully_achieved", Map.of(),
            "essential", "multi_task", Map.of(), "none", "b2"));

        AggregatedData data = InsightsAggregator.aggregate(List.of(s1.build(), s2.build()), byId);

        assertEquals(2, data.sessionsWithFacets());
        assertEquals(3L, data.goalCategories().get("fix_bug"));
        assertFalse(data.goalCategories().containsKey("write_docs"));
        assertEquals(2L, data.outcomes().get("fully_achieved"));
        assertEquals(1L, data.satisfaction().get("satisfied"));
        assertEquals(1L, data.helpfulness().get("very_helpful"));
        assertEquals(1L, data.helpfulness().get("essential"));
        assertEquals(1L, data.sessionTypes().get("single_task"));
        assertEquals(1L, data.friction().get("buggy_code"));
        // primary_success 'none' skipped
        assertEquals(1L, data.success().get("correct_code_edits"));
        assertFalse(data.success().containsKey("none"));
    }

    @Test
    void sessionSummariesUseSummaryOrTruncatedFirstPromptAndFacetGoal() {
        MetaBuilder withSummary = new MetaBuilder();
        withSummary.sessionId = "abcdef1234567890";
        withSummary.summary = "the summary";
        withSummary.firstPrompt = "ignored";

        MetaBuilder withoutSummary = new MetaBuilder();
        withoutSummary.sessionId = "s2";
        withoutSummary.summary = null;
        withoutSummary.firstPrompt = "p".repeat(150);

        Map<String, SessionFacets> byId = Map.of("s2", facets("s2", Map.of(), "not_achieved",
            Map.of(), "unhelpful", "exploration", Map.of(), "none", "brief"));

        AggregatedData data = InsightsAggregator.aggregate(
            List.of(withSummary.build(), withoutSummary.build()), byId);

        assertEquals(2, data.sessionSummaries().size());
        AggregatedData.SessionSummary first = data.sessionSummaries().getFirst();
        assertEquals("abcdef12", first.id());
        assertEquals("2026-07-01", first.date());
        assertEquals("the summary", first.summary());
        assertNull(first.goal());

        AggregatedData.SessionSummary second = data.sessionSummaries().get(1);
        assertEquals("p".repeat(100), second.summary());
        assertEquals("goal of s2", second.goal());
    }

    @Test
    void capsSessionSummariesAtFifty() {
        List<SessionMeta> sessions = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            MetaBuilder b = new MetaBuilder();
            b.sessionId = "s" + i;
            sessions.add(b.build());
        }
        AggregatedData data = InsightsAggregator.aggregate(sessions, Map.of());
        assertEquals(50, data.sessionSummaries().size());
        assertEquals(60, data.totalSessions());
    }

    @Test
    void detectsInterleavedMultiClauding() {
        MetaBuilder a = new MetaBuilder();
        a.sessionId = "aaa";
        a.userMessageTimestamps = List.of(
            "2026-07-01T10:00:00.000Z",
            "2026-07-01T10:10:00.000Z"); // s1 → s2 → s1 within 30 min
        MetaBuilder b = new MetaBuilder();
        b.sessionId = "bbb";
        b.userMessageTimestamps = List.of("2026-07-01T10:05:00.000Z");

        AggregatedData.MultiClauding result =
            InsightsAggregator.detectMultiClauding(List.of(a.build(), b.build()));

        assertEquals(1, result.overlapEvents());
        assertEquals(2, result.sessionsInvolved());
        assertEquals(3, result.userMessagesDuring());
    }

    @Test
    void ignoresInterleavingOutsideThirtyMinuteWindow() {
        MetaBuilder a = new MetaBuilder();
        a.sessionId = "aaa";
        a.userMessageTimestamps = List.of(
            "2026-07-01T10:00:00.000Z",
            "2026-07-01T11:20:00.000Z"); // second aaa message 80 min later
        MetaBuilder b = new MetaBuilder();
        b.sessionId = "bbb";
        b.userMessageTimestamps = List.of("2026-07-01T10:40:00.000Z");

        AggregatedData.MultiClauding result =
            InsightsAggregator.detectMultiClauding(List.of(a.build(), b.build()));

        assertEquals(0, result.overlapEvents());
        assertEquals(0, result.sessionsInvolved());
        assertEquals(0, result.userMessagesDuring());
    }

    @Test
    void singleSessionAloneNeverCountsAsMultiClauding() {
        MetaBuilder a = new MetaBuilder();
        a.sessionId = "aaa";
        a.userMessageTimestamps = List.of(
            "2026-07-01T10:00:00.000Z",
            "2026-07-01T10:05:00.000Z",
            "2026-07-01T10:10:00.000Z");

        AggregatedData.MultiClauding result =
            InsightsAggregator.detectMultiClauding(List.of(a.build()));

        assertEquals(0, result.overlapEvents());
    }

    @Test
    void aggregatesEmptyInputToZeroedData() {
        AggregatedData data = InsightsAggregator.aggregate(List.of(), Map.of());

        assertEquals(0, data.totalSessions());
        assertEquals("", data.dateRange().start());
        assertEquals("", data.dateRange().end());
        assertEquals(0.0, data.medianResponseTime());
        assertEquals(0.0, data.avgResponseTime());
        assertEquals(0, data.daysActive());
        assertEquals(0.0, data.messagesPerDay());
        assertEquals(0, data.multiClauding().overlapEvents());
        assertTrue(data.sessionSummaries().isEmpty());
    }
}
