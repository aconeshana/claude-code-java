package com.claudecode.core.text;

import org.apache.commons.lang3.StringUtils;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure display formatters — leaf-safe (no UI dependencies).
 *
 * <ul>
 *   <li>Covers: </li>
 *   <li>Covers: </li>
 *   <li>Covers: </li>
 *   <li>Covers: </li>
 *   <li>Covers:  / {@code formatRelativeTimeAgo}
 *       (long / short / narrow, numeric always / auto, including future instants)</li>
 *   <li>these
 *       format claude.ai OAuth subscription-limit reset times. This API-key-only port has no
 *       subscription profile or reset timestamp source; {@code /usage} reports that boundary.</li>
 *   <li>grapheme-safe, terminal-column-aware
 *       truncation, middle path truncation, and wrapping.</li>
 *   <li>Covers:  compact notation (upper/lower-case suffix)</li>
 *   <li>{@code yyyy-MM-dd-HHmmss}</li>
 *   <li>Covers: {@code Date.prototype.toISOString} shape — {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}</li>
 * </ul>
 * All methods are static; this class is not instantiated.
 */
public final class FormatUtils {

    private static final ThreadLocal<NumberFormat> COMPACT_ONE_DECIMAL =
        ThreadLocal.withInitial(() -> compactNumberFormatter(1));
    private static final ThreadLocal<NumberFormat> COMPACT_OPTIONAL_DECIMAL =
        ThreadLocal.withInitial(() -> compactNumberFormatter(0));

    private FormatUtils() {}

    private static NumberFormat compactNumberFormatter(int minimumFractionDigits) {
        NumberFormat formatter = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
        formatter.setMinimumFractionDigits(minimumFractionDigits);
        formatter.setMaximumFractionDigits(1);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter;
    }

    /**
     * Formats milliseconds as a human-readable duration string.
     */
    public static String formatDuration(long ms) {
        return formatDuration(ms, false, false);
    }

    /**
     * Formats milliseconds as a human-readable duration string with options.
     */
    public static String formatDuration(long ms, boolean hideTrailingZeros, boolean mostSignificantOnly) {
        if (ms < 60_000) {
            if (ms == 0) return "0s";
            long s = ms / 1000;
            return s + "s";
        }

        long days    = ms / 86_400_000;
        long hours   = (ms % 86_400_000) / 3_600_000;
        long minutes = (ms % 3_600_000)  / 60_000;
        long seconds = Math.round((ms % 60_000) / 1000.0);


        if (seconds == 60) { seconds = 0; minutes++; }
        if (minutes == 60) { minutes = 0; hours++;   }
        if (hours   == 24) { hours   = 0; days++;    }

        if (mostSignificantOnly) {
            if (days    > 0) return days    + "d";
            if (hours   > 0) return hours   + "h";
            if (minutes > 0) return minutes + "m";
            return seconds + "s";
        }

        if (days > 0) {
            if (hideTrailingZeros && hours == 0 && minutes == 0) return days + "d";
            if (hideTrailingZeros && minutes == 0)               return days + "d " + hours + "h";
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            if (hideTrailingZeros && minutes == 0 && seconds == 0) return hours + "h";
            if (hideTrailingZeros && seconds == 0)                 return hours + "h " + minutes + "m";
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        // minutes > 0 (guaranteed: ms >= 60_000 and not in days/hours branch)
        if (hideTrailingZeros && seconds == 0) return minutes + "m";
        return minutes + "m " + seconds + "s";
    }

    /**
     * Formats milliseconds as seconds with 1 decimal place.
     */
    public static String formatSecondsShort(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /**
     * Format a compact token count string.
     */
    public static String formatTokens(long tokens) {
        String formatted = formatNumber(tokens);
        int trailingZero = formatted.indexOf(".0");
        return trailingZero < 0
            ? formatted
            : formatted.substring(0, trailingZero) + formatted.substring(trailingZero + 2);
    }


    public static String formatCost(double cost) {
        if (cost > 0.5) {
            return "$" + String.format(Locale.US, "%.2f", Math.round(cost * 100) / 100.0);
        }
        return "$" + String.format(Locale.US, "%.4f", cost);
    }


    public static String formatNumber(long n) {
        NumberFormat formatter = n >= 1_000L
            ? COMPACT_ONE_DECIMAL.get()
            : COMPACT_OPTIONAL_DECIMAL.get();
        return formatter.format(n).toLowerCase(Locale.ROOT);
    }

    /**
     * Formats a byte count as a human-readable file size string.
     */
    public static String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1)    return sizeInBytes + " bytes";
        if (kb < 1024) return stripZero(String.format(Locale.ROOT, "%.1fKB", kb));
        double mb = kb / 1024.0;
        if (mb < 1024) return stripZero(String.format(Locale.ROOT, "%.1fMB", mb));
        double gb = mb / 1024.0;
        return stripZero(String.format(Locale.ROOT, "%.1fGB", gb));
    }

