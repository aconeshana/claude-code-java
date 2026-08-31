package com.claudecode.ui.lanterna.components;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.input.PromptTextLayout;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.graphics.ThemeDefinition;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

/**
 * A {@link TextBox} that paints inline highlights and the prompt's visual-line projection.
 */
public class HighlightedTextBox extends TextBox {

    /** Source of highlight spans for the current text — re-evaluated each draw. */
    private final Supplier<List<Highlight>> highlightSupplier;

    /**
     * Source of inline ghost text — re-evaluated each draw.
     */
    private Supplier<String> ghostTextSupplier = () -> null;

    private Supplier<PromptTextLayout> visualLayoutSupplier = () -> null;
    /** Absolute UTF-16 cursor offset paired with {@link #visualLayoutSupplier}. */
    private IntSupplier absoluteCaretOffsetSupplier = () -> 0;

    public void setGhostTextSupplier(Supplier<String> supplier) {
        this.ghostTextSupplier = supplier == null ? () -> null : supplier;
    }

    public void setVisualLayoutSupplier(Supplier<PromptTextLayout> layoutSupplier,
                                        IntSupplier caretOffsetSupplier) {
        this.visualLayoutSupplier = layoutSupplier == null ? () -> null : layoutSupplier;
        this.absoluteCaretOffsetSupplier = caretOffsetSupplier == null
            ? () -> 0 : caretOffsetSupplier;
    }

    /**
     * A coloured substring span: {@code [start, end)} positions plus the text colour to use.
     */
    public record Highlight(int start, int end, TextColor color, boolean bold, int priority) {
        public Highlight(int start, int end, TextColor color) {
            this(start, end, color, false, 0);
        }
    }

    public HighlightedTextBox(TerminalSize preferredSize, Style style,
                              Supplier<List<Highlight>> highlightSupplier) {
        super(preferredSize, style);
        this.highlightSupplier = highlightSupplier;
    }

    /**
 * Replace the logical text and discard a horizontal scroll offset that no longer fits the new
 * value.
     */
    @Override
    public synchronized HighlightedTextBox setText(String text) {
        super.setText(text);
        resetViewportIfOutOfBounds();
        return this;
    }

    /**
     * Replaces text while retaining a viewport that the caller knows remains
     * valid. Prompt burst insertion only lengthens an existing logical line,
     * so it cannot strand the viewport beyond the new content and need not pay
     * for the renderer/theme/ancestor walk in {@link #setText(String)}.
     */
    public synchronized HighlightedTextBox setTextPreservingViewport(String text) {
        super.setText(text);
        return this;
    }

    private void resetViewportIfOutOfBounds() {
// The upward walk: AbstractComponent.getRenderer is synchronized and
// unconditionally calls getTheme, which recurses parent.getTheme
        // (also synchronized) all the way to the window. Called from the
        // synchronized setText above, that is child-monitor-then-ancestor —
// the reverse of updateScreen's top-down order. Base Lanterna's
        // TextBox.setText does NOT touch the renderer; this override is what
        // creates the hazard, hence the GUI-thread requirement documented on
        // setText. Keep any new viewport logic renderer-free if you can.
        TextBoxRenderer renderer = getRenderer();
        TerminalPosition viewport = renderer.getViewTopLeft();
        int lastRow = Math.max(0, getLineCount() - 1);
        if (viewport.getRow() > lastRow) {
            renderer.setViewTopLeft(TerminalPosition.of(0, 0));
            return;
        }
        String line = getLine(viewport.getRow());
        if (viewport.getColumn() > line.length()) {
            renderer.setViewTopLeft(TerminalPosition.of(0, 0));
        }
    }


