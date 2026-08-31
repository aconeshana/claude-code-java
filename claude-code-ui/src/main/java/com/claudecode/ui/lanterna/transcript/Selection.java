package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.TerminalTextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;


public final class Selection {

    /** A point in display-row coordinates. Row grows downward (0 = viewport top). */
    public record Point(int col, int row) {}

    /** Normalized selection bounds: {@code start} is always before {@code end} in reading order. */
    public record Bounds(Point start, Point end) {}

    private Point anchor;
    private Point focus;
    private boolean isDragging;

// ── Phase 3: scrolled-off accumulators ───────────────────────────── When the selection's
// anchor or focus scrolls off-screen during a viewport scroll, the underlying screen buffer
// loses the original row text.

    // Above: newest entries at END (closest to on-screen).
    // Below: newest entries at FRONT.
    private final List<String> scrolledOffAbove = new ArrayList<>();
    private final List<String> scrolledOffBelow = new ArrayList<>();
    // Pre-clamp virtual rows. When anchor/focus row is clamped to the
    // viewport edge during shiftSelection, the "true" row lives here so a
    // reverse scroll can restore the on-screen position and pop the
// matching accumulator entry. null when no clamp debt exists. matches

    private Integer virtualAnchorRow;
    private Integer virtualFocusRow;




    public void startSelection(int col, int row) {
        this.anchor = new Point(col, row);
        this.focus = null;
        this.isDragging = true;
        // New drag → flush any state from previous selection so virtual
        // rows and scrolledOff accumulators don't leak into this one.
        this.scrolledOffAbove.clear();
        this.scrolledOffBelow.clear();
        this.virtualAnchorRow = null;
        this.virtualFocusRow = null;
    }


    public void updateSelection(int col, int row) {
        if (!isDragging) return;
        if (focus == null && anchor != null
                && anchor.col == col && anchor.row == row) {
            return;
        }
        this.focus = new Point(col, row);
    }


    public void finishSelection() {
        this.isDragging = false;
    }


    public void clearSelection() {
        this.anchor = null;
        this.focus = null;
        this.isDragging = false;
        this.scrolledOffAbove.clear();
        this.scrolledOffBelow.clear();
        this.virtualAnchorRow = null;
        this.virtualFocusRow = null;
    }




    public boolean hasSelection() {
        return anchor != null && focus != null;
    }

    /** True while the user is mid-drag (mouse held down). */
    public boolean isDragging() {
        return isDragging;
    }


    public Bounds getSelectionBounds() {
        if (anchor == null || focus == null) return null;
        return comparePoints(anchor, focus) <= 0
            ? new Bounds(anchor, focus)
            : new Bounds(focus, anchor);
    }


    public boolean isCellSelected(int col, int row) {
        Bounds b = getSelectionBounds();
        if (b == null) return false;
        if (row < b.start.row || row > b.end.row) return false;
        if (row == b.start.row && col < b.start.col) return false;
        return row != b.end.row || col <= b.end.col;
    }


    public String getSelectedText(Function<Integer, String> rowText) {
        Bounds b = getSelectionBounds();
        if (b == null) return "";
        StringBuilder out = new StringBuilder();
// Prepend rows that scrolled off the top — they're the upper part of the original
// selection.
        for (String r : scrolledOffAbove) {
            out.append(r).append('\n');
        }
        for (int row = b.start.row; row <= b.end.row; row++) {
            String full = rowText.apply(row);
            if (full == null) full = "";
            int startCell = (row == b.start.row) ? b.start.col : 0;
            int endCell   = (row == b.end.row)   ? b.end.col  : Integer.MAX_VALUE;
            int startChar = cellToChar(full, startCell);

            // EXCLUSIVE char index for substring. Add 1 to the cell
            // position, then map back; if the next cell is the spacer-tail
            // of a CJK char, getStringCharacterIndex still returns the
            // CJK char index (same value as startChar+1) which is correct.
            // MAX_VALUE marks "to end of row" — adding 1 would overflow to
            // negative and silently empty the slice.
            int endCharExclusive = endCell == Integer.MAX_VALUE
                ? full.length() : cellToChar(full, endCell + 1);
            // Clamp so we never index past String end (rowText may be
            // shorter than (endCell+1) cells if the row is sparse).
            if (endCharExclusive < startChar) endCharExclusive = startChar;
            if (endCharExclusive > full.length()) endCharExclusive = full.length();
            if (endCharExclusive > startChar) {
                out.append(full, startChar, endCharExclusive);
            }
            if (row != b.end.row) out.append('\n');
        }
// Append rows that scrolled off the bottom — they're the lower part of the original
// selection.
        for (String r : scrolledOffBelow) {
            out.append('\n').append(r);
        }
        return out.toString();
    }

