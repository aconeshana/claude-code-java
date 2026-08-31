package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownTableFormatterColumnWidthTest {

    // ── computeColumnWidths — 3-step algorithm ───────────────────────────────

    @Test
    void allFit_usesIdealWidths() {
        // totalIdeal (10) ≤ available (50) → use ideal
        int[] ideal = {5, 5};
        int[] min   = {2, 2};
        int[] result = MarkdownTableFormatter.computeColumnWidths(ideal, min, 50);
        assertArrayEquals(ideal, result);
    }

    @Test
    void idealOverflows_distributesByOverflowProportion() {
        // totalIdeal=20 > available=12; totalMin=6 ≤ available=12
        // extraSpace=6; overflow=[4,4,2]; totalOverflow=10
        // col0: 2 + floor(4/10*6)=2, col1: 2+2=4, col2: 2+floor(2/10*6)=3
        int[] ideal = {6, 6, 8}; // totalIdeal=20
        int[] min   = {2, 2, 2}; // totalMin=6
        int[] result = MarkdownTableFormatter.computeColumnWidths(ideal, min, 12);
        // available=12; each column gets at least minWidth, extras distributed by overflow.
        int sum = result[0] + result[1] + result[2];
        assertTrue(sum <= 12,
            "sum of widths must not exceed available=12 but got " + sum);
        assertTrue(sum >= 6,
            "sum of widths must be at least totalMin=6 but got " + sum);
        for (int w : result) {
            assertTrue(w >= TableBorders.MIN_COLUMN_WIDTH,
                "each width must be at least MIN_COLUMN_WIDTH=" + TableBorders.MIN_COLUMN_WIDTH);
        }
    }

    @Test
    void minOverflows_scaleProportionally() {
        // totalMin=12 > available=8 → scale factor = 8/12 ≈ 0.67
        int[] ideal = {6, 6};
        int[] min   = {6, 6}; // totalMin=12
        int[] result = MarkdownTableFormatter.computeColumnWidths(ideal, min, 8);
        for (int w : result) {
            assertTrue(w >= TableBorders.MIN_COLUMN_WIDTH,
                "scaled width must be at least MIN_COLUMN_WIDTH; got " + w);
        }
        // Total should be ≤ available (allow rounding down)
        assertTrue(result[0] + result[1] <= 8,
            "scaled widths must fit available=8; got sum=" + (result[0]+result[1]));
    }

    @Test
    void singleColumn_fitsWhenIdealFits() {
        int[] ideal = {20};
        int[] min   = {5};
        int[] result = MarkdownTableFormatter.computeColumnWidths(ideal, min, 30);
        assertArrayEquals(new int[]{20}, result);
    }

// ── format integration ────────────────────────────────────────────────

    @Test
    void simpleTable_renders_withBoxBorders() {
        String md = "| A | B |\n| --- | --- |\n| 1 | 2 |";
        String result = MarkdownTableFormatter.format(md, 80);
        assertTrue(Strings.CS.contains(result, "┌"), "must have top-left corner");
        assertTrue(Strings.CS.contains(result, "┘"), "must have bottom-right corner");
        assertTrue(Strings.CS.contains(result, "│ A "), "must include header A");
        assertTrue(Strings.CS.contains(result, "│ 1 "), "must include data 1");
    }

    @Test
    void narrowTerminal_doesNotExceedAvailableWidth() {
        // 3 columns, narrow terminal (30 cols)
        String md = "| Alpha | Beta | Gamma |\n| --- | --- | --- |\n| Long content here | Short | X |";
        String result = MarkdownTableFormatter.format(md, 30);
        // Each rendered line must not exceed 30 chars.
        for (String line : result.split("\n")) {
            assertTrue(line.length() <= 30,
                "line width " + line.length() + " exceeds terminal width 30: '" + line + "'");
        }
    }

    @Test
    void nonTableContent_passesThrough() {
        String md = "Hello\n| A | B |\n| --- | --- |\n| 1 | 2 |\nWorld";
        String result = MarkdownTableFormatter.format(md, 80);
        assertTrue(Strings.CS.startsWith(result, "Hello"), "non-table prefix must pass through");
        assertTrue(Strings.CS.endsWith(result, "World"), "non-table suffix must pass through");
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertEquals("", MarkdownTableFormatter.format("", 80));
        assertNull(MarkdownTableFormatter.format(null, 80));
    }
}
