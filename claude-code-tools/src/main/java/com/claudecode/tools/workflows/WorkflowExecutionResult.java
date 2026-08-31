package com.claudecode.tools.workflows;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Completed workflow return value plus aggregate usage and progress metadata. */
public record WorkflowExecutionResult(
    JsonNode value,
    int agentCalls,
    long tokensUsed,
    int toolUseCount,
    long durationMs,
    List<String> logs,
    List<String> failures,
    String lastPhase,
    List<WorkflowAgentCacheEntry> agentCache) {

    public WorkflowExecutionResult {
        logs = logs == null ? List.of() : List.copyOf(logs);
        failures = failures == null ? List.of() : List.copyOf(failures);
        agentCache = agentCache == null ? List.of() : List.copyOf(agentCache);
    }
}
