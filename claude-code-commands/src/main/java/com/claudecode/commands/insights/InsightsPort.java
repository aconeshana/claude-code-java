package com.claudecode.commands.insights;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * Command-facing boundary for generating the expensive usage-insights report.
 */
@FunctionalInterface
public interface InsightsPort {

    record Stats(long totalSessions, Long totalSessionsScanned, long totalMessages,
                 double totalDurationHours, long gitCommits,
                 String startDate, String endDate) { }

    record Report(Map<String, JsonNode> insights, String insightsJson,
                  Path htmlPath, Stats stats) {
        public Report {
            insights = insights == null ? Map.of() : Map.copyOf(insights);
        }
    }

    Report generate() throws Exception;
}
