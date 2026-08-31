package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.text.FormatUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Width-aware folding for shell/tool output displayed in the transcript.
 */
final class ShellOutputTruncator {

    static final int MAX_VISIBLE_ROWS = 3;
    private static final int PADDING_TO_PREVENT_OVERFLOW = 10;
    private static final int MIN_WRAP_WIDTH = 10;
    private static final int MAX_CHARS_PER_VISIBLE_COLUMN = 4;

    private ShellOutputTruncator() {}

    record TruncatedOutput(List<String> visibleRows, int remainingRows) {
        TruncatedOutput {
            visibleRows = List.copyOf(visibleRows);
            if (remainingRows < 0) {
                throw new IllegalArgumentException("remainingRows must be non-negative");
            }
        }
    }

    record PreparedContent(String contentForWrapping, int wrapWidth, boolean preTruncated,
                           int originalLength) {}

    record TruncatedRows<T>(List<T> visibleRows, int remainingRows) {
        TruncatedRows {
            visibleRows = List.copyOf(visibleRows);
        }
    }

    static TruncatedOutput truncate(String content, int terminalWidth) {
        PreparedContent prepared = prepare(content, terminalWidth);
        if (prepared.contentForWrapping().isEmpty()) {
            return new TruncatedOutput(List.of(), 0);
        }

        List<String> wrappedRows = wrapByDisplayWidth(
            prepared.contentForWrapping(), prepared.wrapWidth());
        TruncatedRows<String> truncated = truncateRows(wrappedRows, prepared);
        return new TruncatedOutput(truncated.visibleRows(), truncated.remainingRows());
    }

    static PreparedContent prepare(String content, int terminalWidth) {
        String trimmed = content == null ? "" : content.stripTrailing();
        int wrapWidth = Math.max(terminalWidth - PADDING_TO_PREVENT_OVERFLOW,
            MIN_WRAP_WIDTH);
        if (trimmed.isEmpty()) return new PreparedContent("", wrapWidth, false, 0);

        int maxChars = MAX_VISIBLE_ROWS * wrapWidth * MAX_CHARS_PER_VISIBLE_COLUMN;
        boolean preTruncated = trimmed.length() > maxChars;
        String contentForWrapping = preTruncated
            ? safePrefix(trimmed, maxChars)
            : trimmed;
        return new PreparedContent(contentForWrapping, wrapWidth, preTruncated,
            trimmed.length());
    }

    static <T> TruncatedRows<T> truncateRows(List<T> wrappedRows, PreparedContent prepared) {
        int remainingRows = wrappedRows.size() - MAX_VISIBLE_ROWS;
        int visibleCount = remainingRows == 1
            ? Math.min(wrappedRows.size(), MAX_VISIBLE_ROWS + 1)
            : Math.min(wrappedRows.size(), MAX_VISIBLE_ROWS);

        int estimatedRemaining = prepared.preTruncated()
            ? Math.max(remainingRows,
                divideRoundingUp(prepared.originalLength(), prepared.wrapWidth())
                    - MAX_VISIBLE_ROWS)
            : Math.max(0, remainingRows);
        if (!prepared.preTruncated() && remainingRows == 1) estimatedRemaining = 0;

        return new TruncatedRows<>(wrappedRows.subList(0, visibleCount),
            Math.max(0, estimatedRemaining));
    }

    private static List<String> wrapByDisplayWidth(String content, int wrapWidth) {
        String[] logicalLines = content.split("\n", -1);
        List<String> rows = new ArrayList<>();
        for (String logicalLine : logicalLines) {
            if (FormatUtils.displayWidth(logicalLine) <= wrapWidth) {
                rows.add(logicalLine.stripTrailing());
                continue;
            }
            List<String> wrapped = FormatUtils.wrapText(logicalLine, wrapWidth);
            if (wrapped.isEmpty()) {
                rows.add("");
            } else {
                for (String row : wrapped) rows.add(row.stripTrailing());
            }
        }
        return rows;
    }

    private static String safePrefix(String value, int maxChars) {
        int end = Math.min(value.length(), maxChars);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static int divideRoundingUp(int value, int divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }
}
