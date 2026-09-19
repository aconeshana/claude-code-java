package com.claudecode.ui.lanterna.dialog;

import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the {@code /effort} slider animation maths to the 2.1.236 bundle's formulae.
 */
class EffortSliderEffectsTest {

    @Test
    void rippleRampInterpolatesBetweenTheTwoVioletEndpoints() {
        assertEquals(8, EffortSliderEffects.RIPPLE_RAMP.size());
        assertRgb(62, 22, 118, EffortSliderEffects.RIPPLE_RAMP.getFirst());
        assertRgb(140, 80, 240, EffortSliderEffects.RIPPLE_RAMP.getLast());
        assertEquals(EffortSliderEffects.VIOLET, EffortSliderEffects.RIPPLE_RAMP.getLast());
    }

    @Test
    void rippleRampRisesMonotonically() {
        for (int i = 1; i < EffortSliderEffects.RIPPLE_RAMP.size(); i++) {
            assertTrue(
                EffortSliderEffects.RIPPLE_RAMP.get(i).getRed()
                    > EffortSliderEffects.RIPPLE_RAMP.get(i - 1).getRed(),
                "stop " + i + " should be brighter than its predecessor");
        }
    }

    private static void assertRgb(int red, int green, int blue, TextColor actual) {
        assertEquals(red, actual.getRed(), "red");
        assertEquals(green, actual.getGreen(), "green");
        assertEquals(blue, actual.getBlue(), "blue");
    }

    @Test
    void cellsBeyondTheWavefrontAreUnstyled() {
        // -1 means "the wave has not arrived", which the renderer draws with no background.
        assertEquals(-1, EffortSliderEffects.rampIndex(10.0, 9.99));
        assertNotEquals(-1, EffortSliderEffects.rampIndex(10.0, 10.0));
    }

    @Test
    void theWavefrontItselfSitsAtTheBrightestStop() {
        // distance == travel puts the cosine at its peak.
        assertEquals(7, EffortSliderEffects.rampIndex(12.0, 12.0));
    }

    @Test
    void brightnessTroughsHalfAWavelengthBehindTheFront() {
        // Wavelength is 20 cells, so a 10-cell lag is the darkest point of the band.
        assertEquals(0, EffortSliderEffects.rampIndex(0.0, 10.0));
        // A full wavelength behind is back at the crest — the bands repeat rather than fade.
        assertEquals(7, EffortSliderEffects.rampIndex(0.0, 20.0));
    }

    @Test
    void rowsCountDoubleTowardsDistance() {
        // Cells are taller than wide, so one row off-origin costs the same as two columns.
        assertEquals(EffortSliderEffects.distance(2, 2, 0),
            EffortSliderEffects.distance(0, 3, 0), 1e-9);
    }

    @Test
    void travelAdvancesLinearlyWithElapsedTime() {
        assertEquals(0.0, EffortSliderEffects.travel(0), 1e-9);
        assertEquals(30.0, EffortSliderEffects.travel(1000), 1e-9);
    }

    @Test
    void shimmerCrestSweepsPastTheEndOfTheLabelBeforeRepeating() {
        int length = "xhigh".length();
        assertEquals(0, EffortSliderEffects.shimmerCrest(length, 0));
        assertEquals(4, EffortSliderEffects.shimmerCrest(length, 4));
        // The cycle is length + 4, so the crest spends four frames off the label each pass.
        assertEquals(0, EffortSliderEffects.shimmerCrest(length, 9));
    }

    @Test
    void labelFrameAdvancesEveryHundredMilliseconds() {
        assertEquals(0, EffortSliderEffects.labelFrame(99));
        assertEquals(1, EffortSliderEffects.labelFrame(100));
    }
}
