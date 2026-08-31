package com.claudecode.services.insights;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure aggregation of per-session {@link SessionMeta} + {@link SessionFacets} into one {@link
 * AggregatedData} — the numbers behind the insight prompts and the HTML report.
 */
public final class InsightsAggregator {


    private static final long OVERLAP_WINDOW_MS = 30L * 60_000;

    private static final int SESSION_SUMMARY_CAP = 50;

    private InsightsAggregator() {}


    public static AggregatedData aggregate(List<SessionMeta> sessions, Map<String, SessionFacets> facets) {
        long totalMessages = 0;
        double totalDurationHours = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long gitCommits = 0;
        long gitPushes = 0;
        long totalInterruptions = 0;
        long totalToolErrors = 0;
        long sessionsUsingTaskAgent = 0;
        long sessionsUsingMcp = 0;
        long sessionsUsingWebSearch = 0;
        long sessionsUsingWebFetch = 0;
        long totalLinesAdded = 0;
        long totalLinesRemoved = 0;
        long totalFilesModified = 0;

        Map<String, Long> toolCounts = new LinkedHashMap<>();
        Map<String, Long> languages = new LinkedHashMap<>();
        Map<String, Long> projects = new LinkedHashMap<>();
        Map<String, Long> toolErrorCategories = new LinkedHashMap<>();
        Map<String, Long> goalCategories = new LinkedHashMap<>();
        Map<String, Long> outcomes = new LinkedHashMap<>();
        Map<String, Long> satisfaction = new LinkedHashMap<>();
        Map<String, Long> helpfulness = new LinkedHashMap<>();
        Map<String, Long> sessionTypes = new LinkedHashMap<>();
        Map<String, Long> friction = new LinkedHashMap<>();
        Map<String, Long> success = new LinkedHashMap<>();
        List<AggregatedData.SessionSummary> sessionSummaries = new ArrayList<>();

        List<String> dates = new ArrayList<>();
        List<Double> allResponseTimes = new ArrayList<>();
        List<Integer> allMessageHours = new ArrayList<>();

        for (SessionMeta session : sessions) {
            dates.add(nullToEmpty(session.startTime()));
            totalMessages += session.userMessageCount();
            totalDurationHours += session.durationMinutes() / 60.0;
            totalInputTokens += session.inputTokens();
            totalOutputTokens += session.outputTokens();
            gitCommits += session.gitCommits();
            gitPushes += session.gitPushes();

            // New stats aggregation
            totalInterruptions += session.userInterruptions();
            totalToolErrors += session.toolErrors();
            mergeCounts(toolErrorCategories, session.toolErrorCategories());
            if (session.userResponseTimes() != null) {
                allResponseTimes.addAll(session.userResponseTimes());
            }
            if (session.usesTaskAgent()) sessionsUsingTaskAgent++;
            if (session.usesMcp()) sessionsUsingMcp++;
            if (session.usesWebSearch()) sessionsUsingWebSearch++;
            if (session.usesWebFetch()) sessionsUsingWebFetch++;

            // Additional stats aggregation
            totalLinesAdded += session.linesAdded();
            totalLinesRemoved += session.linesRemoved();
            totalFilesModified += session.filesModified();
            if (session.messageHours() != null) {
                allMessageHours.addAll(session.messageHours());
            }

            mergeCounts(toolCounts, session.toolCounts());
            mergeCounts(languages, session.languages());

            if (StringUtils.isNotEmpty(session.projectPath())) {
                projects.merge(session.projectPath(), 1L, Long::sum);
            }

            SessionFacets sessionFacets = facets.get(session.sessionId());
            if (sessionFacets != null) {
                // Goal categories
                mergePositiveCounts(goalCategories, sessionFacets.goalCategories());

                // Outcomes
                outcomes.merge(sessionFacets.outcome(), 1L, Long::sum);

                // Satisfaction counts
                mergePositiveCounts(satisfaction, sessionFacets.userSatisfactionCounts());

                // Helpfulness
                helpfulness.merge(sessionFacets.claudeHelpfulness(), 1L, Long::sum);

                // Session types
                sessionTypes.merge(sessionFacets.sessionType(), 1L, Long::sum);

                // Friction counts
                mergePositiveCounts(friction, sessionFacets.frictionCounts());

                // Success factors
                if (!Strings.CS.equals("none", sessionFacets.primarySuccess())) {
                    success.merge(sessionFacets.primarySuccess(), 1L, Long::sum);
                }
            }

            if (sessionSummaries.size() < SESSION_SUMMARY_CAP) {
                String summary = StringUtils.isNotEmpty(session.summary())
                    ? session.summary()
                    : slice(nullToEmpty(session.firstPrompt()), 100);
                sessionSummaries.add(new AggregatedData.SessionSummary(
                    slice(nullToEmpty(session.sessionId()), 8),
                    dayOf(session.startTime()),
                    summary,
                    sessionFacets != null ? sessionFacets.underlyingGoal() : null));
            }
        }

        dates.sort(Comparator.naturalOrder());
        String rangeStart = dates.isEmpty() ? "" : dayOf(dates.getFirst());
        String rangeEnd = dates.isEmpty() ? "" : dayOf(dates.getLast());

        // Calculate response time stats
        double medianResponseTime = 0;
        double avgResponseTime = 0;
        if (!allResponseTimes.isEmpty()) {
            List<Double> sorted = new ArrayList<>(allResponseTimes);
            sorted.sort(Comparator.naturalOrder());
            medianResponseTime = sorted.get(sorted.size() / 2);
            avgResponseTime = allResponseTimes.stream().mapToDouble(Double::doubleValue).sum()
                / allResponseTimes.size();
        }

        // Calculate days active and messages per day
        Set<String> uniqueDays = new HashSet<>();
        for (String d : dates) {
            uniqueDays.add(dayOf(d));
        }
        long daysActive = uniqueDays.size();
        double messagesPerDay = daysActive > 0
            ? Math.round((totalMessages / (double) daysActive) * 10) / 10.0
            : 0;

        return AggregatedData.builder(sessions.size(), facets.size(),
                new AggregatedData.DateRange(rangeStart, rangeEnd))
            .totalMessages(totalMessages)
            .totalDurationHours(totalDurationHours)
            .totalInputTokens(totalInputTokens)
            .totalOutputTokens(totalOutputTokens)
            .toolCounts(toolCounts)
            .languages(languages)
            .gitCommits(gitCommits)
            .gitPushes(gitPushes)
            .projects(projects)
            .goalCategories(goalCategories)
            .outcomes(outcomes)
            .satisfaction(satisfaction)
            .helpfulness(helpfulness)
            .sessionTypes(sessionTypes)
            .friction(friction)
            .success(success)
            .sessionSummaries(sessionSummaries)
            .totalInterruptions(totalInterruptions)
            .totalToolErrors(totalToolErrors)
            .toolErrorCategories(toolErrorCategories)
            .userResponseTimes(allResponseTimes)
            .medianResponseTime(medianResponseTime)
            .avgResponseTime(avgResponseTime)
            .sessionsUsingTaskAgent(sessionsUsingTaskAgent)
            .sessionsUsingMcp(sessionsUsingMcp)
            .sessionsUsingWebSearch(sessionsUsingWebSearch)
            .sessionsUsingWebFetch(sessionsUsingWebFetch)
            .totalLinesAdded(totalLinesAdded)
            .totalLinesRemoved(totalLinesRemoved)
            .totalFilesModified(totalFilesModified)
            .daysActive(daysActive)
            .messagesPerDay(messagesPerDay)
            .messageHours(allMessageHours)
            .multiClauding(detectMultiClauding(sessions))
            .build();
    }