    private static String stripZero(String s) {
        return s.replace(".0", "");
    }

    // ── Relative time ───────────────────────────────────────────────────────


    public enum RelativeTimeStyle {

        LONG,

        NARROW,

        SHORT,

        PARENTHESIZED,
    }

/** matches {@code Intl.RelativeTimeFormat}'s numeric option. */
    public enum RelativeTimeNumeric {
        ALWAYS,
        AUTO,
    }

    private enum RelativeUnit {
        YEAR(31_536_000L, "y", "year"),
        MONTH(2_592_000L, "mo", "month"),
        WEEK(604_800L, "w", "week"),
        DAY(86_400L, "d", "day"),
        HOUR(3_600L, "h", "hour"),
        MINUTE(60L, "m", "minute"),
        SECOND(1L, "s", "second");

        private final long seconds;
        private final String narrow;
        private final String word;

        RelativeUnit(long seconds, String narrow, String word) {
            this.seconds = seconds;
            this.narrow = narrow;
            this.word = word;
        }
    }


    public static String formatRelativeTime(
            Instant when, RelativeTimeStyle style, RelativeTimeNumeric numeric, Instant now) {
        if (when == null) return "—";
        if (style == RelativeTimeStyle.PARENTHESIZED) {
            return formatRelativeTimeParenthesized(when, now);
        }
        long diffSeconds = (when.toEpochMilli() - now.toEpochMilli()) / 1_000L;
        for (RelativeUnit unit : RelativeUnit.values()) {
            if (Math.abs(diffSeconds) < unit.seconds) continue;
            long value = diffSeconds / unit.seconds;
            if (style == RelativeTimeStyle.NARROW) {
                return diffSeconds < 0
                    ? Math.abs(value) + unit.narrow + " ago"
                    : "in " + value + unit.narrow;
            }
            return formatLongRelative(value, unit, numeric);
        }
        if (style == RelativeTimeStyle.NARROW) {
            return diffSeconds <= 0 ? "0s ago" : "in 0s";
        }
        if (numeric == RelativeTimeNumeric.AUTO) return "now";
        return style == RelativeTimeStyle.SHORT ? "in 0 sec." : "in 0 seconds";
    }

    private static String formatLongRelative(
            long value, RelativeUnit unit, RelativeTimeNumeric numeric) {
        if (numeric == RelativeTimeNumeric.AUTO) {
            String qualitative = qualitativeRelative(value, unit);
            if (qualitative != null) return qualitative;
        }
        long magnitude = Math.abs(value);
        String amount = magnitude + " " + unit.word + (magnitude == 1 ? "" : "s");
        return value < 0 ? amount + " ago" : "in " + amount;
    }

