package com.claudecode.core.text;

import java.util.Arrays;
import java.util.List;


public final class StringUtils {


    public static final int MAX_STRING_LENGTH = 1 << 25;

    private StringUtils() {
        // static-only utility
    }

    /**
     * Returns the singular or plural form of {@code word} based on {@code n}.
     */
    public static String plural(long n, String word) {
        return plural(n, word, word + "s");
    }

    /**
     * Returns the singular or plural form of {@code word} based on {@code n}, using {@code pluralWord}
     * when {@code n != 1}.
     */
    public static String plural(long n, String word, String pluralWord) {
        return n == 1 ? word : pluralWord;
    }

    /**
     * Uppercases only the first character of {@code s}; the rest is left unchanged.
     * explicitly NOT the
     * lodash behaviour of lowercasing the remainder (e.g. {@code capitalize("fooBar") → "FooBar"}).
     * A null or empty input is returned unchanged.
     */
    public static String capitalize(String s) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(s)) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Escapes regex metacharacters so {@code value} can be embedded as a literal pattern. */
    public static String escapeRegExp(String value) {
        return value.replaceAll("([.*+?^${}()|\\[\\]\\\\])", "\\\\$1");
    }

    /** Normalizes Japanese full-width digits to ASCII digits. */
    public static String normalizeFullWidthDigits(String input) {
        StringBuilder out = new StringBuilder(input.length());
        input.codePoints().forEach(cp -> out.appendCodePoint(
            cp >= '０' && cp <= '９' ? cp - 0xFEE0 : cp));
        return out.toString();
    }

    /** Normalizes U+3000 IDEOGRAPHIC SPACE to an ASCII space. */
    public static String normalizeFullWidthSpace(String input) {
        return input.replace('\u3000', ' ');
    }

    /** Joins strings without allowing the accumulated value to exceed {@code maxSize}. */
    public static String safeJoinLines(List<String> lines, String delimiter, int maxSize) {
        String marker = "...[truncated]";
        StringBuilder result = new StringBuilder(Math.min(maxSize, 1024));
        for (String line : lines) {
            String separator = result.isEmpty() ? "" : delimiter;
            if (result.length() + separator.length() + line.length() <= maxSize) {
                result.append(separator).append(line);
                continue;
            }
            int remaining = maxSize - result.length() - separator.length() - marker.length();
            if (remaining > 0) result.append(separator).append(line, 0, Math.min(remaining, line.length()));
            result.append(marker);
            return result.toString();
        }
        return result.toString();
    }

    public static String safeJoinLines(List<String> lines, String delimiter) {
        return safeJoinLines(lines, delimiter, MAX_STRING_LENGTH);
    }

    /** Keeps at most {@code maxLines} lines and adds an ellipsis when more were present. */
    public static String truncateToLines(String text, int maxLines) {
        String[] lines = text.split("\\n", -1);
        if (lines.length <= maxLines) return text;
        return String.join("\n", Arrays.copyOf(lines, maxLines)) + "…";
    }

    /** Bounded head-preserving accumulator used for shell stdout. */
    public static final class EndTruncatingAccumulator {
        private final int maxSize;
        private final StringBuilder content = new StringBuilder();
        private boolean truncated;
        private long totalBytes;

        public EndTruncatingAccumulator() { this(MAX_STRING_LENGTH); }

        public EndTruncatingAccumulator(int maxSize) { this.maxSize = maxSize; }

        public synchronized void append(String data) {
            totalBytes += data.length();
            int remaining = maxSize - content.length();
            if (remaining > 0) content.append(data, 0, Math.min(remaining, data.length()));
            if (data.length() > remaining) truncated = true;
        }

        public synchronized int length() { return content.length(); }

        public synchronized boolean truncated() { return truncated; }

        public synchronized long totalBytes() { return totalBytes; }

        public synchronized String tail(int chars) {
            return content.substring(Math.max(0, content.length() - chars));
        }

        public synchronized void clear() {
            content.setLength(0); truncated = false; totalBytes = 0;
        }

        @Override public synchronized String toString() {
            if (!truncated) return content.toString();
            long removedKb = Math.round((totalBytes - maxSize) / 1024.0);
            return content + "\n... [output truncated - " + removedKb + "KB removed]";
        }
    }

    /**
     * Counts occurrences of {@code c} in {@code s} starting at {@code start}.
     */
    public static int countChar(String s, char c, int start) {
        int count = 0;
        for (int i = s.indexOf(c, start); i != -1; i = s.indexOf(c, i + 1)) {
            count++;
        }
        return count;
    }

    /**
     * Counts all occurrences of {@code c} in {@code s} (from index 0).
     * Convenience overload for {@link #countChar(String, char, int)} with {@code start = 0}.
     */
    public static int countChar(String s, char c) {
        return countChar(s, c, 0);
    }

    /**
     * Right-pads {@code value} with spaces until it reaches {@code width}; values already at
     * least that long are returned unchanged. matches {@code String.prototype.padEnd(width)}
     * at the call sites that use its default space padding for fixed-width terminal columns.
     */
    public static String padEnd(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    /**
     * Returns the text of {@code s} up to the first newline, or {@code s} itself when it contains no
     * newline.
     */
    public static String firstLineOf(String s) {
        int nl = s.indexOf('\n');
        return nl == -1 ? s : s.substring(0, nl);
    }

    /**
     * Returns at most {@code maxBodyChars} characters from {@code value}, appending
     * {@code suffix} only when truncation occurred. A null value becomes an empty string.
     */
    public static String truncateWithSuffix(String value, int maxBodyChars, String suffix) {
        if (maxBodyChars < 0) {
            throw new IllegalArgumentException("maxBodyChars must be non-negative");
        }
        if (value == null) return "";
        if (value.length() <= maxBodyChars) return value;
        return value.substring(0, maxBodyChars) + (suffix == null ? "" : suffix);
    }

    /**
     * Size of the live-progress tail window, in chars.
     */
    public static final int PROGRESS_TAIL_CHARS = 4096;


    public record ProgressTail(String last5, String last100) {}

    /**
     * Computes the last-5 / last-100 line tails of {@code window}.
     */
    public static ProgressTail progressTail(String window) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(window)) {
            return new ProgressTail("", "");
        }
        int pos = window.length();
        int lineCount = 0;
        int n5 = 0;
        int n100 = 0;
        while (pos > 0) {
            pos = window.lastIndexOf('\n', pos - 1);
            if (pos < 0) break;
            lineCount++;
            if (lineCount == 5) n5 = pos <= 0 ? 0 : pos + 1;
            if (lineCount == 100) n100 = pos <= 0 ? 0 : pos + 1;
        }
        return new ProgressTail(window.substring(n5), window.substring(n100));
    }
}
