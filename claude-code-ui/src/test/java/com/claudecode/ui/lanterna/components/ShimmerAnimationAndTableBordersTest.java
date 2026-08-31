package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ShimmerAnimationAndTableBordersTest {

    // ── ShimmerAnimation ────────────────────────────────────────────────────

    @Test
    void shimmer_speedConstants_matchTs() {
        assertEquals(50,  ShimmerAnimation.REQUESTING_SPEED_MS);
        assertEquals(200, ShimmerAnimation.TOOL_USE_SPEED_MS);
    }

    @Test
    void shimmer_stalledReturnsOffScreen() {
        assertEquals(ShimmerAnimation.OFF_SCREEN,
            ShimmerAnimation.compute(ShimmerAnimation.Mode.REQUESTING, 20, 0L, true));
        assertEquals(ShimmerAnimation.OFF_SCREEN,
            ShimmerAnimation.compute(ShimmerAnimation.Mode.TOOL_USE, 20, 1234L, true));
    }

    @Test
    void shimmer_requesting_walksLeftToRight() {
        // At t=0, position = 0 - lead = -10 (just off the left edge).
        int messageWidth = 20;
        long speed = ShimmerAnimation.REQUESTING_SPEED_MS;
        int first = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.REQUESTING, messageWidth, 0L, false);
        int second = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.REQUESTING, messageWidth, speed, false);
        assertEquals(-10, first);
        assertEquals(-9,  second, "next tick must advance by 1 column");
    }

    @Test
    void shimmer_toolUse_walksRightToLeft() {
        // TOOL_USE returns messageWidth + 10 - position; at t=0 it sits at +30.
        int messageWidth = 20;
        long speed = ShimmerAnimation.TOOL_USE_SPEED_MS;
        int first = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.TOOL_USE, messageWidth, 0L, false);
        int second = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.TOOL_USE, messageWidth, speed, false);
        assertEquals(messageWidth + ShimmerAnimation.LEAD_OFFSET, first);
        assertEquals(first - 1, second, "next tick must move left by 1");
    }

    @Test
    void shimmer_cycleLength_wrapsAroundEvery_messageWidthPlusPadding() {
        int messageWidth = 20;
        int cycle = messageWidth + ShimmerAnimation.CYCLE_PADDING; // 40
        long speed = ShimmerAnimation.REQUESTING_SPEED_MS;

        int first = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.REQUESTING, messageWidth, 0L, false);
        int afterFullCycle = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.REQUESTING, messageWidth, speed * cycle, false);

        // After exactly one full cycle the glimmer is back at the start.
        assertEquals(first, afterFullCycle);
    }

    @Test
    void shimmer_negativeMessageWidth_isCoerced() {
        int v = ShimmerAnimation.compute(
            ShimmerAnimation.Mode.REQUESTING, -5, 0L, false);
        // Should not throw; cycle becomes CYCLE_PADDING, position 0 - 10 = -10.
        assertEquals(-ShimmerAnimation.LEAD_OFFSET, v);
    }

    // ── TableBorders ────────────────────────────────────────────────────────

    @Test
    void table_borderGlyphs_areCorrect() {
        // Top row.
        assertEquals('┌', TableBorders.TOP_LEFT);
        assertEquals('┬', TableBorders.TOP_TEE);
        assertEquals('┐', TableBorders.TOP_RIGHT);
        // Middle row.
        assertEquals('├', TableBorders.MID_LEFT);
        assertEquals('┼', TableBorders.MID_CROSS);
        assertEquals('┤', TableBorders.MID_RIGHT);
        // Bottom row.
        assertEquals('└', TableBorders.BOTTOM_LEFT);
        assertEquals('┴', TableBorders.BOTTOM_TEE);
        assertEquals('┘', TableBorders.BOTTOM_RIGHT);
        // Body.
        assertEquals('│', TableBorders.VERTICAL);
        assertEquals('─', TableBorders.HORIZONTAL);
    }

    @Test
    void table_layoutConstants_matchTs() {
        assertEquals(4, TableBorders.SAFETY_MARGIN);
        assertEquals(3, TableBorders.MIN_COLUMN_WIDTH);
        assertEquals(4, TableBorders.MAX_ROW_LINES);
    }

    @Test
    void table_borderOverhead_isOneBorderPlusThreePerColumn() {

        assertEquals(1, TableBorders.computeBorderOverhead(0));
        assertEquals(4, TableBorders.computeBorderOverhead(1));
        assertEquals(10, TableBorders.computeBorderOverhead(3));
    }

    @Test
    void table_borderRowFor_returnsCorrectQuadruple() {
        char[] top    = TableBorders.borderRowFor(TableBorders.Row.TOP);
        char[] middle = TableBorders.borderRowFor(TableBorders.Row.MIDDLE);
        char[] bottom = TableBorders.borderRowFor(TableBorders.Row.BOTTOM);
        assertArrayEquals(new char[]{'┌', '─', '┬', '┐'}, top);
        assertArrayEquals(new char[]{'├', '─', '┼', '┤'}, middle);
        assertArrayEquals(new char[]{'└', '─', '┴', '┘'}, bottom);
    }
}
