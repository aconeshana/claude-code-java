package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpinnerFramesTest {

    // ── Glyph cycles ────────────────────────────────────────────────────────

    @Test
    void characterSets_eachHaveSixGlyphs() {
        assertEquals(6, SpinnerFrames.CHARACTERS_GHOSTTY.size());
        assertEquals(6, SpinnerFrames.CHARACTERS_DARWIN.size());
        assertEquals(6, SpinnerFrames.CHARACTERS_OTHER.size());
    }

    @Test
    void ghosttyVariant_usesAsteriskNotTwelvePointed() {

        assertEquals("*", SpinnerFrames.CHARACTERS_GHOSTTY.get(5));
        assertEquals("✽", SpinnerFrames.CHARACTERS_DARWIN.get(5));
        assertEquals("✽", SpinnerFrames.CHARACTERS_OTHER.get(5));
    }

    @Test
    void otherVariant_swapsThirdGlyphForAsterisk() {
        // macOS gets ✳ at index 2; other terminals get a plain asterisk.
        assertEquals("✳", SpinnerFrames.CHARACTERS_DARWIN.get(2));
        assertEquals("*", SpinnerFrames.CHARACTERS_OTHER.get(2));
    }

    @Test
    void animationFrames_isForwardThenReverse_giving12Frames() {
        List<String> base = List.of("a", "b", "c");
        List<String> animated = SpinnerFrames.animationFrames(base);
        assertEquals(6, animated.size());
        assertEquals(List.of("a", "b", "c", "c", "b", "a"), animated);
    }

    @Test
    void defaultAnimationFrames_producesTwelveCharCycle() {
        assertEquals(12, SpinnerFrames.defaultAnimationFrames().size());
    }

    @Test
    void glyphAt_wrapsAroundEnd() {
        List<String> frames = SpinnerFrames.animationFrames(SpinnerFrames.CHARACTERS_OTHER);
        // Cycle length 12 — frame 12 must equal frame 0.
        assertEquals(SpinnerFrames.glyphAt(frames, 0),
                     SpinnerFrames.glyphAt(frames, 12));
        // Negative frame ids must also wrap (uses floorMod).
        assertEquals(SpinnerFrames.glyphAt(frames, 0),
                     SpinnerFrames.glyphAt(frames, -12));
    }

    // ── Timing constants ────────────────────────────────────────────────────

    @Test
    void reducedMotion_constantsMatchTs() {
        assertEquals(2_000, SpinnerFrames.REDUCED_MOTION_CYCLE_MS);
        assertEquals("●",   SpinnerFrames.REDUCED_MOTION_DOT);
    }

    @Test
    void stall_thresholdsMatchTs() {
        assertEquals(3_000, SpinnerFrames.STALL_START_MS);
        assertEquals(2_000, SpinnerFrames.STALL_FADE_MS);
        assertEquals(50,    SpinnerFrames.STALL_SMOOTH_TICK_MS);
        assertEquals(0.1,   SpinnerFrames.STALL_SMOOTH_STEP);
    }

    @Test
    void errorRed_matchesTsRgb() {

        assertEquals(171, SpinnerFrames.ERROR_RED_R);
        assertEquals(43,  SpinnerFrames.ERROR_RED_G);
        assertEquals(63,  SpinnerFrames.ERROR_RED_B);
    }

    @Test
    void showTokensAfter_matchesReleasedSixteenSecondThreshold() {
        assertEquals(16_000, SpinnerFrames.SHOW_TOKENS_AFTER_MS);
    }

    // ── computeStallIntensity ───────────────────────────────────────────────

    @Test
    void stallIntensity_zero_belowThreshold() {
        assertEquals(0.0, SpinnerFrames.computeStallIntensity(0, false));
        assertEquals(0.0, SpinnerFrames.computeStallIntensity(2_999, false));
        assertEquals(0.0, SpinnerFrames.computeStallIntensity(3_000, false));
    }

    @Test
    void stallIntensity_fadesLinearlyOverTwoSeconds() {
        // 1 second past threshold = 0.5 (halfway through 2000ms fade).
        assertEquals(0.5, SpinnerFrames.computeStallIntensity(4_000, false), 1e-9);
        // 2 seconds past = 1.0 (fully red).
        assertEquals(1.0, SpinnerFrames.computeStallIntensity(5_000, false), 1e-9);
        // Saturates at 1.0.
        assertEquals(1.0, SpinnerFrames.computeStallIntensity(60_000, false), 1e-9);
    }

    @Test
    void stallIntensity_suppressedByActiveTools() {
        // Long elapsed but tools active → 0.
        assertEquals(0.0, SpinnerFrames.computeStallIntensity(10_000, true));
    }

    // ── smoothStep ──────────────────────────────────────────────────────────

    @Test
    void smoothStep_closesTenPercentOfGap() {
        // From 0 toward 1, one step → 0 + (1 - 0) * 0.1 = 0.1.
        assertEquals(0.1, SpinnerFrames.smoothStep(0.0, 1.0), 1e-9);
        // From 0.5 toward 1, one step → 0.5 + 0.5 * 0.1 = 0.55.
        assertEquals(0.55, SpinnerFrames.smoothStep(0.5, 1.0), 1e-9);
    }

    @Test
    void smoothStep_snapsToTargetWhenGapIsTiny() {
        // |diff| < 0.01 → snap to target (avoid infinite tail).
        assertEquals(1.0, SpinnerFrames.smoothStep(0.995, 1.0), 1e-9);
        assertEquals(0.0, SpinnerFrames.smoothStep(0.001, 0.0), 1e-9);
    }
}
