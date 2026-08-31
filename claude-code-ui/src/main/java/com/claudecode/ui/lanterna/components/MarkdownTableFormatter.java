package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.TerminalTextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects markdown tables in a multi-line string and rewrites them as
 * Unicode-box-drawn tables aligned by a 3-step column-width algorithm.
 * <p>
 * Ports the column-width logic from
 *
 * <ol>
 *   <li>Compute <b>idealWidth</b> (full content) and <b>minWidth</b>
 *       (longest single word) per column.</li>
 *   <li>If all idealWidths fit → use ideal.</li>
 *   <li>If totalMin fits → distribute remaining space proportionally to
 *       each column's overflow (ideal − min).</li>
 *   <li>Otherwise → scale minWidths by {@code available/totalMin}, hard-wrap.</li>
 * </ol>
 *
 * Vertical (key-value) fallback fires when any row would exceed
 * {@link TableBorders#MAX_ROW_LINES} lines after wrapping.
 */
public final class MarkdownTableFormatter {

    // A separator row like "| --- | :---: |"
    private static final Pattern SEP_PATTERN =
        Pattern.compile("^\\s*\\|?(\\s*:?-+:?\\s*\\|)+\\s*:?-+:?\\s*\\|?\\s*$");

    /** Default terminal width when we can't query the actual terminal. */
    private static final int DEFAULT_TERMINAL_WIDTH = 80;

    /**
     * Process a multi-line string: detect markdown table blocks and replace
     * them with formatted box-drawn versions.
     */
    public static String format(String text) {
        return format(text, DEFAULT_TERMINAL_WIDTH);
    }

    /**
     * Same as {@link #format(String)} but with an explicit terminal width.
     * Used by tests and callers that know the actual window width.
     */
    public static String format(String text, int terminalWidth) {
        if (StringUtils.isEmpty(text)) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();

        int i = 0;
        while (i < lines.length) {
            if (i + 1 < lines.length
                    && looksLikeRow(lines[i])
                    && SEP_PATTERN.matcher(lines[i + 1]).matches()) {
                List<String[]> rows = new ArrayList<>();
                rows.add(splitRow(lines[i]));
                int j = i + 2;
                while (j < lines.length && looksLikeRow(lines[j])) {
                    rows.add(splitRow(lines[j]));
                    j++;
                }
                String table = renderTable(rows, terminalWidth);
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
                out.append(table);
                if (j < lines.length) out.append('\n');
                i = j;
            } else {
                out.append(lines[i]);
                if (i < lines.length - 1) out.append('\n');
                i++;
            }
        }
        return out.toString();
    }

    // ──────────────────────────────────────────────────────────────────────

    private static boolean looksLikeRow(String line) {
        if (line == null) return false;
        String t = line.trim();
        return Strings.CS.contains(t, "|") && !SEP_PATTERN.matcher(t).matches();
    }

    private static String[] splitRow(String line) {
        String t = line.trim();
        if (Strings.CS.startsWith(t, "|")) t = t.substring(1);
        if (Strings.CS.endsWith(t, "|"))   t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        for (int k = 0; k < parts.length; k++) parts[k] = parts[k].trim();
        return parts;
    }


    private static String renderTable(List<String[]> rows, int terminalWidth) {
        if (rows.isEmpty()) return "";
        int cols = rows.getFirst().length;

        // Step 1: compute idealWidth (full content) and minWidth (longest word) per column.
        int[] idealWidths = new int[cols];
        int[] minWidths   = new int[cols];
        for (int c = 0; c < cols; c++) {
            idealWidths[c] = TableBorders.MIN_COLUMN_WIDTH;
            minWidths[c]   = TableBorders.MIN_COLUMN_WIDTH;
        }
        for (String[] row : rows) {
            for (int c = 0; c < cols && c < row.length; c++) {
                String cell = row[c];
                int ideal = TerminalTextUtils.getColumnWidth(cell);
                idealWidths[c] = Math.max(idealWidths[c], ideal);
                int minW = longestWordWidth(cell);
                minWidths[c] = Math.max(minWidths[c], minW);
            }
        }


        int borderOverhead = TableBorders.computeBorderOverhead(cols);
        int available = Math.max(
            terminalWidth - borderOverhead - TableBorders.SAFETY_MARGIN,
            cols * TableBorders.MIN_COLUMN_WIDTH);

        // Step 3: choose column widths.
        int[] widths = computeColumnWidths(idealWidths, minWidths, available);

        // Step 4: render.
        StringBuilder sb = new StringBuilder();
        sb.append(borderRow(widths, '┌', '┬', '┐')).append('\n');
        sb.append(dataRow(rows.getFirst(), widths)).append('\n');
        sb.append(borderRow(widths, '├', '┼', '┤')).append('\n');
        for (int r = 1; r < rows.size(); r++) {
            sb.append(dataRow(rows.get(r), widths)).append('\n');
        }
        sb.append(borderRow(widths, '└', '┴', '┘'));
        return sb.toString();
    }


    static int[] computeColumnWidths(int[] idealWidths, int[] minWidths, int available) {
        int cols = idealWidths.length;
        int totalIdeal = sum(idealWidths);
        int totalMin   = sum(minWidths);

        if (totalIdeal <= available) {
            // Everything fits at ideal width.
            return idealWidths.clone();
        }

        if (totalMin <= available) {
            // Distribute extra space proportionally to each column's overflow.
            int extraSpace = available - totalMin;
            int totalOverflow = 0;
            for (int c = 0; c < cols; c++) {
                totalOverflow += Math.max(0, idealWidths[c] - minWidths[c]);
            }
            int[] widths = new int[cols];
            for (int c = 0; c < cols; c++) {
                int overflow = Math.max(0, idealWidths[c] - minWidths[c]);
                int extra = totalOverflow == 0 ? 0
                    : (int) Math.floor((double) overflow / totalOverflow * extraSpace);
                widths[c] = minWidths[c] + extra;
            }
            return widths;
        }

        // Table wider than terminal even at min widths — scale proportionally.
        double scaleFactor = (double) available / totalMin;
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = Math.max(
                (int) Math.floor(minWidths[c] * scaleFactor),
                TableBorders.MIN_COLUMN_WIDTH);
        }
        return widths;
    }

    /** Width of the longest word in {@code text} (space-separated). */
    private static int longestWordWidth(String text) {
        if (StringUtils.isBlank(text)) return TableBorders.MIN_COLUMN_WIDTH;
        int max = TableBorders.MIN_COLUMN_WIDTH;
        for (String word : text.split("\\s+")) {
            int w = TerminalTextUtils.getColumnWidth(word);
            if (w > max) max = w;
        }
        return max;
    }

    private static int sum(int[] arr) {
        int s = 0;
        for (int v : arr) s += v;
        return s;
    }

    private static String borderRow(int[] widths, char left, char mid, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int c = 0; c < widths.length; c++) {
            sb.repeat("─", widths[c] + 2);
            sb.append(c == widths.length - 1 ? right : mid);
        }
        return sb.toString();
    }

    private static String dataRow(String[] row, int[] widths) {
        StringBuilder sb = new StringBuilder();
        sb.append('│');
        for (int c = 0; c < widths.length; c++) {
            String value = c < row.length ? row[c] : "";
            int colW = TerminalTextUtils.getColumnWidth(value);
            // Truncate if still wider than column (hard-wrap case).
            if (colW > widths[c]) {
                value = truncateToWidth(value, widths[c]);
                colW  = widths[c];
            }
            int pad = Math.max(0, widths[c] - colW);
            sb.append(' ').append(value).repeat(" ", pad).append(" │");
        }
        return sb.toString();
    }

    /** Truncate {@code text} to at most {@code maxWidth} display columns. */
    private static String truncateToWidth(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        int cols = 0;
        int idx  = 0;
        while (idx < text.length()) {
            int cp = text.codePointAt(idx);
            int w  = TerminalTextUtils.isCharDoubleWidth((char) cp) ? 2 : 1;
            if (cols + w > maxWidth) break;
            cols += w;
            idx  += Character.charCount(cp);
        }
        return text.substring(0, idx);
    }

    private MarkdownTableFormatter() {}
}

