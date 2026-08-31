package com.claudecode.tools.tasks;

import com.claudecode.tools.agent.SubAgentResult;

/**
 * Aggregated token/cost usage of a running or completed background sub-agent run.
 */
public record TaskUsage(long totalTokens, int toolUses, long durationMs) {

    /** Builds a usage record from the Java {@link SubAgentResult} fields. */
    public static TaskUsage from(SubAgentResult result) {
        return new TaskUsage(result.progressTokens(), result.toolUseCount(), result.durationMs());
    }
}
