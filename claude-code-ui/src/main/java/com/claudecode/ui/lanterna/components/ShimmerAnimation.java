package com.claudecode.ui.lanterna.components;


public final class ShimmerAnimation {

    private ShimmerAnimation() {}

    public enum Mode { REQUESTING, TOOL_USE }

    /** REQUESTING mode tick — 50 ms / frame (= 20 FPS). */
    public static final int REQUESTING_SPEED_MS = 50;
    /** TOOL_USE mode tick — 200 ms / frame (= 5 FPS). */
    public static final int TOOL_USE_SPEED_MS   = 200;

    /** Off-screen sentinel returned when stalled or reduced-motion. */
    public static final int OFF_SCREEN = -100;

    /** Padding added to the message width when computing the cycle length. */
    public static final int CYCLE_PADDING = 20;

    public static final int LEAD_OFFSET   = 10;

    /** Speed in ms / frame for the given mode. */
    public static int speedForMode(Mode mode) {
        return mode == Mode.REQUESTING ? REQUESTING_SPEED_MS : TOOL_USE_SPEED_MS;
    }

    /**
     * Computes the current glimmer column index for the given mode, elapsed
     * animation time, and message width (display columns, not bytes — callers
     * should pre-compute with {@code TerminalTextUtils.getColumnWidth}).
     *
     * @param mode          current spinner mode
     * @param messageWidth  rendered width of the spinner verb message in columns
     * @param elapsedMs     ms since the spinner started ticking (monotonic)
     * @param isStalled     true when the spinner is showing the stalled state
     * @return column index where the glimmer should land, or {@link #OFF_SCREEN}
     */
    public static int compute(Mode mode, int messageWidth, long elapsedMs, boolean isStalled) {
        if (isStalled) return OFF_SCREEN;
        if (messageWidth < 0) messageWidth = 0;

        int speed = speedForMode(mode);
        long cyclePosition = elapsedMs / speed;
        int cycleLength = messageWidth + CYCLE_PADDING;
        if (cycleLength <= 0) return OFF_SCREEN;

        int position = (int) Math.floorMod(cyclePosition, cycleLength);
        if (mode == Mode.REQUESTING) {
            return position - LEAD_OFFSET;
        }
        // TOOL_USE: walks the other direction.
        return messageWidth + LEAD_OFFSET - position;
    }
}
