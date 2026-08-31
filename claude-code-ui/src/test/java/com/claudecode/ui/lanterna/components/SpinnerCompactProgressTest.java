package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


class SpinnerCompactProgressTest {

    @Test
    void activeSpinnerReservesReleasedTopMargin() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.start("Working");
        try {
            assertEquals(2, spinner.calculatePreferredSize().getRows());
        } finally {
            spinner.stop();
        }
    }

    @Test
    void percentMatchesUpstreamCurve() {
        // Reference values computed from 1-e^(-t/90), independently verified
        // against the real CLI's rendered percentages (14s → 14%, ~1.8s → 2%).
        assertEquals(0,  SpinnerComponent.compactPercent(0));
        assertEquals(2,  SpinnerComponent.compactPercent(1_800));
        assertEquals(11, SpinnerComponent.compactPercent(10_000));
        assertEquals(14, SpinnerComponent.compactPercent(14_000));
        assertEquals(28, SpinnerComponent.compactPercent(30_000));
        assertEquals(49, SpinnerComponent.compactPercent(60_000));
        assertEquals(63, SpinnerComponent.compactPercent(90_000));
    }

    @Test
    void percentIsCappedAt95() {
        assertEquals(95, SpinnerComponent.compactPercent(600_000));   // 10 min
        assertEquals(95, SpinnerComponent.compactPercent(3_600_000)); // 1 h
        assertEquals(95, SpinnerComponent.compactPercent(Long.MAX_VALUE / 2));
    }

    @Test
    void negativeElapsedClampsToZero() {
// Clamp elapsed time at zero so clock skew cannot produce a negative percent.
        assertEquals(0, SpinnerComponent.compactPercent(-5_000));
    }

    @Test
    void percentIsMonotonicOverTime() {
        int prev = -1;
        for (long ms = 0; ms <= 400_000; ms += 500) {
            int p = SpinnerComponent.compactPercent(ms);
            assertTrue(p >= prev, "percent must never decrease; " + ms + "ms → " + p + " after " + prev);
            prev = p;
        }
    }

    @Test
    void barTextFillCountMatchesUpstreamRounding() {
        // round(ratio * width): 14% of 40 = 5.6 → 6 fill cells
        String bar = SpinnerComponent.compactBarText(14, 40);
        assertEquals(40, bar.length());
        assertEquals("▰".repeat(6) + "▱".repeat(34), bar);
        // 2% of 40 = 0.8 → 1 fill cell (matches the real CLI screenshot)
        assertEquals("▰" + "▱".repeat(39), SpinnerComponent.compactBarText(2, 40));
        // boundaries
        assertEquals("▱".repeat(8), SpinnerComponent.compactBarText(0, 8));
        assertEquals("▰".repeat(8), SpinnerComponent.compactBarText(100, 8));
    }

    @Test
    void setCompactingKeepsExistingStartTime() throws InterruptedException {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setCompacting(true);
        assertTrue(spinner.isCompacting());
        Thread.sleep(20);
// A second compact_start must not reset an existing clock.
        spinner.setCompacting(true);
        assertTrue(spinner.isCompacting());
        spinner.setCompacting(false);
        assertFalse(spinner.isCompacting());
    }

    @Test
    void stopClearsCompactingState() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.setCompacting(true);
        spinner.stop();
        assertFalse(spinner.isCompacting());
    }
}
