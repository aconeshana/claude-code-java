package com.claudecode.tools.agent;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import java.util.List;
import java.util.Optional;
import com.claudecode.tools.worktree.WorktreeInfo;

/**
 * Successful/normal result of an agent execution.
 *
 * <ul>
 *   <li>structured Agent result
 *       usage/content metadata, resolved model and the required agent id.</li>
 *   <li>task-progress token
 *       accounting ({@code latestInputTokens + cumulativeOutputTokens}), kept
 *       separately from the final assistant turn's Agent-result usage.</li>
 *   <li>optional
 *       worktree path and branch metadata returned alongside execution output.</li>
 * </ul>
 */
public record AgentExecutionResult(
    String output,
    long tokensUsed,
    long outputTokens,
    double costUsd,
    int toolUseCount,
    long durationMs,
    String agentIdValue,
    WorktreeInfo worktree,
    List<Message> conversationMessages,
    SubAgentTermination termination,
    String terminalError,
    String stopReason,
    boolean structuredOutputPresent,
    Usage usage,
    String resolvedModel,
    long progressTokens
) implements SubAgentResult {

    public AgentExecutionResult {
        conversationMessages = conversationMessages == null
            ? List.of()
            : List.copyOf(conversationMessages);
        usage = usage == null ? Usage.EMPTY : usage;
        termination = termination == null ? SubAgentTermination.COMPLETED : termination;
    }

    public static Builder builder(String output) {
        return new Builder(output);
    }

    public static final class Builder {
        private final String output;
        private long tokensUsed;
        private long outputTokens;
        private double costUsd;
        private int toolUseCount;
        private long durationMs;
        private String agentIdValue;
        private WorktreeInfo worktree;
        private List<Message> conversationMessages = List.of();
        private SubAgentTermination termination = SubAgentTermination.COMPLETED;
        private String terminalError;
        private String stopReason;
        private boolean structuredOutputPresent;
        private Usage usage = Usage.EMPTY;
        private String resolvedModel;
        private long progressTokens;

        private Builder(String output) { this.output = output; }

        public Builder tokensUsed(long value) { tokensUsed = value; return this; }
        public Builder outputTokens(long value) { outputTokens = value; return this; }
        public Builder costUsd(double value) { costUsd = value; return this; }
        public Builder toolUseCount(int value) { toolUseCount = value; return this; }
        public Builder durationMs(long value) { durationMs = value; return this; }
        public Builder agentId(String value) { agentIdValue = value; return this; }
        public Builder worktree(WorktreeInfo value) { worktree = value; return this; }
        public Builder conversationMessages(List<Message> value) { conversationMessages = value; return this; }
        public Builder termination(SubAgentTermination value) { termination = value; return this; }
        public Builder terminalError(String value) { terminalError = value; return this; }
        public Builder stopReason(String value) { stopReason = value; return this; }
        public Builder structuredOutputPresent(boolean value) { structuredOutputPresent = value; return this; }
        public Builder usage(Usage value) { usage = value; return this; }
        public Builder resolvedModel(String value) { resolvedModel = value; return this; }
        public Builder progressTokens(long value) { progressTokens = value; return this; }

        public AgentExecutionResult build() {
            return new AgentExecutionResult(output, tokensUsed, outputTokens,
                costUsd, toolUseCount, durationMs, agentIdValue, worktree,
                conversationMessages, termination, terminalError, stopReason, structuredOutputPresent,
                usage, resolvedModel, progressTokens);
        }
    }

    @Override
    public Optional<String> agentId() {
        return Optional.ofNullable(agentIdValue);
    }

    @Override
    public Optional<String> worktreePath() {
        return worktree == null ? Optional.empty() : Optional.ofNullable(worktree.path());
    }

    @Override
    public Optional<String> worktreeBranch() {
        return worktree == null ? Optional.empty() : Optional.ofNullable(worktree.branch());
    }

    @Override
    public Optional<List<Message>> conversation() {
        return Optional.of(conversationMessages);
    }

    @Override
    public Optional<String> error() {
        return termination == SubAgentTermination.COMPLETED
            ? Optional.empty()
            : Optional.ofNullable(terminalError);
    }

    @Override
    public boolean isError() {
        return termination != SubAgentTermination.COMPLETED;
    }

    static AgentExecutionResult empty(String output) {
        return of(output, 0, 0.0, 0, 0);
    }

    static AgentExecutionResult of(String output, long tokensUsed, double costUsd) {
        return of(output, tokensUsed, costUsd, 0, 0);
    }

    static AgentExecutionResult of(String output, long tokensUsed, double costUsd,
                                   int toolUseCount, long durationMs) {
        return builder(output).tokensUsed(tokensUsed).outputTokens(tokensUsed)
            .costUsd(costUsd).toolUseCount(toolUseCount).durationMs(durationMs)
            .usage(new Usage(0, Math.max(0L, tokensUsed), 0, 0))
            .progressTokens(tokensUsed).build();
    }

    static AgentExecutionResult withWorktree(String output, String path, String branch) {
        return builder(output).worktree(new WorktreeInfo(path, branch)).build();
    }
}