    private static String qualitativeRelative(long value, RelativeUnit unit) {
        if (value < -1 || value > 1) return null;
        return switch (unit) {
            case YEAR -> switch ((int) value) {
                case -1 -> "last year";
                case 0 -> "this year";
                case 1 -> "next year";
                default -> null;
            };
            case MONTH -> switch ((int) value) {
                case -1 -> "last month";
                case 0 -> "this month";
                case 1 -> "next month";
                default -> null;
            };
            case WEEK -> switch ((int) value) {
                case -1 -> "last week";
                case 0 -> "this week";
                case 1 -> "next week";
                default -> null;
            };
            case DAY -> switch ((int) value) {
                case -1 -> "yesterday";
                case 0 -> "today";
                case 1 -> "tomorrow";
                default -> null;
            };
            default -> null;
        };
    }

    /**
     * Formats an instant as a relative-time string.
     */
    public static String formatRelativeTimeAgo(Instant when, RelativeTimeStyle style) {
        return formatRelativeTimeAgo(when, style, Instant.now());
    }

    static String formatRelativeTimeAgo(Instant when, RelativeTimeStyle style, Instant now) {
        return formatRelativeTimeAgo(when, style, RelativeTimeNumeric.ALWAYS, now);
    }


    public static String formatRelativeTimeAgo(
            Instant when, RelativeTimeStyle style, RelativeTimeNumeric numeric, Instant now) {
        if (when == null) return style == RelativeTimeStyle.PARENTHESIZED ? null : "—";
        RelativeTimeNumeric effective = when.isAfter(now) ? numeric : RelativeTimeNumeric.ALWAYS;
        return formatRelativeTime(when, style, effective, now);
    }

    private static String formatRelativeTimeParenthesized(Instant start, Instant now) {
        if (start == null) return null;
        Duration d = Duration.between(start, now);
        if (d.isNegative()) return null;
        long mins = d.toMinutes();
        if (mins < 1) return "(just now)";
        if (mins < 60) return "(" + mins + "m ago)";
        long hours = d.toHours();
        if (hours < 24) return "(" + hours + "h ago)";
        return "(" + d.toDays() + "d ago)";
    }

    // ── String truncation ───────────────────────────────────────────────────

