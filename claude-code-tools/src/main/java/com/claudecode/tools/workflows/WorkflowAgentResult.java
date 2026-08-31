package com.claudecode.tools.workflows;

/** Raw subagent return value and usage counters consumed by the workflow runtime. */
public record WorkflowAgentResult(
    String output,
    long tokensUsed,
    int toolUseCount,
    long durationMs,
    long outputTokens,
    String apiError,
    String stopReason,
    boolean structuredOutputPresent) {

    public WorkflowAgentResult {
        output = output == null ? "" : output;
    }

/**
     * Compatibility shape from before output-token/API-error.
     */
    public WorkflowAgentResult(String output, long tokensUsed, int toolUseCount,
                               long durationMs) {
        this(output, tokensUsed, toolUseCount, durationMs, tokensUsed, null, null, false);
    }

    /** Compatibility shape from before stop/structured metadata. */
    public WorkflowAgentResult(String output, long tokensUsed, int toolUseCount,
                               long durationMs, long outputTokens, String apiError) {
        this(output, tokensUsed, toolUseCount, durationMs, outputTokens, apiError,
            null, false);
    }

    public static WorkflowAgentResult of(String output) {
        return new WorkflowAgentResult(output, 0, 0, 0, 0, null, null, false);
    }

    public static WorkflowAgentResult of(String output, long tokensUsed,
                                         int toolUseCount, long durationMs,
                                         long outputTokens) {
        return new WorkflowAgentResult(output, tokensUsed, toolUseCount,
            durationMs, outputTokens, null, null, false);
    }

    public static WorkflowAgentResult of(String output, long tokensUsed,
                                         int toolUseCount, long durationMs,
                                         long outputTokens, String stopReason,
                                         boolean structuredOutputPresent) {
        return new WorkflowAgentResult(output, tokensUsed, toolUseCount,
            durationMs, outputTokens, null, stopReason, structuredOutputPresent);
    }

    public static WorkflowAgentResult apiError(String message, long tokensUsed,
                                               int toolUseCount, long durationMs,
                                               long outputTokens) {
        return new WorkflowAgentResult("", tokensUsed, toolUseCount, durationMs,
            outputTokens, message == null ? "API error" : message, null, false);
    }
}