    /**
     * Convenience overload — takes a {@link List<String>} of row texts.
     * Equivalent to {@code getSelectedText(row -> row < rows.size() ? rows.get(row) : null)}.
     */
    public String getSelectedText(List<String> rows) {
        return getSelectedText(row -> row >= 0 && row < rows.size() ? rows.get(row) : null);
    }

    // ── Phase 2: word/line selection + Shift+Click extend ──────────────


    public void selectWordAt(String rowText, int cellCol, int row) {
        if (StringUtils.isEmpty(rowText)) return;
        int charIdx = cellToChar(rowText, cellCol);
        if (charIdx >= rowText.length()) return;
        if (!isWordChar(rowText.charAt(charIdx))) {

            // when wordBoundsAt returns null). Just start a normal selection.
            startSelection(cellCol, row);
            updateSelection(cellCol, row);
            return;
        }
        int lo = charIdx, hi = charIdx;
        while (lo > 0 && isWordChar(rowText.charAt(lo - 1))) lo--;
        while (hi + 1 < rowText.length() && isWordChar(rowText.charAt(hi + 1))) hi++;
        int loCell = charToCell(rowText, lo);
        int hiCell = charToCell(rowText, hi);
        this.anchor = new Point(loCell, row);
        this.focus = new Point(hiCell, row);
        this.isDragging = true;
    }


    public void selectLineAt(String rowText, int row) {
        int hiCell = (StringUtils.isEmpty(rowText)) ? 0
            : Math.max(0, TerminalTextUtils.getColumnWidth(rowText) - 1);
        this.anchor = new Point(0, row);
        this.focus = new Point(hiCell, row);
        this.isDragging = true;
    }

    /**
     * Shift+Click: extend the existing selection's focus to {@code (col, row)}.
     * If there's no active anchor, this becomes a fresh selection.
     */
    public void extendFocus(int col, int row) {
        if (anchor == null) {
            startSelection(col, row);
        }
        this.focus = new Point(col, row);
        this.isDragging = false;
    }

    /**
     * Keyboard-driven focus extension.
     */
    public void moveFocus(int col, int row) {
        if (focus == null) {
            // No active focus yet (anchor-only) — promote to a full

            // moveFocus on null focus is effectively a no-op there
            // because shift+arrow only fires after a click placed anchor,
            // but we're conservative.
            if (anchor == null) return;
        }
        this.focus = new Point(Math.max(0, col), Math.max(0, row));
        this.isDragging = false;
    }

    /** Accessor for caller-driven moveFocus: get current focus or anchor (fallback). */
    public Point getFocusOrAnchor() {
        if (focus != null) return focus;
        return anchor;
    }

    /**
     * Anchor point (where the drag started), or null.
     */
    public Point getAnchor() {
        return anchor;
    }


    public enum FocusDir { LEFT, RIGHT, UP, DOWN, LINE_START, LINE_END, WORD_LEFT, WORD_RIGHT }

