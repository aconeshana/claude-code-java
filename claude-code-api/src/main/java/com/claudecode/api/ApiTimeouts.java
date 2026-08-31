package com.claudecode.api;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * Environment-backed API timeout policy.
 *
 * <ul>
 *   <li>{@code API_TIMEOUT_MS}, default
 *       600 seconds.</li>
 *   <li>opt-in streaming idle watchdog,
 *       default 90 seconds when enabled, and
 *       {@code getNonstreamingFallbackTimeoutMs} for the
 *       streaming-to-non-streaming recovery.</li>
 * </ul>
 */
public final class ApiTimeouts {

    private static final Duration DEFAULT_API_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration DEFAULT_FALLBACK_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration REMOTE_FALLBACK_TIMEOUT = Duration.ofSeconds(120);

    private ApiTimeouts() {}

    static Duration apiTimeout() {
        return resolveApiTimeout(SubprocessEnvironment.get("API_TIMEOUT_MS"));
    }

    /**
     * Per-attempt timeout for the non-streaming fallback.
     */
    public static Duration nonStreamingFallbackTimeout() {
        return resolveNonStreamingFallbackTimeout(
            SubprocessEnvironment.get("API_TIMEOUT_MS"),
            SubprocessEnvironment.get("CLAUDE_CODE_REMOTE"));
    }

    /** Pure resolution, split out so tests need not mutate the process environment. */
    static Duration resolveNonStreamingFallbackTimeout(String apiTimeoutRaw, String remoteRaw) {
        return positiveMillis(apiTimeoutRaw,
            EnvUtils.isEnvTruthy(remoteRaw)
                ? REMOTE_FALLBACK_TIMEOUT : DEFAULT_FALLBACK_TIMEOUT);
    }

    static StreamWatchdog watchdog() {
        return resolveWatchdog(
            SubprocessEnvironment.get("CLAUDE_ENABLE_STREAM_WATCHDOG"),
            SubprocessEnvironment.get("CLAUDE_STREAM_IDLE_TIMEOUT_MS"));
    }

    static Duration resolveApiTimeout(String raw) {
        return positiveMillis(raw, DEFAULT_API_TIMEOUT);
    }

    static StreamWatchdog resolveWatchdog(String enabledRaw, String timeoutRaw) {
        return new StreamWatchdog(
            EnvUtils.isEnvTruthy(enabledRaw),
            positiveMillis(timeoutRaw, DEFAULT_STREAM_IDLE_TIMEOUT));
    }

    private static Duration positiveMillis(String raw, Duration fallback) {
        if (StringUtils.isBlank(raw)) return fallback;
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Duration.ofMillis(millis) : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    record StreamWatchdog(boolean enabled, Duration idleTimeout) {
        Duration warningTimeout() {
            return idleTimeout.dividedBy(2);
        }
    }
}
