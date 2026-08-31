package com.claudecode.runtime.query;


import com.claudecode.core.message.ToolUseSummaryMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Mutable-by-replacement per-iteration state for the query loop.
 */
record QueryState(
    AutoCompactTrackingState autoCompactTracking,
    int maxOutputTokensRecoveryCount,
    Integer maxOutputTokensOverride,
    CompletableFuture<ToolUseSummaryMessage> pendingToolUseSummary,
    Boolean stopHookActive,
    int turnCount,
    Continue transition,
    JsonNode structuredOutput) {

    /** Creates the initial state at query start. */
    public static QueryState initial() {
        return new QueryState(null, 0, null, null, null, 1, null, null);
    }

    public QueryState withAutoCompactTracking(AutoCompactTrackingState a) {
        return new QueryState(a, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            pendingToolUseSummary, stopHookActive, turnCount, transition, structuredOutput);
    }

    public QueryState withMaxOutputTokensRecoveryCount(int n) {
        return new QueryState(autoCompactTracking, n, maxOutputTokensOverride,
            pendingToolUseSummary, stopHookActive, turnCount, transition, structuredOutput);
    }

    public QueryState withMaxOutputTokensOverride(Integer value) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, value,
            pendingToolUseSummary, stopHookActive, turnCount, transition, structuredOutput);
    }

    public QueryState withPendingToolUseSummary(CompletableFuture<ToolUseSummaryMessage> p) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            p, stopHookActive, turnCount, transition, structuredOutput);
    }

    public QueryState withStopHookActive(Boolean b) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            pendingToolUseSummary, b, turnCount, transition, structuredOutput);
    }

    public QueryState withTurnCount(int t) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            pendingToolUseSummary, stopHookActive, t, transition, structuredOutput);
    }

    public QueryState withTransition(Continue c) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            pendingToolUseSummary, stopHookActive, turnCount, c, structuredOutput);
    }

    /**
     * Carries the most recent {@code StructuredOutput} tool payload through to
     * the final {@code SDKMessage.Result.structuredOutput}.
     */
    public QueryState withStructuredOutput(JsonNode so) {
        return new QueryState(autoCompactTracking, maxOutputTokensRecoveryCount, maxOutputTokensOverride,
            pendingToolUseSummary, stopHookActive, turnCount, transition, so);
    }
}
