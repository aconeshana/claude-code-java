package com.claudecode.services.compact;

import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Decides when auto-compaction should fire and computes the token-warning state used for UI display
 * and the pre-emptive blocking-limit guard.
 */
interface AutoCompactStrategy {

    /**
     * Token buffer subtracted from effective context window to get the auto-compact threshold.
     */
    long AUTOCOMPACT_BUFFER_TOKENS = 13_000;

    /** Default context window size (200K tokens for Claude models). */
    long DEFAULT_CONTEXT_WINDOW = 200_000;

    /**
     * Tokens reserved for LLM compact summary output during compaction.
     */
    long SYSTEM_PROMPT_RESERVE = 20_000;

    /**
     * Token buffer below the auto-compact threshold at which a yellow warning is shown.
     */
    long WARNING_THRESHOLD_BUFFER_TOKENS = 20_000;

    /**
     * Token buffer below the auto-compact threshold at which a red error warning is shown.
     */
    long ERROR_THRESHOLD_BUFFER_TOKENS = 20_000;

    /**
     * Token buffer below the effective context window for the hard blocking limit.
     */
    long MANUAL_COMPACT_BUFFER_TOKENS = 3_000;


    long getAutoCompactThreshold(String model);


    String getAutoCompactSource(String model);

    /**
     * Check whether auto-compaction should be triggered.
     */
    default boolean shouldTrigger(List<Message> messages, String model, String querySource, boolean autoCompactEnabled) {
        return shouldTrigger(messages, model, querySource, autoCompactEnabled, 0L);
    }

    /**
     * Variant that accounts for {@code snipTokensFreed} (tokens already removed by the snip step).
     */
    boolean shouldTrigger(List<Message> messages, String model, String querySource,
                          boolean autoCompactEnabled, long snipTokensFreed);

    /**
     * Computes the token warning state for UI display and blocking-limit enforcement.
     */
    CompactService.TokenWarningState calculateTokenWarningState(long tokenUsage, String model, boolean autoCompactEnabled);

    boolean isAtBlockingLimit(long tokenUsage, String model, boolean autoCompactEnabled);

    boolean isAtBlockingLimit(List<Message> messages, String model, boolean autoCompactEnabled);
}
