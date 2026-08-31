package com.claudecode.tools.bash;

import java.util.function.Function;

import com.claudecode.core.process.SubprocessEnvironment;

import org.apache.commons.lang3.StringUtils;
/**
 * Shared Bash/PowerShell timeout policy.
 *
 * <ul>
 *   <li>{@code getDefaultBashTimeoutMs} and
 *       {@code getMaxBashTimeoutMs}, including environment overrides and the
 *       invariant that the maximum is never below the default.</li>
 * </ul>
 */
public final class BashTimeouts {
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;
    public static final long MAX_TIMEOUT_MS = 600_000L;

    private BashTimeouts() {}

    public static long defaultTimeoutMs() {
        return defaultTimeoutMs(SubprocessEnvironment::get);
    }

    public static long defaultTimeoutMs(Function<String, String> envLookup) {
        return positiveIntegerPrefix(envLookup.apply("BASH_DEFAULT_TIMEOUT_MS"), DEFAULT_TIMEOUT_MS);
    }

    public static long maxTimeoutMs(Function<String, String> envLookup) {
        long defaultValue = defaultTimeoutMs(envLookup);
        long configured = positiveIntegerPrefix(envLookup.apply("BASH_MAX_TIMEOUT_MS"), MAX_TIMEOUT_MS);
        return Math.max(configured, defaultValue);
    }

    private static long positiveIntegerPrefix(String raw, long fallback) {
        if (StringUtils.isEmpty(raw)) return fallback;
        int end = 0;
        while (end < raw.length() && Character.isWhitespace(raw.charAt(end))) end++;
        int start = end;
        if (end < raw.length() && (raw.charAt(end) == '+' || raw.charAt(end) == '-')) end++;
        int digitsStart = end;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) end++;
        if (end == digitsStart) return fallback;
        try {
            long parsed = Long.parseLong(raw.substring(start, end));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
