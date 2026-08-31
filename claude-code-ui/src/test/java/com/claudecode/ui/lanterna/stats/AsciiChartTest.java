package com.claudecode.ui.lanterna.stats;

import org.apache.commons.lang3.Strings;

import com.claudecode.ui.lanterna.stats.AsciiChart.Cell;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AsciiChartTest {

    private static final LongFunction<String> FMT =
        x -> String.format(Locale.US, "%6d", x);

    private static String rowText(List<Cell> row) {
        StringBuilder sb = new StringBuilder();
        for (Cell c : row) sb.append(c.text());
        return sb.toString();
    }

    @Test
    void flatSeriesRendersHorizontalLine() {
        // [1,1,1]: range 0 → ratio 1, rows 0 → single row.
        List<List<Cell>> grid = AsciiChart.plot(List.of(new double[]{1, 1, 1}), 8, FMT);
        assertEquals(1, grid.size());
        List<Cell> row = grid.getFirst();
        // Series tick replaces the axis char, then two ─ cells.
        assertEquals("┼", row.get(2).text());
        assertEquals(0, row.get(2).seriesIndex());
        assertEquals("─", row.get(3).text());
        assertEquals("─", row.get(4).text());
        assertTrue(Strings.CS.startsWith(rowText(row), "     1"), "flat chart keeps raw label: " + rowText(row));
    }

    @Test
    void risingSeriesDrawsCorners() {
        // [0,2] with height 2: ratio 1 → rows 2; rise draws ╭ at top, ╯ at bottom, │ between.
        List<List<Cell>> grid = AsciiChart.plot(List.of(new double[]{0, 2}), 2, FMT);
        assertEquals(3, grid.size());
        assertEquals("╭", grid.getFirst().get(3).text());
        assertEquals("│", grid.get(1).get(3).text());
        assertEquals("╯", grid.get(2).get(3).text());
        // First-value tick at the axis column of the starting row.
        assertEquals("┼", grid.get(2).get(2).text());
        assertEquals(0, grid.get(2).get(2).seriesIndex());
        // Labels: max at top, min at bottom.
        assertTrue(Strings.CS.startsWith(rowText(grid.getFirst()), "     2"));
        assertTrue(Strings.CS.startsWith(rowText(grid.get(2)), "     0"));
    }

    @Test
    void fallingSeriesDrawsOppositeCorners() {
        List<List<Cell>> grid = AsciiChart.plot(List.of(new double[]{2, 0}), 2, FMT);
        // y0 > y1: rows-y1 gets ╰, rows-y0 gets ╮.
        assertEquals("╮", grid.getFirst().get(3).text());
        assertEquals("╰", grid.get(2).get(3).text());
    }

    @Test
    void secondSeriesKeepsItsIndex() {
        List<List<Cell>> grid = AsciiChart.plot(
            List.of(new double[]{0, 0, 0}, new double[]{2, 2, 2}), 2, FMT);
        // Top row: series 1's flat line; bottom row: series 0's.
        boolean sawSeries1 = grid.getFirst().stream().anyMatch(c -> Integer.valueOf(1).equals(c.seriesIndex()));
        boolean sawSeries0 = grid.get(2).stream().anyMatch(c -> Integer.valueOf(0).equals(c.seriesIndex()));
        assertTrue(sawSeries1, "series 1 painted on its own row");
        assertTrue(sawSeries0, "series 0 painted on its own row");
    }

    @Test
    void axisRowsCarryNoSeriesIndex() {
        List<List<Cell>> grid = AsciiChart.plot(List.of(new double[]{0, 2}), 2, FMT);
        // Middle row's axis char (col 2) is unclaimed by any series.
        assertNull(grid.get(1).get(2).seriesIndex());
        assertEquals("┤", grid.get(1).get(2).text());
    }
}