    public static AggregatedData.MultiClauding detectMultiClauding(List<SessionMeta> sessions) {
        List<Stamp> allSessionMessages = new ArrayList<>();
        for (SessionMeta session : sessions) {
            if (session.userMessageTimestamps() == null) continue;
            for (String timestamp : session.userMessageTimestamps()) {
                Long ts = parseMillis(timestamp);
                if (ts != null) {
                    allSessionMessages.add(new Stamp(ts, session.sessionId()));
                }
            }
        }

        allSessionMessages.sort(Comparator.comparingLong(Stamp::ts));

        Set<String> multiClaudeSessionPairs = new HashSet<>();
        Set<String> messagesDuringMulticlaude = new HashSet<>();

        // Sliding window: sessionLastIndex tracks the most recent index for each session
        int windowStart = 0;
        Map<String, Integer> sessionLastIndex = new HashMap<>();

        for (int i = 0; i < allSessionMessages.size(); i++) {
            Stamp msg = allSessionMessages.get(i);

            // Shrink window from the left
            while (windowStart < i
                && msg.ts() - allSessionMessages.get(windowStart).ts() > OVERLAP_WINDOW_MS) {
                Stamp expiring = allSessionMessages.get(windowStart);
                Integer lastIdx = sessionLastIndex.get(expiring.sessionId());
                if (lastIdx != null && lastIdx == windowStart) {
                    sessionLastIndex.remove(expiring.sessionId());
                }
                windowStart++;
            }

            // Check if this session appeared earlier in the window (pattern: s1 -> s2 -> s1)
            Integer prevIndex = sessionLastIndex.get(msg.sessionId());
            if (prevIndex != null) {
                for (int j = prevIndex + 1; j < i; j++) {
                    Stamp between = allSessionMessages.get(j);
                    if (!between.sessionId().equals(msg.sessionId())) {
                        multiClaudeSessionPairs.add(pairKey(msg.sessionId(), between.sessionId()));
                        messagesDuringMulticlaude.add(
                            allSessionMessages.get(prevIndex).ts() + ":" + msg.sessionId());
                        messagesDuringMulticlaude.add(between.ts() + ":" + between.sessionId());
                        messagesDuringMulticlaude.add(msg.ts() + ":" + msg.sessionId());
                        break;
                    }
                }
            }

            sessionLastIndex.put(msg.sessionId(), i);
        }

        Set<String> sessionsWithOverlaps = new HashSet<>();
        for (String pair : multiClaudeSessionPairs) {
            String[] parts = pair.split(":");
            if (parts.length > 0 && !parts[0].isEmpty()) sessionsWithOverlaps.add(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) sessionsWithOverlaps.add(parts[1]);
        }

        return new AggregatedData.MultiClauding(
            multiClaudeSessionPairs.size(),
            sessionsWithOverlaps.size(),
            messagesDuringMulticlaude.size());
    }

    private record Stamp(long ts, String sessionId) {}


    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }


    private static void mergeCounts(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) return;
        for (Map.Entry<String, Long> e : source.entrySet()) {
            if (e.getValue() != null) {
                target.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
    }


    private static void mergePositiveCounts(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) return;
        for (Map.Entry<String, Long> e : source.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                target.merge(e.getKey(), e.getValue(), Long::sum);
            }
        }
    }


    private static String dayOf(String isoTimestamp) {
        if (isoTimestamp == null) return "";
        int t = isoTimestamp.indexOf('T');
        return t < 0 ? isoTimestamp : isoTimestamp.substring(0, t);
    }

    private static String slice(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Long parseMillis(String timestamp) {
        if (StringUtils.isEmpty(timestamp)) return null;
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception _) {
            try {
                return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli();
            } catch (Exception _) {
                return null;
            }
        }
    }
}