    /**
     * Truncates {@code s} to at most {@code max} terminal columns, appending "…" when truncated.
     */
    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (displayWidth(s) <= max) return s;
        if (max <= 1) return "…";
        return takeWidth(s, max - 1, false) + "…";
    }


    public static String truncateNoEllipsis(String s, int max) {
        if (s == null) return "";
        if (displayWidth(s) <= max) return s;
        if (max <= 0) return "";
        return takeWidth(s, max, false);
    }


    public static String truncateSingleLine(String s, int max) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        if (nl != -1) {
            String result = s.substring(0, nl);
            if (displayWidth(result) + 1 > max) return truncate(result, max);
            return result + "…";
        }
        return truncate(s, max);
    }

    /** Keeps the tail of a value and prepends an ellipsis when it exceeds {@code maxWidth}. */
    public static String truncateStartToWidth(String value, int maxWidth) {
        if (value == null) return "";
        if (displayWidth(value) <= maxWidth) return value;
        if (maxWidth <= 1) return "…";
        return "…" + takeWidth(value, maxWidth - 1, true);
    }

    /** Preserves both the directory prefix and filename when shortening a slash-separated path. */
    public static String truncatePathMiddle(String path, int maxWidth) {
        if (path == null) return "";
        if (displayWidth(path) <= maxWidth) return path;
        if (maxWidth <= 0) return "…";
        if (maxWidth < 5) return truncate(path, maxWidth);
        int slash = path.lastIndexOf('/');
        String filename = slash >= 0 ? path.substring(slash) : path;
        String directory = slash >= 0 ? path.substring(0, slash) : "";
        int filenameWidth = displayWidth(filename);
        if (filenameWidth >= maxWidth - 1) return truncateStartToWidth(path, maxWidth);
        int directoryWidth = maxWidth - 1 - filenameWidth;
        if (directoryWidth <= 0) return truncateStartToWidth(filename, maxWidth);
        return truncateNoEllipsis(directory, directoryWidth) + "…" + filename;
    }

    /** Wraps text into terminal-width-bounded chunks without splitting grapheme clusters. */
    public static List<String> wrapText(String text, int width) {
        if (StringUtils.isEmpty(text) || width <= 0) return List.of();
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (Segment segment : segments(text)) {
            if (currentWidth + segment.width() <= width) {
                current.append(segment.text());
                currentWidth += segment.width();
            } else {
                if (!current.isEmpty()) lines.add(current.toString());
                current = new StringBuilder(segment.text());
                currentWidth = segment.width();
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return List.copyOf(lines);
    }

    /** Terminal display width with ambiguous-width characters treated as narrow. */
    public static int displayWidth(String value) {
        if (StringUtils.isEmpty(value)) return 0;
        // Prompt editing is overwhelmingly printable ASCII. Avoid constructing
        // an ICU BreakIterator plus one Segment per character for the common
        // path; controls and all non-ASCII text still use the grapheme-aware
        // implementation below, so Unicode/emoji width semantics are unchanged.
        boolean printableAscii = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                printableAscii = false;
                break;
            }
        }
        if (printableAscii) return value.length();
        return segments(value).stream().mapToInt(Segment::width).sum();
    }

    private static String takeWidth(String value, int maxWidth, boolean fromEnd) {
        List<Segment> items = segments(value);
        StringBuilder out = new StringBuilder();
        int width = 0;
        if (!fromEnd) {
            for (Segment item : items) {
                if (width + item.width() > maxWidth) break;
                out.append(item.text());
                width += item.width();
            }
        } else {
            for (int i = items.size() - 1; i >= 0; i--) {
                Segment item = items.get(i);
                if (width + item.width() > maxWidth) break;
                out.insert(0, item.text());
                width += item.width();
            }
        }
        return out.toString();
    }

    private static List<Segment> segments(String value) {
        if (StringUtils.isEmpty(value)) return List.of();
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        List<Segment> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String text = value.substring(start, end);
            result.add(new Segment(text, graphemeWidth(value, start, end)));
        }
        return result;
    }

    private static int graphemeWidth(String value, int start, int end) {
        boolean visible = false;
        boolean wide = false;
        boolean emoji = false;
        for (int offset = start; offset < end;) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x20E3
                    || UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)
                    || UCharacter.hasBinaryProperty(cp, UProperty.REGIONAL_INDICATOR)) emoji = true;
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK) continue;
            visible = true;
            int eastAsian = UCharacter.getIntPropertyValue(cp, UProperty.EAST_ASIAN_WIDTH);
            if (eastAsian == UCharacter.EastAsianWidth.WIDE
                    || eastAsian == UCharacter.EastAsianWidth.FULLWIDTH) wide = true;
        }
        return !visible ? 0 : wide || emoji ? 2 : 1;
    }

    private record Segment(String text, int width) {}

    // ── Date / timestamp formatting ─────────────────────────────────────────

    private static final DateTimeFormatter EXPORT_TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter ISO_MILLIS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter MONTH_DAY_FMT =
        DateTimeFormatter.ofPattern("MMM d", Locale.US);

/**
     * Export filename timestamp, e.g.
     */
    public static String formatExportTimestamp(Instant when) {
        return EXPORT_TS_FMT.format(when);
    }

/** ISO-8601 millis UTC, e.g. "2026-07-20T14:30:15.123Z"; matches {@code Date#toISOString}. */
    public static String formatInstantIso(Instant when) {
        return ISO_MILLIS_FMT.format(when);
    }