    /**
     * High-level convenience: shift focus by one step in {@code dir}.
     * Looks up the current row width via {@code rowText.apply(row)} for
     * LINE_END / clamping at horizontal edges.
     */
    public void moveFocus(FocusDir dir, Function<Integer, String> rowText) {
        Point f = getFocusOrAnchor();
        if (f == null) return;
        int col = f.col, row = f.row;
        switch (dir) {
            case LEFT       -> col = Math.max(0, col - 1);
            case RIGHT      -> col = col + 1;
            case UP         -> row = Math.max(0, row - 1);
            case DOWN       -> row = row + 1;
            case LINE_START -> col = 0;
            case LINE_END   -> {
                String t = rowText.apply(row);
                col = (StringUtils.isEmpty(t)) ? 0
                    : Math.max(0, TerminalTextUtils.getColumnWidth(t) - 1);
            }
            case WORD_LEFT  -> {
                String t = rowText.apply(row);
                if (StringUtils.isNotEmpty(t)) {
                    int charIdx = cellToChar(t, col);
                    if (charIdx > 0) charIdx--;          // step back from anchor cell
                    // Skip non-word chars left, then skip word chars left,
// landing on the start of the previous word. matches
                    // Vim/readline word-back motion.
                    while (charIdx > 0 && !isWordChar(t.charAt(charIdx))) charIdx--;
                    while (charIdx > 0 && isWordChar(t.charAt(charIdx - 1))) charIdx--;
                    col = charToCell(t, charIdx);
                }
            }
            case WORD_RIGHT -> {
                String t = rowText.apply(row);
                if (StringUtils.isNotEmpty(t)) {
                    int charIdx = cellToChar(t, col);
                    // Skip word chars right, then skip non-word chars,
                    // landing on the next word's first char.
                    while (charIdx < t.length() && isWordChar(t.charAt(charIdx))) charIdx++;
                    while (charIdx < t.length() && !isWordChar(t.charAt(charIdx))) charIdx++;
                    col = charToCell(t, charIdx);
                }
            }
        }
        moveFocus(col, row);
    }

    // ── Phase 3: scroll-shift + scrolled-off accumulators ──────────────

    /**
     * Shift the whole selection's row by {@code dRow} rows.
     */
    public void shiftSelection(int dRow, int maxRow) {
        if (anchor == null || focus == null) return;
        // Virtual rows track pre-clamp positions so reverse scrolls
        // restore correctly. Without this, clamp(5→0) + shift(+10) = 10
        // (not the true 5), and scrolledOffAbove stays stale (highlight

        int vAnchor = (virtualAnchorRow != null ? virtualAnchorRow : anchor.row) + dRow;
        int vFocus  = (virtualFocusRow  != null ? virtualFocusRow  : focus.row)  + dRow;
        if ((vAnchor < 0 && vFocus < 0)
                || (vAnchor > maxRow && vFocus > maxRow)) {
            clearSelection();
            return;
        }
        // Debt = how far the nearer endpoint overshoots each edge. When
        // debt shrinks (reverse scroll), those rows are back on-screen —
        // pop from the accumulator so getSelectedText doesn't double-count.
        int oldMin = Math.min(
            virtualAnchorRow != null ? virtualAnchorRow : anchor.row,
            virtualFocusRow  != null ? virtualFocusRow  : focus.row);
        int oldMax = Math.max(
            virtualAnchorRow != null ? virtualAnchorRow : anchor.row,
            virtualFocusRow  != null ? virtualFocusRow  : focus.row);
        int oldAboveDebt = Math.max(0, -oldMin);
        int oldBelowDebt = Math.max(0, oldMax - maxRow);
        int newAboveDebt = Math.max(0, -Math.min(vAnchor, vFocus));
        int newBelowDebt = Math.max(0, Math.max(vAnchor, vFocus) - maxRow);
        if (newAboveDebt < oldAboveDebt) {
            // scrolledOffAbove pushes newest at the END (closest to on-screen)

            int drop = oldAboveDebt - newAboveDebt;
            for (int i = 0; i < drop && !scrolledOffAbove.isEmpty(); i++) {
                scrolledOffAbove.removeLast();
            }
        }
        if (newBelowDebt < oldBelowDebt) {
            // scrolledOffBelow unshifts newest at FRONT → pop from front.
            int drop = oldBelowDebt - newBelowDebt;
            for (int i = 0; i < drop && !scrolledOffBelow.isEmpty(); i++) {
                scrolledOffBelow.removeFirst();
            }
        }
        // Invariant: accumulator length ≤ debt. Truncate stale entries
        // (e.g. moveFocus cleared virtualFocusRow without trimming).

        while (scrolledOffAbove.size() > newAboveDebt) {
            scrolledOffAbove.removeFirst();   // above: keep END (newest)
        }
        while (scrolledOffBelow.size() > newBelowDebt) {
            scrolledOffBelow.removeLast();  // below: keep FRONT
        }
// Commit shifted points with edge clamp.
        this.anchor = new Point(anchor.col, clamp(vAnchor, maxRow));
        this.focus  = new Point(focus.col,  clamp(vFocus,  maxRow));
        this.virtualAnchorRow = (vAnchor < 0 || vAnchor > maxRow) ? vAnchor : null;
        this.virtualFocusRow  = (vFocus  < 0 || vFocus  > maxRow) ? vFocus  : null;
    }

