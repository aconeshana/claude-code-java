package com.claudecode.core.metrics;

import com.claudecode.core.annotation.Explanation;

/**
 * Compact, non-chain JSONL record ({@code type = "java-llm-request"}) capturing one LLM API
 * invocation's 4-protocol routing identity, sanitized wire options, retry/fallback chain,
 * and SHA-256 prompt-header reference for the Trajectory inspector.
 */
@Explanation("Persists 4-protocol request metadata and header epoch hashes for WebUI Trajectory")
public record LlmRequestTraceRecord(
        int schemaVersion,
        String sessionId,
        long turn,
        long step,
        /** {@code "assistant"} or {@code "compaction"}. */
        String purpose,
        long startedAt,
        long completedAt,
        String configuredProtocol,
        String effectiveProtocol,
        String providerLabel,
        String requestedModel,
        String respondedModel,
        String headerSha256,
        /** Compact JSON object of sanitized protocol-native generation options. */
        String optionsJson,
        /** Optional JSON array of adaptive fallback / HTTP retry steps. */
        String attemptsJson,
        int retry,
        int maxRetries,
        long retryDelayMs,
        String status,
        String error,
        String errorCode) {

    public static final int SCHEMA_VERSION = 1;
    public static final String TRANSCRIPT_TYPE = "java-llm-request";
}
