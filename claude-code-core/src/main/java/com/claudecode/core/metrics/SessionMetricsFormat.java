package com.claudecode.core.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display formulas for session metrics. */
public final class SessionMetricsFormat {
    private SessionMetricsFormat() {}

    public static String formatTokens(long tokens) {
        if (tokens < 1_000) return Long.toString(tokens);
        if (tokens < 1_000_000) return scaled(tokens / 1_000.0) + "K";
        return scaled(tokens / 1_000_000.0) + "M";
    }

    private static String scaled(double value) {
        double rounded = value >= 100 ? Math.round(value) : Math.round(value * 10) / 10.0;
        return decimalText(rounded);
    }

    public static String formatDuration(double millis) {
        double seconds = Math.max(0, millis) / 1_000.0;
        if (seconds < 60) return decimalText(Math.round(seconds * 10) / 10.0) + "s";
        long whole = Math.round(seconds);
        return whole / 60 + "m" + whole % 60 + "s";
    }

    public static String formatTokensPerSecond(double tps) {
        double clamped = Math.max(0, tps);
        double rounded = clamped >= 10 ? Math.round(clamped) : Math.round(clamped * 10) / 10.0;
        return decimalText(rounded);
    }

    /**
     * Integer rounding unless it would falsely display 100; in that case add
     * the minimum decimal precision that stays below 100. Only a full hit is 100.
     */
    public static String cacheHitPercent(SessionMetricsSnapshot value) {
        long denominator = value.billedInputTokens();
        if (denominator == 0) return null;
        long missed = saturatedAdd(value.uncachedInputTokens(), value.cacheWriteTokens());
        if (missed == 0) return "100";

        BigDecimal ratio = BigDecimal.valueOf(value.cacheReadTokens())
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(denominator), 64, RoundingMode.HALF_UP);
        BigDecimal integer = ratio.setScale(0, RoundingMode.HALF_UP);
        if (integer.compareTo(BigDecimal.valueOf(100)) < 0) return integer.toPlainString();

        for (int scale = 1; scale < 64; scale++) {
            BigDecimal rounded = ratio.setScale(scale, RoundingMode.HALF_UP);
            if (rounded.compareTo(BigDecimal.valueOf(100)) < 0) {
                return rounded.stripTrailingZeros().toPlainString();
            }
        }
        return ratio.stripTrailingZeros().toPlainString();
    }

    private static String decimalText(double value) {
        if (value == Math.rint(value)) return Long.toString((long) value);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }
}
