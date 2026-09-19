package com.claudecode.ui.lanterna.dialog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code /effort} slider geometry to the 2.1.236 bundle's constants.
 *
 * <p>Upstream hardcodes the marker columns and the full-width value; this implementation
 * derives them. These tests are what makes that substitution safe — they assert the derived
 * values equal the hardcoded ones for the standard level list and each of its prefixes.
 */
class EffortSliderLayoutTest {

    private static final List<String> STANDARD =
        List.of("low", "medium", "high", "xhigh", "max");

    @Test
    void standardLevelsMatchUpstreamHardcodedGeometry() {
        EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD, false);

        assertEquals(List.of(0, 8, 19, 28, 39), layout.labelStarts());
        assertEquals(List.of(1, 10, 20, 30, 40), layout.markerColumns());
        assertEquals(42, layout.width());
        assertEquals("─".repeat(42), layout.trackChars());
        assertNull(layout.sublabelText());
        // No accent segment without ultracode, so every track cell renders dim.
        assertEquals(layout.width(), layout.accentStart());
    }

    /**
     * The gaps upstream puts between labels, observed through {@code labelStarts} rather than
     * through the builder's scratch spacer list — the starts are what the renderer actually
     * draws at, so pinning them pins the geometry that can regress.
     */
    @Test
    void labelGapsFollowTheUpstreamSpacerTable() {
        EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD, false);
        assertEquals(List.of(5, 5, 5, 6), gapsBetweenLabels(layout));

        // ultracode sits a wider gap out, to clear the ┆ divider.
        EffortSliderLayout withUltracode = EffortSliderLayout.compute(STANDARD, true);
        assertEquals(List.of(5, 5, 5, 6, 7), gapsBetweenLabels(withUltracode));
    }

    /** Blank cells between the end of each label and the start of the next. */
    private static List<Integer> gapsBetweenLabels(EffortSliderLayout layout) {
        List<Integer> gaps = new ArrayList<>();
        List<EffortSliderLayout.Slot> slots = layout.slots();
        for (int i = 1; i < slots.size(); i++) {
            int previousEnd = layout.labelStarts().get(i - 1) + slots.get(i - 1).value().length();
            gaps.add(layout.labelStarts().get(i) - previousEnd);
        }
        return gaps;
    }

    @Test
    void everyPrefixKeepsTheUpstreamMarkerColumns() {
        List<Integer> upstream = List.of(1, 10, 20, 30, 40);
        for (int n = 1; n <= STANDARD.size(); n++) {
            EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD.subList(0, n), false);
            assertEquals(upstream.subList(0, n), layout.markerColumns(),
                "marker columns for the first " + n + " levels");
        }
    }

    @Test
    void ultracodeAppendsAccentTrackAndSublabel() {
        EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD, true);

        assertEquals(6, layout.slots().size());
        assertEquals("ultracode", layout.slots().getLast().value());
        assertEquals(EffortSliderLayout.Accent.RIPPLE, layout.slots().getLast().accent());

        assertEquals(List.of(0, 8, 19, 28, 39, 49), layout.labelStarts());
        assertEquals(List.of(1, 10, 20, 30, 40, 53), layout.markerColumns());
        assertEquals(62, layout.width());
        assertEquals(44, layout.accentStart());
        assertEquals("xhigh + workflows", layout.sublabelText());
        assertEquals(45, layout.sublabelStart());
    }

    @Test
    void ultracodeTrackIsDividedAtTheAccentBoundary() {
        EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD, true);
        String track = layout.trackChars();

        assertEquals("─".repeat(43) + "┆" + "─".repeat(18), track);
        assertEquals(layout.width(), track.length());
        assertEquals('┆', track.charAt(43));
        // The ultracode marker has to land on the accent side of the divider, otherwise the
        // violet track segment and the selected slot would disagree about where it starts.
        assertTrue(layout.markerColumns().getLast() >= layout.accentStart());
    }

    @Test
    void accentRolesFollowTheLevelTable() {
        EffortSliderLayout layout = EffortSliderLayout.compute(STANDARD, true);
        assertEquals(
            List.of(EffortSliderLayout.Accent.WARNING, EffortSliderLayout.Accent.SUCCESS,
                EffortSliderLayout.Accent.PERMISSION, EffortSliderLayout.Accent.SHIMMER,
                EffortSliderLayout.Accent.RAINBOW, EffortSliderLayout.Accent.RIPPLE),
            layout.slots().stream().map(EffortSliderLayout.Slot::accent).toList());
    }

    @Test
    void gptOnlyLevelsGetPlainAccentsAndStillLayOut() {
        // none/minimal have no upstream slot, so they fall back to the plain treatment rather
        // than borrowing a colour from a neighbouring level.
        EffortSliderLayout layout =
            EffortSliderLayout.compute(List.of("none", "minimal", "low"), false);

        assertEquals(List.of(EffortSliderLayout.Accent.PLAIN, EffortSliderLayout.Accent.PLAIN,
            EffortSliderLayout.Accent.WARNING),
            layout.slots().stream().map(EffortSliderLayout.Slot::accent).toList());
        assertEquals(List.of(0, 9, 21), layout.labelStarts());
        assertEquals(24, layout.width());
        assertEquals(layout.width(), layout.trackChars().length());
    }

    @Test
    void shortListsStillFillTheMinimumWidth() {
        EffortSliderLayout layout = EffortSliderLayout.compute(List.of("low"), false);
        assertEquals(14, layout.width());
        assertEquals(14, layout.trackChars().length());
    }
}
