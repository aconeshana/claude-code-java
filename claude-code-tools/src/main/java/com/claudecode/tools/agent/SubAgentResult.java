package com.claudecode.tools.agent;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import java.util.List;
import java.util.Optional;

/**
 * Result protocol for an agent invocation.
 */
public sealed interface SubAgentResult permits AgentExecutionResult, AgentFailureResult {

    String output();

    default long tokensUsed() { return 0; }

    default long outputTokens() { return 0; }

    default double costUsd() { return 0.0; }

    default int toolUseCount() { return 0; }

    default long durationMs() { return 0; }

    default Usage usage() {
        long output = Math.max(0L, outputTokens());
        long input = Math.max(0L, tokensUsed() - output);
        return new Usage(input, output, 0, 0);
    }

    /** Foreground/background task progress token count (latest input + cumulative outputs). */
    default long progressTokens() { return tokensUsed(); }

    default String resolvedModel() { return null; }

    default Optional<String> agentId() { return Optional.empty(); }

    default Optional<String> worktreePath() { return Optional.empty(); }

    default Optional<String> worktreeBranch() { return Optional.empty(); }

    default Optional<String> error() { return Optional.empty(); }

    default Optional<List<Message>> conversation() { return Optional.empty(); }

    default String stopReason() { return null; }

    default SubAgentTermination termination() {
        return isError() ? SubAgentTermination.FAILED : SubAgentTermination.COMPLETED;
    }

    default boolean structuredOutputPresent() { return false; }

    default boolean isError() { return error().isPresent(); }

    static SubAgentResult of(String output) {
        return AgentExecutionResult.empty(output);
    }

    static SubAgentResult of(String output, long tokensUsed, double costUsd) {
        return AgentExecutionResult.of(output, tokensUsed, costUsd);
    }

    static SubAgentResult of(String output, long tokensUsed, double costUsd,
                             int toolUseCount, long durationMs) {
        return AgentExecutionResult.of(output, tokensUsed, costUsd, toolUseCount, durationMs);
    }

    static SubAgentResult error(String message) {
        return new AgentFailureResult(message);
    }

    static SubAgentResult withWorktree(String output, String worktreePath) {
        return AgentExecutionResult.withWorktree(output, worktreePath, null);
    }

    static SubAgentResult withWorktree(String output, String worktreePath, String worktreeBranch) {
        return AgentExecutionResult.withWorktree(output, worktreePath, worktreeBranch);
    }
}
