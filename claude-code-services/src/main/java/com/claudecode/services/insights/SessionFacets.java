package com.claudecode.services.insights;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;


@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionFacets(
    String sessionId,
    String underlyingGoal,
    Map<String, Long> goalCategories,
    String outcome,
    Map<String, Long> userSatisfactionCounts,
    String claudeHelpfulness,
    String sessionType,
    Map<String, Long> frictionCounts,
    String frictionDetail,
    String primarySuccess,
    String briefSummary,
    List<String> userInstructionsToClaude   // nullable/optional
) {


    public boolean isValid() {
        return underlyingGoal != null
            && outcome != null
            && briefSummary != null
            && goalCategories != null
            && userSatisfactionCounts != null
            && frictionCounts != null;
    }
}
