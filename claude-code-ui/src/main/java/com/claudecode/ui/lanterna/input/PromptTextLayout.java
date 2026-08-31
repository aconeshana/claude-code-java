package com.claudecode.ui.lanterna.input;

import com.claudecode.core.text.FormatUtils;
import com.ibm.icu.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Immutable visual-line projection of prompt text.
 */
public final class PromptTextLayout {

    public record Position(int line, int column) {}

    public record VisualLine(String text, int startOffset, int endOffset,
                             boolean precededByNewline, boolean endsWithNewline) {

        public String displayText() {
            return precededByNewline ? text : text.substring(leadingWhitespaceLength(text));
        }

        public int displayStartOffset() {
            return startOffset + (precededByNewline ? 0 : leadingWhitespaceLength(text));
        }

        VisualLine withEndsWithNewline(boolean value) {
            return new VisualLine(text, startOffset, endOffset, precededByNewline, value);
        }
    }

    private final String text;
    private final int wrapColumns;
    private final List<VisualLine> lines;

    private PromptTextLayout(String text, int inputColumns) {
        this.text = normalize(text == null ? "" : text);
        this.wrapColumns = Math.max(1, inputColumns - 1);
        this.lines = List.copyOf(measureLines());
    }

    private static String normalize(String value) {
// NFC is identity for ASCII.
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                return Normalizer.normalize(value, Normalizer.Form.NFC);
            }
        }
        return value;
    }

    public static PromptTextLayout create(String text, int inputColumns) {
        return new PromptTextLayout(text, inputColumns);
    }

    public String text() {
        return text;
    }

    public int wrapColumns() {
        return wrapColumns;
    }

    public List<VisualLine> lines() {
        return lines;
    }

    public int lineCount() {
        return lines.size();
    }

    public Position positionAt(int absoluteOffset) {
        int offset = Math.max(0, Math.min(absoluteOffset, text.length()));
        int row = lineIndexAt(offset);
        VisualLine line = lines.get(row);
        int relative = Math.max(0, Math.min(offset - line.startOffset(), line.text().length()));
        int leading = line.precededByNewline() ? 0 : leadingWhitespaceLength(line.text());
        if (relative <= leading) return new Position(row, 0);
        return new Position(row,
            FormatUtils.displayWidth(line.text().substring(leading, relative)));
    }

    public int offsetAt(Position position) {
        int row = Math.max(0, Math.min(position.line(), lines.size() - 1));
        VisualLine line = lines.get(row);
        int targetColumn = Math.max(0, position.column());
        int leading = line.precededByNewline() ? 0 : leadingWhitespaceLength(line.text());
        int targetWidth = targetColumn + FormatUtils.displayWidth(line.text().substring(0, leading));
        int relative = offsetAtDisplayWidth(line.text(), targetWidth);
        if (line.endsWithNewline()
                && targetColumn > FormatUtils.displayWidth(line.text().substring(leading))) {
            return Math.min(text.length(), line.endOffset() + 1);
        }
        return Math.min(line.endOffset(), line.startOffset() + relative);
    }

    public int moveVertically(int absoluteOffset, int delta) {
        int max = Math.max(0, Math.min(absoluteOffset, text.length()));
        if (delta == 0) return max;
        Position current = positionAt(absoluteOffset);
        int targetRow = current.line() + delta;
        if (targetRow < 0 || targetRow >= lines.size()) {
            return max;
        }
        return offsetAt(new Position(targetRow, current.column()));
    }

    private List<VisualLine> measureLines() {
        List<VisualLine> measured = new ArrayList<>();
        int logicalStart = 0;
        while (logicalStart <= text.length()) {
            int newline = text.indexOf('\n', logicalStart);
            boolean endsWithNewline = newline >= 0;
            int logicalEnd = endsWithNewline ? newline : text.length();
            wrapLogicalLine(logicalStart, logicalEnd, measured);
            int last = measured.size() - 1;
            if (endsWithNewline) {
                measured.set(last, measured.get(last).withEndsWithNewline(true));
                logicalStart = newline + 1;
            } else {
                break;
            }
        }
        if (measured.isEmpty()) {
            measured.add(new VisualLine("", 0, 0, true, false));
        }
        return measured;
    }

    private void wrapLogicalLine(int start, int end, List<VisualLine> measured) {
        if (start == end) {
            measured.add(new VisualLine("", start, start, isPrecededByNewline(start), false));
            return;
        }

        LineBuilder current = new LineBuilder(start);
        int wordStart = start;
        int wordIndex = 0;
        while (wordStart <= end) {
            int space = text.indexOf(' ', wordStart);
            if (space < 0 || space > end) space = end;
            int wordEnd = space;

            if (wordIndex > 0) {
                if (current.width >= wrapColumns) {
                    finish(current, measured);
                    current = new LineBuilder(wordStart - 1);
                }
                current.append(wordStart - 1, wordStart);
            }

            int wordWidth = FormatUtils.displayWidth(text.substring(wordStart, wordEnd));
            if (wordWidth > wrapColumns) {
                int remaining = wrapColumns - current.width;
                int breaksHere = 1 + Math.floorDiv(wordWidth - remaining - 1, wrapColumns);
                int breaksNext = Math.floorDiv(wordWidth - 1, wrapColumns);
                if (breaksNext < breaksHere && !current.isEmpty()) {
                    finish(current, measured);
                    current = new LineBuilder(wordStart);
                }
                current = appendHardWrapped(wordStart, wordEnd, current, measured);
            } else {
                if (current.width + wordWidth > wrapColumns
                        && current.width > 0 && wordWidth > 0) {
                    finish(current, measured);
                    current = new LineBuilder(wordStart);
                }
                current.append(wordStart, wordEnd);
            }

            if (space == end) break;
            wordStart = space + 1;
            wordIndex++;
        }
        finish(current, measured);
    }

    private LineBuilder appendHardWrapped(int start, int end, LineBuilder current,
                                          List<VisualLine> measured) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text.substring(start, end));
        int relativeStart = iterator.first();
        for (int relativeEnd = iterator.next(); relativeEnd != BreakIterator.DONE;
             relativeStart = relativeEnd, relativeEnd = iterator.next()) {
            int segmentStart = start + relativeStart;
            int segmentEnd = start + relativeEnd;
            int width = FormatUtils.displayWidth(text.substring(segmentStart, segmentEnd));
            if (!current.isEmpty() && current.width + width > wrapColumns) {
                finish(current, measured);
                current = new LineBuilder(segmentStart);
            }
            current.append(segmentStart, segmentEnd);
            if (current.width == wrapColumns && segmentEnd < end) {
                finish(current, measured);
                current = new LineBuilder(segmentEnd);
            }
        }
        return current;
    }

    private void finish(LineBuilder line, List<VisualLine> measured) {
        if (line.finished) return;
        line.finished = true;
        measured.add(new VisualLine(text.substring(line.start, line.end), line.start, line.end,
            isPrecededByNewline(line.start), false));
    }

    private boolean isPrecededByNewline(int offset) {
        return offset == 0 || offset > 0 && text.charAt(offset - 1) == '\n';
    }

    private int lineIndexAt(int offset) {
        for (int i = 0; i < lines.size(); i++) {
            int nextStart = i + 1 < lines.size() ? lines.get(i + 1).startOffset() : text.length() + 1;
            if (offset >= lines.get(i).startOffset() && offset < nextStart) return i;
        }
        return lines.size() - 1;
    }

    private static int leadingWhitespaceLength(String value) {
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) break;
            offset += Character.charCount(codePoint);
        }
        return offset;
    }

    private static int offsetAtDisplayWidth(String value, int targetWidth) {
        if (targetWidth <= 0 || value.isEmpty()) return 0;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        int width = 0;
        int result = 0;
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            int segmentWidth = FormatUtils.displayWidth(value.substring(start, end));
            if (width + segmentWidth > targetWidth) break;
            width += segmentWidth;
            result = end;
        }
        return result;
    }

    private final class LineBuilder {
        private final int start;
        private int end;
        private int width;
        private boolean finished;

        private LineBuilder(int start) {
            this.start = start;
            this.end = start;
        }

        private boolean isEmpty() {
            return end == start;
        }

        private void append(int from, int to) {
            if (to <= from) return;
            end = to;
            width += FormatUtils.displayWidth(text.substring(from, to));
        }
    }
}
