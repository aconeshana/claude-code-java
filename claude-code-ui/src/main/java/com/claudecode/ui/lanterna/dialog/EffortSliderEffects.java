package com.claudecode.ui.lanterna.dialog;

import com.googlecode.lanterna.TextColor;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Frame maths for the three {@code /effort} slider animations: the violet ripple that washes
 * over the whole strip while {@code ultracode} is selected, the shimmer that travels along a
 * selected {@code xhigh} label, and the rainbow that scrolls across a selected {@code max}.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/commands/effort/effort.tsx} — the ripple distance and ramp-index helpers,
 *       the ripple-aware text renderer, and the shimmer and rainbow label components.</li>
 * </ul>
 *
 * <p>Everything here is a pure function of (position, elapsed time) so the renderer stays
 * free of animation state and the formulae can be unit-tested directly. Authoritative
 * constants are from the 2.1.236 bundle.
 *
 * <p>Upstream freezes all three animations under a reduce-motion preference. This project has
 * no such setting yet, so no frame-freezing path exists here; adding one is a TODO that would
 * land alongside the preference itself.
 */
final class EffortSliderEffects {

    private EffortSliderEffects() {}

    /** Ripple redraw interval. Faster than the label animations — the wave covers more cells. */
    static final long RIPPLE_FRAME_MS = 80;

    /** Shimmer and rainbow redraw interval. */
    static final long LABEL_FRAME_MS = 100;

    /** How far the wavefront advances per millisecond, in cells. */
    private static final double RIPPLE_SPEED = 0.03;

    /** Distance between successive crests, in cells. */
    private static final int RIPPLE_WAVELENGTH = 20;

    /**
     * Row the ripple radiates from, in the renderer's ripple coordinate space — the label row.
     * See {@link EffortSliderDialog} for the mapping from screen rows to this space.
     */
    private static final int RIPPLE_ORIGIN_ROW = 2;

    /** Rows count double towards distance, compensating for cells being taller than wide. */
    private static final int ROW_ASPECT = 2;

    private static final int[] RIPPLE_DARK = {62, 22, 118};
    private static final int[] RIPPLE_LIGHT = {140, 80, 240};

    /** Eight-stop violet ramp, linearly interpolated between the two endpoints above. */
    static final List<TextColor> RIPPLE_RAMP = IntStream.range(0, 8)
        .mapToObj(step -> {
            double t = step / 7.0;
            return (TextColor) new TextColor.RGB(
                lerp(RIPPLE_DARK[0], RIPPLE_LIGHT[0], t),
                lerp(RIPPLE_DARK[1], RIPPLE_LIGHT[1], t),
                lerp(RIPPLE_DARK[2], RIPPLE_LIGHT[2], t));
        })
        .toList();

    /** Brightest ramp stop — also the violet used for the {@code ultracode} label itself. */
    static final TextColor VIOLET = RIPPLE_RAMP.getLast();

    /** Foreground over a rippled cell, and over the selected {@code ultracode} label. */
    static final TextColor RIPPLE_TEXT = new TextColor.RGB(255, 255, 255);

    /** Highlight at the shimmer's crest, and the foreground of rippled track cells. */
    static final TextColor SHIMMER_PEAK = new TextColor.RGB(0xd0, 0xb4, 0xff);

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    /** How far the wavefront has travelled after {@code elapsedMs}, in cells. */
    static double travel(long elapsedMs) {
        return elapsedMs * RIPPLE_SPEED;
    }

    /**
     * Distance from the ripple origin to {@code (col, row)}, with {@code row} in ripple
     * coordinate space.
     */
    static double distance(int col, int row, int originCol) {
        double dx = (double) col - originCol;
        double dy = (double) (row - RIPPLE_ORIGIN_ROW) * ROW_ASPECT;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Ramp stop for a cell at {@code distance} once the wavefront has reached {@code travel},
     * or {@code -1} when the wave has not arrived and the cell should render unstyled.
     *
     * <p>Behind the front, brightness follows a cosine of the distance lag, so the strip shows
     * continuous concentric bands rather than a single expanding edge.
     */
    static int rampIndex(double distance, double travel) {
        if (distance > travel) return -1;
        double lag = (distance - travel) % RIPPLE_WAVELENGTH;
        if (lag < 0) lag += RIPPLE_WAVELENGTH;
        double brightness = (1 + Math.cos(2 * Math.PI * lag / RIPPLE_WAVELENGTH)) / 2;
        int last = RIPPLE_RAMP.size() - 1;
        return Math.min(last, (int) Math.round(brightness * last));
    }

    /**
     * Index of the shimmer crest within a label of {@code length} characters on {@code frame}.
     * The cycle runs four characters past the end, so the crest pauses briefly between passes.
     */
    static int shimmerCrest(int length, long frame) {
        return (int) Math.floorMod(frame, (long) length + 4);
    }

    /** Frame counter for the label animations, from milliseconds since the slider opened. */
    static long labelFrame(long elapsedMs) {
        return elapsedMs / LABEL_FRAME_MS;
    }
}
