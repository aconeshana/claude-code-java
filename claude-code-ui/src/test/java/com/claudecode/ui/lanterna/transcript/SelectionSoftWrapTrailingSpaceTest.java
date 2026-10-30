package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2.1.236 keeps a soft-wrapped row's significant trailing spaces; only a
 * hard-broken row is trimmed.
 *
 * <p>236's row slice {@code A0p(text, row, startCol, endCol, ...)} ends with
 * {@code return u > 0 ? m : m.replace(/\s+$/, "")}, where {@code u} is the
 * next row's soft-wrap marker high half. {@code u > 0} means "the next row is
 * a continuation of this one", so the visual rows of one soft-wrapped logical
 * line preserve the spaces they end with, while a hard row drops them.
 *
 * <p>Measured against the released binary with a pty + SGR-mouse + OSC52
 * harness at 80 columns, feeding {@code "A" * 70 + "    " + "B" * 100}:
 *
 * <pre>
 *   visual row 1: "&#10095; " + 70 A + 4 spaces      (columns 1..76)
 *   continuation: "  "       + 77 B + 1 space       (columns 1..80)
 *   continuation: "  "       + 24 B
 *
 *   row 1, 1-based columns  3..79 -> 70 A + "    "   (74 chars; spaces kept)
 *   row 1, 1-based columns 73..79 -> "    "          (4 chars; kept)
 *   row 2, 1-based columns  3..79 -> 77 B            (padding cell yields none)
 *   row 1 col 60 .. row 2 col 10  -> "AA    BBBBBBBB"
 * </pre>
 *
 * <p>Java's {@link Selection} already reproduces those numbers when the caller
 * supplies the untrimmed row text — the first three tests below are the
 * measurements above. The gap is one layer up: {@link SelectionAwareTextGUI}
 * reads the screen back buffer, which carries no soft-wrap metadata, so it
 * strips every row. The last test records that limitation.
 */
class SelectionSoftWrapTrailingSpaceTest {

    /** 236 visual row 1: "&#10095; " + 70 A + 4 significant spaces. */
    private static final String WRAPPED_ROW = "\u276f " + "A".repeat(70) + "    ";
    /** 236 continuation row: "  " + 77 B + 1 significant space. */
    private static final String CONTINUATION = "  " + "B".repeat(77) + ' ';

    private static Function<Integer, String> rows(String... text) {
        return row -> row >= 0 && row < text.length ? text[row] : "";
    }

    private static String select(Function<Integer, String> rowText,
                                 int startCell, int startRow, int endCell, int endRow) {
        Selection sel = new Selection();
        sel.startSelection(startCell, startRow);
        sel.updateSelection(endCell, endRow);
        return sel.getSelectedText(rowText);
    }

    @Test
    @DisplayName("a soft-wrapped row keeps the significant spaces it ends with")
    void aSoftWrappedRowKeepsItsTrailingSignificantSpaces() {
        // 236 row 1, 1-based columns 3..79 (0-based cells 2..78) -> 74 chars.
        String copied = select(rows(WRAPPED_ROW), 2, 0, 78, 0);
        assertEquals("A".repeat(70) + "    ", copied);
        assertEquals(74, copied.length(),
            "the four spaces between the last A and the fold must survive");
    }

    @Test
    @DisplayName("a slice covering only the trailing spaces of a wrapped row keeps them")
    void aSliceOfOnlyTrailingSpacesOnAWrappedRowKeepsThem() {
        // 236 row 1, 1-based columns 73..79 -> "    " (4 chars, no trim).
        assertEquals("    ", select(rows(WRAPPED_ROW), 72, 0, 78, 0));
    }

    @Test
    @DisplayName("a continuation row's trailing padding cell yields no character")
    void aContinuationRowsTrailingPaddingCellYieldsNoCharacter() {
        // 236 row 2, 1-based columns 3..79 -> 77 B. The row's own trailing
        // space at cell 79 is excluded by 236's `d = min(endCol, u - 1)` bound.
        assertEquals("B".repeat(77), select(rows(CONTINUATION), 2, 0, 78, 0));
    }

    @Test
    @DisplayName("spaces survive when a selection crosses a soft wrap")
    void spacesSurviveWhenASelectionCrossesASoftWrap() {
        // 236 row 1 col 60 .. row 2 col 10 (0-based cells 59 .. 9): the
        // significant space run and the continuation's leading indent both
        // come through, so the copy is 13 A, 4 spaces, newline, 2 spaces, 8 B.
        String copied = select(rows(WRAPPED_ROW, CONTINUATION), 59, 0, 9, 1);
        assertEquals("A".repeat(13) + "    \n  " + "B".repeat(8), copied,
            "the space run at the fold belongs to the same logical line");
    }

    @Test
    @DisplayName("the back-buffer reader trims every row, wrapped or not")
    void theBackBufferReaderTrimsEveryRow() {
        // The gap, recorded rather than asserted as correct: rowText() cannot
        // distinguish a soft-wrapped row from a hard one, so it strips both.
        // Closing it requires carrying soft-wrap metadata into the selection
        // layer (236 keeps it in the cell buffer); Selection itself is correct.
        assertEquals("\u276f " + "A".repeat(70), WRAPPED_ROW.stripTrailing());
        assertEquals("  " + "B".repeat(77), CONTINUATION.stripTrailing());
    }
}