    static List<Highlight> resolveHighlights(List<Highlight> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<Highlight> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(Highlight::start)
            .thenComparing(Comparator.comparingInt(Highlight::priority).reversed()));
        List<Highlight> resolved = new ArrayList<>();
        for (Highlight candidate : sorted) {
            if (candidate.start() >= candidate.end()) continue;
            boolean overlaps = resolved.stream().anyMatch(existing ->
                candidate.start() < existing.end() && candidate.end() > existing.start());
            if (!overlaps) resolved.add(candidate);
        }
        return List.copyOf(resolved);
    }

    @Override
    protected TextBoxRenderer createDefaultRenderer() {
        return new HighlightedTextBoxRenderer();
    }

    


    private class HighlightedTextBoxRenderer implements TextBoxRenderer {

        private TerminalPosition viewTopLeft = TerminalPosition.of(0, 0);

        @Override
        public TerminalPosition getViewTopLeft() {
            return viewTopLeft;
        }

        @Override
        public void setViewTopLeft(TerminalPosition position) {
            this.viewTopLeft = position;
        }

        @Override
        public TerminalPosition getCursorLocation(TextBox component) {
            if (component.isReadOnly()) return null;
            PromptTextLayout visualLayout = visualLayoutSupplier.get();
            if (visualLayout != null) {
                PromptTextLayout.Position position =
                    visualLayout.positionAt(absoluteCaretOffsetSupplier.getAsInt());
                return new TerminalPosition(position.column(), position.line());
            }
            TerminalPosition caret = component.getCaretPosition();
            String line = component.getLine(caret.getRow());
// caret.getColumn is a CHARACTER INDEX; the cursor location must be
// a SCREEN COLUMN. CJK characters take 2 columns — matches Lanterna's
            // DefaultTextBoxRenderer at TextBox.java:843-849.
            int charIdx = Math.min(caret.getColumn(), line.length());
            int screenCol = TerminalTextUtils.getColumnIndex(line, charIdx);
            int viewScreenCol = TerminalTextUtils.getColumnIndex(line,
                Math.min(viewTopLeft.getColumn(), line.length()));
            return new TerminalPosition(screenCol - viewScreenCol,
                                        caret.getRow() - viewTopLeft.getRow());
        }

        @Override
        public TerminalSize getPreferredSize(TextBox component) {
            PromptTextLayout visualLayout = visualLayoutSupplier.get();
            if (visualLayout != null) {
                int longest = visualLayout.lines().stream()
                    .map(PromptTextLayout.VisualLine::displayText)
                    .mapToInt(FormatUtils::displayWidth)
                    .max().orElse(0);
                return new TerminalSize(Math.max(longest, 10), visualLayout.lineCount());
            }
            // Return actual line count + longest line width so the parent
            // layout grows/shrinks with content. Original impl delegated back
// to component.getPreferredSize which forms a caching loop that
            // freezes at the initial constructor size — fine for SINGLE_LINE
            // (always 1 row) but breaks MULTI_LINE where Shift+Enter should
            // add a row.
            int lineCount = Math.max(1, component.getLineCount());
            int longest = 0;
            for (int i = 0; i < lineCount; i++) {
                String line = component.getLine(i);
                if (line == null) continue;
                int cols = TerminalTextUtils.getColumnWidth(line);
                if (cols > longest) longest = cols;
            }
            return new TerminalSize(Math.max(longest, 10), lineCount);
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, TextBox component) {
            TerminalSize size = graphics.getSize();
            if (size.getRows() == 0 || size.getColumns() == 0) return;

            ThemeDefinition td = component.getThemeDefinition();
            if (component.isFocused()) {
                if (component.isReadOnly()) graphics.applyThemeStyle(td.getSelected());
                else                        graphics.applyThemeStyle(td.getActive());
            } else {
                if (component.isReadOnly()) graphics.applyThemeStyle(td.getInsensitive());
                else                        graphics.applyThemeStyle(td.getNormal());
            }

            char fill = td.getCharacter("FILL", ' ');
            graphics.fill(fill);

            PromptTextLayout visualLayout = visualLayoutSupplier.get();
            if (visualLayout != null) {
                viewTopLeft = TerminalPosition.of(0, 0);
                drawVisualLayout(graphics, visualLayout, size);
                return;
            }


            // DefaultTextBoxRenderer.drawTextArea bookkeeping for single-line.
// viewTopLeft.getColumn and caret column are CHARACTER indices,
            // but the visibility test must compare SCREEN widths so CJK
            // characters (2 cells) don't make the view scroll twice as fast.
            if (!component.isReadOnly()) {
                TerminalPosition caret = component.getCaretPosition();
                String caretLine = component.getLine(caret.getRow());
                int charIdx = Math.min(caret.getColumn(), caretLine.length());
                int caretScreenCol = TerminalTextUtils.getColumnIndex(caretLine, charIdx);
                int viewScreenCol = TerminalTextUtils.getColumnIndex(caretLine,
                    Math.min(viewTopLeft.getColumn(), caretLine.length()));
                if (caretScreenCol < viewScreenCol) {
                    viewTopLeft = viewTopLeft.withColumn(charIdx);
                } else if (caretScreenCol >= size.getColumns() + viewScreenCol) {
                    // Walk the character index forward until caret is just inside
                    // the right edge — TerminalTextUtils.getStringCharacterIndex
                    // gives us the char index at the target screen column.
                    int targetScreenCol = Math.max(0, caretScreenCol - size.getColumns() + 1);
                    int newCharIdx = TerminalTextUtils.getStringCharacterIndex(
                        caretLine, targetScreenCol);
                    viewTopLeft = viewTopLeft.withColumn(newCharIdx);
                }
            }

            // SINGLE_LINE path: one row, no scrollbars, paint with highlights.
            if (component.getLineCount() <= 1) {
                String line = component.getLine(0);
                drawHighlightedLine(graphics, line, size.getColumns());
                return;
            }

            // Multi-line fallback (uncoloured) — InputPanel doesn't use this,
            // but keep the renderer correct in case it's reused.
            for (int row = 0; row < size.getRows(); row++) {
                int rowIndex = row + viewTopLeft.getRow();
                if (rowIndex >= component.getLineCount()) continue;
                String line = component.getLine(rowIndex);
                String visible = line.length() > viewTopLeft.getColumn()
                    ? line.substring(viewTopLeft.getColumn(),
                        Math.min(line.length(), viewTopLeft.getColumn() + size.getColumns()))
                    : "";
                graphics.putString(0, row, visible);
            }
        }

        private void drawVisualLayout(TextGUIGraphics graphics, PromptTextLayout layout,
                                      TerminalSize size) {
            List<Highlight> highlights = resolveHighlights(highlightSupplier == null
                ? List.of() : highlightSupplier.get());
            int caretOffset = absoluteCaretOffsetSupplier.getAsInt();
            PromptTextLayout.Position caretPosition = layout.positionAt(caretOffset);
            int rows = Math.min(size.getRows(), layout.lineCount());
            for (int row = 0; row < rows; row++) {
                PromptTextLayout.VisualLine visualLine = layout.lines().get(row);
                drawVisualLine(graphics, visualLine, row, size.getColumns(), highlights);
            }

            String ghost = ghostTextSupplier.get();
            if (StringUtils.isEmpty(ghost) || caretOffset != layout.text().length()
                    || caretPosition.line() >= size.getRows()
                    || caretPosition.column() >= size.getColumns()) return;
            int available = size.getColumns() - caretPosition.column();
            String visibleGhost = FormatUtils.truncateNoEllipsis(ghost, available);
            if (visibleGhost.isEmpty()) return;
            TextColor baseFg = graphics.getForegroundColor();
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(caretPosition.column(), caretPosition.line(), visibleGhost);
            graphics.setForegroundColor(baseFg);
        }

        private void drawVisualLine(TextGUIGraphics graphics,
                                    PromptTextLayout.VisualLine visualLine,
                                    int row, int viewColumns,
                                    List<Highlight> highlights) {
            String line = visualLine.displayText();
            int absoluteStart = visualLine.displayStartOffset();
            int absoluteEnd = absoluteStart + line.length();
            TextColor baseFg = graphics.getForegroundColor();
            int absoluteCursor = absoluteStart;
            while (absoluteCursor < absoluteEnd) {
                Highlight active = findHighlightAt(highlights, absoluteCursor);
                int runEnd;
                if (active == null) {
                    runEnd = nextHighlightStart(highlights, absoluteCursor, absoluteEnd);
                } else {
                    runEnd = Math.min(active.end(), absoluteEnd);
                }
                int localStart = absoluteCursor - absoluteStart;
                int localEnd = runEnd - absoluteStart;
                int x = FormatUtils.displayWidth(line.substring(0, localStart));
                if (x >= viewColumns) break;
                String slice = FormatUtils.truncateNoEllipsis(
                    line.substring(localStart, localEnd), viewColumns - x);
                graphics.setForegroundColor(active == null ? baseFg : active.color());
                if (active != null && active.bold()) {
                    graphics.putString(x, row, slice, SGR.BOLD);
                } else {
                    graphics.putString(x, row, slice);
                }
                absoluteCursor = runEnd;
            }
            graphics.setForegroundColor(baseFg);
        }

        /**
         * Render one line with highlight spans spliced in. The line is sliced
         * into runs: each run either falls entirely inside a highlight or
         * entirely outside. {@link TextGUIGraphics#putString} is called once
         * per run with the appropriate colour and SGR modifier set, so the
         * spans appear inline within the user's typed text.
         *
         * <p><b>CJK note:</b> All positional variables here ({@code cursor},
         * {@code viewStart}, {@code viewEnd}, etc.) are CHARACTER indices into
         * {@code line}. The graphics API ({@link TextGUIGraphics#putString}'s
         * x-coordinate) is in SCREEN COLUMNS, so we translate via
         * {@link TerminalTextUtils#getColumnIndex} every time we paint a slice.
         * Otherwise CJK input renders with gaps and the caret drifts.
         */
        private void drawHighlightedLine(TextGUIGraphics graphics, String line, int viewCols) {
            // Apply horizontal scroll: only paint the slice currently visible.
            // viewStart/viewEnd remain character indices; cap viewEnd by char
            // count first, then trim further if the resulting slice would
            // overflow the visible screen width.
            int viewStart = Math.min(viewTopLeft.getColumn(), line.length());
            int viewEnd;
            int viewStartScreenCol = TerminalTextUtils.getColumnIndex(line, viewStart);
            // Walk forward until adding the next char would exceed viewCols.
            int screenColAcc = 0;
            int charScan = viewStart;
            while (charScan < line.length()) {
                int chWidth = TerminalTextUtils.isCharCJK(line.charAt(charScan)) ? 2 : 1;
                if (screenColAcc + chWidth > viewCols) break;
                screenColAcc += chWidth;
                charScan++;
            }
            viewEnd = charScan;
            if (viewEnd <= viewStart) return;

            List<Highlight> highlights = resolveHighlights(highlightSupplier == null
                ? List.of() : highlightSupplier.get());

            // Save base style — we restore it after each highlighted run so
            // following plain text doesn't inherit the highlight colour.
            TextColor baseFg = graphics.getForegroundColor();

            int cursor = viewStart;
            while (cursor < viewEnd) {
                Highlight active = findHighlightAt(highlights, cursor);
                int sliceScreenX = TerminalTextUtils.getColumnIndex(line, cursor)
                                 - viewStartScreenCol;
                if (active == null) {
                    // Plain run: up to the next highlight start or viewEnd.
                    int next = nextHighlightStart(highlights, cursor, viewEnd);
                    String slice = line.substring(cursor, next);
                    graphics.setForegroundColor(baseFg);
                    graphics.putString(sliceScreenX, 0, slice);
                    cursor = next;
                } else {
                    // Highlighted run: from cursor to min(active.end, viewEnd).
                    int runEnd = Math.min(active.end(), viewEnd);
                    String slice = line.substring(cursor, runEnd);
                    graphics.setForegroundColor(active.color());
                    if (active.bold()) {
                        graphics.putString(sliceScreenX, 0, slice, SGR.BOLD);
                    } else {
                        graphics.putString(sliceScreenX, 0, slice);
                    }
                    cursor = runEnd;
                }
            }
            graphics.setForegroundColor(baseFg);

            // Inline ghost text — draw the dim suffix AT THE CARET, after


            // computes ghost in that case anyway).
            String ghost = ghostTextSupplier.get();
            if (StringUtils.isNotEmpty(ghost)) {
                int caretCharIdx = Math.min(
                    HighlightedTextBox.this.getCaretPosition().getColumn(),
                    line.length());
                if (caretCharIdx == line.length()) {
                    int caretScreenCol = TerminalTextUtils.getColumnIndex(line, caretCharIdx)
                                       - viewStartScreenCol;
                    if (caretScreenCol >= 0 && caretScreenCol < viewCols) {
                        int avail = viewCols - caretScreenCol;
                        int used = 0, cut = 0;
                        while (cut < ghost.length()) {
                            int w = TerminalTextUtils.isCharDoubleWidth(ghost.charAt(cut)) ? 2 : 1;
                            if (used + w > avail) break;
                            used += w;
                            cut++;
                        }
                        graphics.setForegroundColor(LanternaTheme.welcomeDim());
                        graphics.putString(caretScreenCol, 0, ghost.substring(0, cut));
                        graphics.setForegroundColor(baseFg);
                    }
                }
            }
        }

        /** Returns the highlight covering {@code pos}, or null. */
        private Highlight findHighlightAt(List<Highlight> highlights, int pos) {
            for (Highlight h : highlights) {
                if (pos >= h.start() && pos < h.end()) return h;
            }
            return null;
        }

        /** Returns the start of the next highlight after {@code pos}, capped at {@code limit}. */
        private int nextHighlightStart(List<Highlight> highlights, int pos, int limit) {
            int best = limit;
            for (Highlight h : highlights) {
                if (h.start() > pos && h.start() < best) best = h.start();
            }
            return best;
        }
    }
}
