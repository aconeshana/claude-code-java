package com.claudecode.core.memdir;

import java.time.Instant;

/**
 * Memory staleness helpers. Ports.
 * <p>
 * Used to warn the model when it reads an auto-memory file that may be
 * out of date — models reason poorly about raw timestamps but respond to
 * "N days ago" phrasing (see {@link #freshnessText}).
 */
public final class MemoryAge {

    private static final long DAY_MS = 86_400_000L;

    private MemoryAge() {}

    /**
     * Days elapsed since {@code mtimeMs}. Floor-rounded — 0 for today, 1 for
     * yesterday, 2+ for older. Negative inputs (future mtime, clock skew)
     * clamp to 0. matches {@code memoryAgeDays}.
     */
    public static long ageDays(long mtimeMs, long nowMs) {
        return Math.max(0, Math.floorDiv(nowMs - mtimeMs, DAY_MS));
    }

/** Human-readable age string. matches {@code memoryAge}. */
    public static String age(long mtimeMs, long nowMs) {
        long d = ageDays(mtimeMs, nowMs);
        if (d == 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    /**
     * Plain-text staleness caveat for memories &gt;1 day old. Returns {@code ""}
     * for fresh (today/yesterday) memories — a warning there is noise.
     * matches {@code memoryFreshnessText}.
     */
    public static String freshnessText(long mtimeMs, long nowMs) {
        long d = ageDays(mtimeMs, nowMs);
        if (d <= 1) return "";
        return "This memory is " + d + " days old. "
            + "Memories are point-in-time observations, not live state — "
            + "claims about code behavior or file:line citations may be outdated. "
            + "Verify against current code before asserting as fact.";
    }

    /**
     * Per-memory staleness note wrapped in {@code <system-reminder>} tags.
     */
    public static String freshnessNote(long mtimeMs, long nowMs) {
        String text = freshnessText(mtimeMs, nowMs);
        if (text.isEmpty()) return "";
        return "<system-reminder>" + text + "</system-reminder>\n";
    }

    public static String freshnessNote(long mtimeMs) {
        return freshnessNote(mtimeMs, Instant.now().toEpochMilli());
    }
}