    /**
     * Shift only the anchor endpoint during drag-to-autoscroll.
     */
    public void shiftAnchor(int dRow, int maxRow) {
        if (anchor == null) return;
        int raw = (virtualAnchorRow != null ? virtualAnchorRow : anchor.row) + dRow;
        this.anchor = new Point(anchor.col, clamp(raw, maxRow));
        this.virtualAnchorRow = (raw < 0 || raw > maxRow) ? raw : null;
    }

    /**
     * Capture rows that are about to scroll off the visible viewport.
     */
    public void captureScrolledRows(String side, List<String> rows) {
        if (rows == null || rows.isEmpty()) return;
        if (Strings.CS.equals("above", side)) {
            // newest = bottom of the outgoing batch → push at END
            scrolledOffAbove.addAll(rows);
        } else if (Strings.CS.equals("below", side)) {
            // newest = top of the outgoing batch → push at FRONT
            for (int i = rows.size() - 1; i >= 0; i--) {
                scrolledOffBelow.addFirst(rows.get(i));
            }
        }
    }

    private static int clamp(int v, int hi) {
        return Math.max(0, Math.min(hi, v));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Reading-order comparison: row first, then col. Negative ⇔ a before b. */
    private static int comparePoints(Point a, Point b) {
        int rowCmp = Integer.compare(a.row, b.row);
        return rowCmp != 0 ? rowCmp : Integer.compare(a.col, b.col);
    }

    /**
     * Convert a screen cell column to a String character index.
     * Lanterna's {@link TerminalTextUtils#getStringCharacterIndex} walks
     * the string accounting for CJK double-width chars; we cap at the
     * string length so callers can pass {@code Integer.MAX_VALUE} to mean
     * "end of row".
     */
    private static int cellToChar(String s, int cellCol) {
        if (StringUtils.isEmpty(s)) return 0;
        int totalCells = TerminalTextUtils.getColumnWidth(s);
        if (cellCol >= totalCells) return s.length();
        if (cellCol <= 0) return 0;
        return TerminalTextUtils.getStringCharacterIndex(s, cellCol);
    }

    /**
     * Inverse of {@link #cellToChar}: char index → screen cell column.
     * Used by selectWordAt to convert word bounds (char indices) back to
     * cell positions for Selection storage.
     */
    private static int charToCell(String s, int charIdx) {
        if (StringUtils.isEmpty(s)) return 0;
        if (charIdx <= 0) return 0;
        if (charIdx >= s.length()) return TerminalTextUtils.getColumnWidth(s);
        return TerminalTextUtils.getColumnIndex(s, charIdx);
    }

    /** Vim-style word char: letter / digit / underscore. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
