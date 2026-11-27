package com.claudecode.core.util;

/** Overflow-safe arithmetic primitives shared across token accounting and metrics. */
public final class MathUtils {

    private MathUtils() {}

    /**
     * Adds {@code left} and {@code right}, clamping to {@link Long#MAX_VALUE} on positive overflow
     * and {@link Long#MIN_VALUE} on negative overflow.
     */
    public static long saturatedAdd(long left, long right) {
        long sum = left + right;
        if (((left ^ sum) & (right ^ sum)) < 0L) {
            return left >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return sum;
    }
}
