package com.claudecode.ui.lanterna.stats;

import org.apache.commons.lang3.Strings;

import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class HeatmapRendererTest {

    @Test
    void percentilesAndIntensityRamp() {
        List<InteractiveSessionPort.DailyActivity> days = List.of(
            new InteractiveSessionPort.DailyActivity("2026-07-01", 10, 1, 0),
            new InteractiveSessionPort.DailyActivity("2026-07-02", 20, 1, 0),
            new InteractiveSessionPort.DailyActivity("2026-07-03", 30, 1, 0),
            new InteractiveSessionPort.DailyActivity("2026-07-04", 40, 1, 0));
        long[] p = HeatmapRenderer.calculatePercentiles(days);
        assertArrayEquals(new long[]{20, 30, 40}, p, "floor-indexed p25/p50/p75");

        assertEquals(0, HeatmapRenderer.getIntensity(0, p));
        assertEquals(1, HeatmapRenderer.getIntensity(10, p));
        assertEquals(2, HeatmapRenderer.getIntensity(20, p));
        assertEquals(3, HeatmapRenderer.getIntensity(30, p));
        assertEquals(4, HeatmapRenderer.getIntensity(40, p));
        assertEquals(4, HeatmapRenderer.getIntensity(999, p));
    }

    @Test
    void zeroActivityYieldsNullPercentilesAndIntensityZero() {
        assertNull(HeatmapRenderer.calculatePercentiles(List.of()));
        assertNull(HeatmapRenderer.calculatePercentiles(
            List.of(new InteractiveSessionPort.DailyActivity("2026-07-01", 0, 0, 0))));
        assertEquals(0, HeatmapRenderer.getIntensity(50, null));
    }

    @Test
    void gridGeometryAndLabels() {
        HeatmapRenderer.Heatmap h = HeatmapRenderer.render(List.of(), 80, ZoneOffset.UTC);
        assertEquals(7, h.grid().size(), "7 day rows");
        int width = h.grid().getFirst().size();
        assertEquals(Math.min(52, 80 - 4), width, "weeks fit terminal minus day-label gutter");
        // Day labels: only Mon/Wed/Fri carry text.
        assertEquals("    ", h.dayLabels().getFirst());
        assertEquals("Mon ", h.dayLabels().get(1));
        assertEquals("    ", h.dayLabels().get(2));
        assertEquals("Wed ", h.dayLabels().get(3));
        assertEquals("Fri ", h.dayLabels().get(5));
        assertTrue(Strings.CS.startsWith(h.monthLabelRow(), "    "), "month row has 4-space gutter");
    }

    @Test
    void activityCellLightsUpAndFutureCellsBlank() {
        // Mark "today" (UTC) active — its cell must be non-zero intensity.
        String today = StatsDateDisplay.today();
        HeatmapRenderer.Heatmap h = HeatmapRenderer.render(
            List.of(new InteractiveSessionPort.DailyActivity(today, 100, 1, 0)), 60, ZoneOffset.UTC);

        boolean litCell = h.grid().stream().flatMap(List::stream)
            .anyMatch(c -> c.intensity() >= 1);
        assertTrue(litCell, "today's activity must light a cell");

        // The last column (current week) has future days → blank cells with -1.
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        if (now.getDayOfWeek() != DayOfWeek.SATURDAY) {
            boolean hasFutureBlank = h.grid().stream()
                .map(List::getLast)
                .anyMatch(c -> c.intensity() == -1 && c.ch() == ' ');
            assertTrue(hasFutureBlank, "future days render blank");
        }
    }

    @Test
    void narrowTerminalClampsToMinimumWidth() {
        HeatmapRenderer.Heatmap h = HeatmapRenderer.render(List.of(), 10, ZoneOffset.UTC);
        assertEquals(10, h.grid().getFirst().size(), "min 10 weeks");
    }
}