/**
     * Chart axis label "Jul 8" (en-US).
     */
    public static String formatMonthDay(Instant when) {
        return LocalDate.ofInstant(when, ZoneOffset.UTC).format(MONTH_DAY_FMT);
    }

    /** Overload accepting a pre-parsed date (e.g. stats {@code xAxisLabels}). */
    public static String formatMonthDay(LocalDate date) {
        return date.format(MONTH_DAY_FMT);
    }

    // ── Tab expansion (ANSI-aware) ──────────────────────────────────────────


    public static final int DEFAULT_TAB_INTERVAL = 8;

    /**
     * Expands tab characters to spaces on fixed 8-column intervals, skipping ANSI escape sequences and
     * tracking the display (wcwidth) column so alignment matches terminal rendering.
     */
    public static String expandTabs(String text) {
        return expandTabs(text, DEFAULT_TAB_INTERVAL);
    }

    /**
     * Like {@link #expandTabs(String)} with a caller-supplied interval (columns per tab stop).
     */
    public static String expandTabs(String text, int interval) {
        if (text == null || interval <= 0 || text.indexOf('\t') < 0) return text;
        StringBuilder result = new StringBuilder(text.length() + 16);
        int column = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\u001B') {
                int end = ansiSequenceEnd(text, i);
                result.append(text, i, end);
                i = end;
                continue;
            }
            if (c == '\t') {
                int spaces = interval - (column % interval);
                result.repeat(" ", Math.max(0, spaces));
                column += spaces;
                i++;
                continue;
            }
            if (c == '\n') {
                result.append(c);
                column = 0;
                i++;
                continue;
            }
            int cp = text.codePointAt(i);
            int w = charDisplayWidth(cp);
            result.appendCodePoint(cp);
            column += w;
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    /**
     * Returns the index just past the ANSI escape sequence starting at {@code start}
     * (where {@code text.charAt(start) == ESC}). Handles CSI ({@code ESC [}), OSC
     * ({@code ESC ]} terminated by BEL or ST), and short {@code ESC X} sequences.
     */
    static int ansiSequenceEnd(String s, int start) {
        if (start + 1 >= s.length()) return start + 1;
        char next = s.charAt(start + 1);
        if (next == '[') {
            int j = start + 2;
            while (j < s.length() && s.charAt(j) >= 0x20 && s.charAt(j) <= 0x3F) j++;
            while (j < s.length() && !(s.charAt(j) >= 0x40 && s.charAt(j) <= 0x7E)) j++;
            return j < s.length() ? j + 1 : s.length();
        }
        if (next == ']') {
            int j = start + 2;
            while (j < s.length() && s.charAt(j) != 0x07) {
                if (s.charAt(j) == '\u001B') {
                    return (j + 1 < s.length() && s.charAt(j + 1) == '\\') ? j + 2 : j;
                }
                j++;
            }
            return j < s.length() ? j + 1 : s.length();
        }
        return start + 2; // other ESC X (incl. ST = ESC \)
    }

    /**
     * Terminal display width of a single code point: 0 for controls / zero-width combining marks, 2 for
     * East-Asian wide / fullwidth, else 1.
     */
    static int charDisplayWidth(int codePoint) {
        int type = Character.getType(codePoint);
        if (type == Character.CONTROL) return 0;
        if (type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) return 0;
        return isEastAsianWide(codePoint) ? 2 : 1;
    }

    /** Whether {@code cp} is an East-Asian Wide or Fullwidth code point (display width 2). */
    private static boolean isEastAsianWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
            || (cp >= 0x2E80 && cp <= 0x303E)
            || (cp >= 0x3041 && cp <= 0x33FF)
            || (cp >= 0x3400 && cp <= 0x4DBF)
            || (cp >= 0x4E00 && cp <= 0x9FFF)
            || (cp >= 0xA000 && cp <= 0xA4CF)
            || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE4F)
            || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6)
            || (cp >= 0x1F300 && cp <= 0x1FAFF)
            || (cp >= 0x20000 && cp <= 0x3FFFD);
    }
}
