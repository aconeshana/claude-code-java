package com.claudecode.core.metrics;

import com.claudecode.core.util.MathUtils;

/** Canonical whole-session metrics and centrally derived HUD values. */
public record SessionMetricsSnapshot(
    boolean complete,
    long turns,
    long steps,
    long llmMs,
    long toolMs,
    long ttftMs,
    long ttftSteps,
    long decodeMs,
    long decodeTokens,
    long uncachedInputTokens,
    long outputTokens,
    long cacheWriteTokens,
    long cacheReadTokens
) {
    public static final SessionMetricsSnapshot INCOMPLETE = new SessionMetricsSnapshot(
        false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public long billedInputTokens() {
        return MathUtils.saturatedAdd(uncachedInputTokens,
            MathUtils.saturatedAdd(cacheWriteTokens, cacheReadTokens));
    }

    public Double ttftAverageMs() {
        return ttftSteps > 0 ? ttftMs / (double) ttftSteps : null;
    }

    public Double tokensPerSecond() {
        return decodeMs > 0 ? decodeTokens / (decodeMs / 1_000.0) : null;
    }
}
