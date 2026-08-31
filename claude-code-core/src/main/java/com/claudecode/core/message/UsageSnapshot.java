package com.claudecode.core.message;

/**
 * Snapshot of per-session token / USD-budget / output-token usage, feeding the {@code token_usage},
 * {@code budget_usd}, and {@code output_token_usage} attachments.
 */
public record UsageSnapshot(
    long tokenUsed,
    long tokenTotal,
    long tokenRemaining,
    double budgetUsed,
    double budgetTotal,
    double budgetRemaining,
    long outputTurn,
    Long outputBudget,
    long outputSession
) {
}
