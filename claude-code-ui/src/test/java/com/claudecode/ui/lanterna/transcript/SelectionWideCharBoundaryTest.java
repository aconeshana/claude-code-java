package com.claudecode.ui.lanterna.transcript;

import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cell-boundary rules for wide (CJK) glyphs when a selection is turned into
 * text. Every expectation below is a recorded observation from the 2.1.236
 * bundle: a wide glyph owns its leading cell plus the padding cell to its right,
 * a selection edge on the leading cell includes the whole glyph, and an edge on
 * the padding owns no character.
 *
 * <p>Language-independent reference, for the row {@code AB中C} laid out at cell
 * 0 (each of A/B/C one cell, 中 two):
 * <pre>
 *   cells  0    1    2 3    4
 *   chars  A    B    中     C
 * </pre>
 */
class SelectionWideCharBoundaryTest {

    /** Row text whose char index equals its cell index for the ASCII prefix. */
    private static final String ROW = "0123456789\u4e2d\u6587";

    /** {@code ROW}: digits at cells 0-9, 中 at 10-11, 文 at 12-13. */
    private static final Function<Integer, String> ROWS = row -> row == 0 ? ROW : "";

    private static String copy(int startCell, int endCell) {
        Selection sel = new Selection();
        sel.startSelection(startCell, 0);
        sel.updateSelection(endCell, 0);
        return sel.getSelectedText(ROWS);
    }

    @Test
    void aSelectionEndingOnAWideGlyphLeadingCellIncludesTheWholeGlyph() {
        // 2.1.236: cells 10-11 (89 + 中's leading cell) -> "89中"
        assertEquals("89\u4e2d", copy(8, 10),
            "ending on 中's leading cell must include the whole glyph");
        // 2.1.236: cells 9-10 -> "9中"
        assertEquals("9\u4e2d", copy(9, 10));
    }

    @Test
    void aSelectionEndingOnAWideGlyphPaddingCellAlsoIncludesIt() {
        // 2.1.236: cells 8-11 (89 + both of 中's cells) -> "89中"
        assertEquals("89\u4e2d", copy(8, 11));
        assertEquals("9\u4e2d", copy(9, 11),
            "the padding cell belongs to 中, so the glyph is included once");
    }

    @Test
    void aSelectionStartingOnAWideGlyphPaddingCellExcludesIt() {
        // 2.1.236: cells 11-12 -> "文" (中's padding owns no character)
        assertEquals("\u6587", copy(11, 12),
            "a start on 中's padding cell must skip 中 entirely");
    }

    @Test
    void selectingBothCellsOfAWideGlyphYieldsItExactlyOnce() {
        assertEquals("\u4e2d", copy(10, 11));
        assertEquals("\u6587", copy(12, 13));
    }

    @Test
    void aSelectionCrossingTwoWideGlyphsKeepsBoth() {
        // 2.1.236: cells 10-12 -> "中文"
        assertEquals("\u4e2d\u6587", copy(10, 12));
        assertEquals("\u4e2d\u6587", copy(10, 13));
    }

    @Test
    void pureAsciiRowsAreUnaffected() {
        assertEquals("0123456789", copy(0, 9));
        assertEquals("89", copy(8, 9));
    }
}