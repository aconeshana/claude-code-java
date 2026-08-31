package com.claudecode.services.compact;

import com.claudecode.services.config.RuntimeSettings;

/**
 * Config for time-based microcompact — clear old tool results before the API call when the gap
 * since the last main-loop assistant message exceeds a threshold: the server-side prompt cache (1h
 * TTL) has almost certainly expired, so the full prefix gets rewritten anyway; shrinking it first
 * saves cache_creation tokens.
 */
record TimeBasedMcConfig(boolean enabled, int gapThresholdMinutes, int keepRecent) {

    static final TimeBasedMcConfig DEFAULTS = new TimeBasedMcConfig(false, 60, 5);

    static TimeBasedMcConfig load() {
        return new TimeBasedMcConfig(
            RuntimeSettings.loadTimeBasedMicrocompactEnabled(),
            RuntimeSettings.loadTimeBasedMicrocompactGapMinutes(),
            RuntimeSettings.loadTimeBasedMicrocompactKeepRecent());
    }
}
