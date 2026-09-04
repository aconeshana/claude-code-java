package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Column-accurate wrapping and slicing for lines of already-styled {@link Segment}s.
 *
 * <p>The bundle does this on raw ANSI strings — {@code x1} is {@code wrap-ansi} with
 * {@code {hard:true, trim:false}} and the per-row clamp is {@code slice-ansi}. Working on parsed
 * segments instead keeps the escape handling in {@code AnsiToSegments}, which already owns it, and
 * makes the geometry unit-testable without re-parsing ANSI.
 *
 * <p>Both operations measure in terminal columns, so a wide (CJK) glyph counts as two.
 */
final class SegmentLines {

    private SegmentLines() {}

    /** A single grapheme-ish unit carrying the style of the segment it came from. */
    private record Cell(String text, int width, Segment style) {}

    /** Total display width of a line. */
    static int width(List<Segment> line) {
        int total = 0;
        for (Segment segment : line) total += FormatUtils.displayWidth(segment.text());
        return total;
    }

    /** A one-segment line, for plain unstyled text. */
    static List<Segment> plain(String text, TextColor color) {
        return List.of(new Segment(text, color));
    }

    /**
     * {@code x1} — greedy word wrap to {@code columns}, breaking inside a word only when the word
     * alone cannot fit. Trailing spaces are kept on the line they broke after, matching
     * {@code trim:false}; the box pads over them anyway.
     */
    static List<List<Segment>> wrap(List<Segment> line, int columns) {
        int limit = Math.max(1, columns);
        if (width(line) <= limit) return List.of(line);

        List<List<Cell>> wrapped = new ArrayList<>();
        List<Cell> current = new ArrayList<>();
        int currentWidth = 0;
        int breakAfter = -1;
        for (Cell cell : cells(line)) {
            if (currentWidth + cell.width() > limit && !current.isEmpty()) {
                int cut = breakAfter > 0 ? breakAfter : current.size();
                wrapped.add(List.copyOf(current.subList(0, cut)));
                current = new ArrayList<>(current.subList(cut, current.size()));
                currentWidth = 0;
                for (Cell kept : current) currentWidth += kept.width();
                breakAfter = -1;
            }
            current.add(cell);
            currentWidth += cell.width();
            if (" ".equals(cell.text())) breakAfter = current.size();
        }
        wrapped.add(List.copyOf(current));

        List<List<Segment>> result = new ArrayList<>(wrapped.size());
        for (List<Cell> row : wrapped) result.add(toSegments(row));
        return List.copyOf(result);
    }

    /** {@code slice-ansi} — the leading {@code columns} columns of a line, never splitting a glyph. */
    static List<Segment> sliceToWidth(List<Segment> line, int columns) {
        if (columns <= 0) return List.of();
        if (width(line) <= columns) return line;
        List<Cell> kept = new ArrayList<>();
        int used = 0;
        for (Cell cell : cells(line)) {
            if (used + cell.width() > columns) break;
            kept.add(cell);
            used += cell.width();
        }
        return toSegments(kept);
    }

    private static List<Cell> cells(List<Segment> line) {
        List<Cell> out = new ArrayList<>();
        for (Segment segment : line) {
            String text = segment.text();
            for (int index = 0; index < text.length();) {
                int codePoint = text.codePointAt(index);
                int length = Character.charCount(codePoint);
                String glyph = text.substring(index, index + length);
                out.add(new Cell(glyph, FormatUtils.displayWidth(glyph), segment));
                index += length;
            }
        }
        return out;
    }

    private static List<Segment> toSegments(List<Cell> cells) {
        List<Segment> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        Segment style = null;
        for (Cell cell : cells) {
            if (style != null && !sameStyle(style, cell.style())) {
                out.add(rebuild(buffer.toString(), style));
                buffer.setLength(0);
            }
            style = cell.style();
            buffer.append(cell.text());
        }
        if (style != null) out.add(rebuild(buffer.toString(), style));
        return List.copyOf(out);
    }

    private static Segment rebuild(String text, Segment style) {
        return new Segment(text, style.color(), style.bgColor(), style.hyperlinkUrl(),
            style.modifiers());
    }

    private static boolean sameStyle(Segment left, Segment right) {
        return Objects.equals(left.color(), right.color())
            && Objects.equals(left.bgColor(), right.bgColor())
            && Objects.equals(left.hyperlinkUrl(), right.hyperlinkUrl())
            && left.modifiers().equals(right.modifiers());
    }
}
