package com.claudecode.ui.lanterna.transcript;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shift+arrow focus motion on {@link Selection}. The row edge is the terminal's
 * last column, not the row's text length, and left/right wrap to the adjacent
 * row — 2.1.236's {@code moveSelectionFocus} reads
 * {@code s = width - 1; case "left": if(l>i)l--; else if(c>0){l=s;c--} }.
 * Using the text length instead stalls the caret on the last glyph, which drops
 * the wrap-around cells when a selection is dragged across a full-width line.
 */
class SelectionFocusMotionTest {

    private static final int WIDTH = 10;

    private static Selection selectionAt(int col, int row) {
        Selection sel = new Selection();
        sel.startSelection(col, row);
        sel.updateSelection(col, row);
        return sel;
    }

    private static Function<Integer, String> rows(String... text) {
        return row -> row >= 0 && row < text.length ? text[row] : "";
    }

    @Test
    void shiftRightAtLastColumnWrapsToTheNextRowStart() {
        Selection sel = selectionAt(WIDTH - 1, 1);

        sel.moveFocus(Selection.FocusDir.RIGHT, rows("abc", "full line"), WIDTH);

        assertEquals(new Selection.Point(0, 2), sel.getFocusOrAnchor(),
            "the caret must leave the last column and continue on the next row");
    }

    @Test
    void shiftLeftAtFirstColumnWrapsToThePreviousRowLastColumn() {
        Selection sel = selectionAt(0, 2);

        sel.moveFocus(Selection.FocusDir.LEFT, rows("abc", "more"), WIDTH);

        assertEquals(new Selection.Point(WIDTH - 1, 1), sel.getFocusOrAnchor(),
            "the caret must leave column zero and continue at the previous row's edge");
    }

    @Test
    void shiftRightWrapsAtTheTerminalEdgeEvenWhenTheRowTextIsShort() {
        Selection sel = selectionAt(WIDTH - 1, 1);

        // Row 1 holds only "hi"; the edge is still the terminal width, so the
        // caret wraps instead of stopping at the text length.
        sel.moveFocus(Selection.FocusDir.RIGHT, rows("hi"), WIDTH);

        assertEquals(new Selection.Point(0, 2), sel.getFocusOrAnchor(),
            "a short row must not stop the caret: 236 uses width - 1, not the text width");
    }

    @Test
    void shiftRightInsideTheRowAdvancesOneColumn() {
        Selection sel = selectionAt(3, 1);

        sel.moveFocus(Selection.FocusDir.RIGHT, rows("abc"), WIDTH);

        assertEquals(new Selection.Point(4, 1), sel.getFocusOrAnchor());
    }

    @Test
    void shiftLeftInsideTheRowRetreatsOneColumn() {
        Selection sel = selectionAt(3, 1);

        sel.moveFocus(Selection.FocusDir.LEFT, rows("abc"), WIDTH);

        assertEquals(new Selection.Point(2, 1), sel.getFocusOrAnchor());
    }

    @Test
    void shiftLeftAtColumnZeroOfTheFirstRowStaysPut() {
        Selection sel = selectionAt(0, 0);

        sel.moveFocus(Selection.FocusDir.LEFT, rows("abc"), WIDTH);

        assertEquals(new Selection.Point(0, 0), sel.getFocusOrAnchor(),
            "there is no row above the first, so the caret cannot move");
    }

    @Test
    void lineEndMovesToTheTerminalLastColumnLike236() {
        Selection sel = selectionAt(2, 3);

        sel.moveFocus(Selection.FocusDir.LINE_END, rows("short"), WIDTH);

        assertEquals(new Selection.Point(WIDTH - 1, 3), sel.getFocusOrAnchor(),
            "236's lineEnd is s = width - 1, independent of the row's text length");
    }

    @Test
    void lineStartMovesToColumnZero() {
        Selection sel = selectionAt(5, 3);

        sel.moveFocus(Selection.FocusDir.LINE_START, rows("short"), WIDTH);

        assertEquals(new Selection.Point(0, 3), sel.getFocusOrAnchor());
    }

    @Test
    void wrapAroundSelectionCoversTheWholeRowItPassedThrough() {
        Selection sel = selectionAt(WIDTH - 2, 1);

        // Two Shift+Right presses: last column of row 1, then column 0 of row 2.
        sel.moveFocus(Selection.FocusDir.RIGHT, rows("ab"), WIDTH);
        sel.moveFocus(Selection.FocusDir.RIGHT, rows("ab"), WIDTH);

        Selection.Bounds b = sel.getSelectionBounds();
        assertEquals(new Selection.Point(WIDTH - 2, 1), b.start());
        assertEquals(new Selection.Point(0, 2), b.end(),
            "the focus must reach the next row so no cell between is dropped");
    }
}