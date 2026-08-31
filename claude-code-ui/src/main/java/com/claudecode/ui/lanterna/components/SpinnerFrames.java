package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.ui.lanterna.features.settings.UiSettings;

/**
 * Spinner glyph frame sets and timing constants.
 */
public final class SpinnerFrames {

    private SpinnerFrames() {}

    // ── Glyph cycle (asterisk gradient) ─────────────────────────────────────

    /** Ghostty terminal — uses `*` because the macOS glyph renders offset. */
    public static final List<String> CHARACTERS_GHOSTTY = List.of(
        "·", "✢", "✳", "✶", "✻", "*");

    /** macOS terminals — full Unicode asterisk gradient ending in ✽. */
    public static final List<String> CHARACTERS_DARWIN = List.of(
        "·", "✢", "✳", "✶", "✻", "✽");

    /** Linux / Windows fallback — substitutes `*` for ✳ (poor coverage.yml). */
    public static final List<String> CHARACTERS_OTHER = List.of(
        "·", "✢", "*", "✶", "✻", "✽");

    // ── Reduced-motion glyph ────────────────────────────────────────────────

    /** Single dot shown when reduced motion is enabled. */
    public static final String REDUCED_MOTION_DOT = "●";

    /** Full pulse cycle in ms when reduced motion is on (1s visible / 1s dim). */
    public static final int REDUCED_MOTION_CYCLE_MS = 2_000;

    /**
     * True when the user has reduced-motion enabled — either via {@code
     * CLAUDE_CODE_REDUCED_MOTION=1}/{@code true} (checked first for scripting/CI)
     * or the layered {@code prefersReducedMotion} setting (user → project → local, local wins; written
     * to the local tier.
     */
    public static volatile boolean REDUCED_MOTION = detectReducedMotion();

    private static boolean detectReducedMotion() {
        String v = SubprocessEnvironment.get("CLAUDE_CODE_REDUCED_MOTION");
        if (Strings.CS.equals("1", v) || Strings.CI.equals("true", v)) return true;
        if (StringUtils.isNotBlank(v)) return false; // explicit "0"/"false" — env wins either way
        return UiSettings.readPrefersReducedMotion();
    }

    /** Live setter for {@code /config set prefersReducedMotion <bool>}. */
    public static void setReducedMotion(boolean enabled) {
        REDUCED_MOTION = enabled;
    }

    // ── Stall animation ─────────────────────────────────────────────────────

    /**
     * No-token threshold before the spinner starts fading toward error red.
     */
    public static final int STALL_START_MS = 3_000;

    /**
     * Fade duration from base color to fully-red. After {@code STALL_START_MS},
     * intensity ramps linearly to 1 over this many ms.
     */
    public static final int STALL_FADE_MS  = 2_000;


    public static final int STALL_SMOOTH_TICK_MS = 50;

    /**
     * Smoothing step size: fraction of the remaining gap closed each tick.
     * Cumulative geometric decay toward the target intensity.
     */
    public static final double STALL_SMOOTH_STEP = 0.1;


    public static final int ERROR_RED_R = 171;
    public static final int ERROR_RED_G = 43;
    public static final int ERROR_RED_B = 63;

    /**
     * Inactivity threshold after which the shimmer effect is suppressed.
     */
    public static final int SHIMMER_STALL_MS = 10_000;


    public static final int SHOW_TOKENS_AFTER_MS = 16_000;

    // ── API ─────────────────────────────────────────────────────────────────

    /**
     * Picks the 6-char gradient for the current platform / terminal.
     */
    public static List<String> charactersForCurrentTerminal() {
        String term = System.getenv("TERM");
        if (term != null && Strings.CS.equals(term, "xterm-ghostty")) return CHARACTERS_GHOSTTY;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin")) return CHARACTERS_DARWIN;
        return CHARACTERS_OTHER;
    }

    /**
     * Builds the animation cycle: forward 6 chars then reversed 6 chars (12 frames).
     */
    public static List<String> animationFrames(List<String> chars) {
        List<String> frames = new ArrayList<>(chars.size() * 2);
        frames.addAll(chars);
        // Reverse copy without touching the source list.
        for (int i = chars.size() - 1; i >= 0; i--) frames.add(chars.get(i));
        return List.copyOf(frames);
    }

    /** Convenience: 12-frame cycle for the current terminal. */
    public static List<String> defaultAnimationFrames() {
        return animationFrames(charactersForCurrentTerminal());
    }

    /** Returns the glyph at {@code frame} index using {@code frames.size()} as cycle. */
    public static String glyphAt(List<String> frames, long frame) {
        return frames.get((int) Math.floorMod(frame, frames.size()));
    }

    // ── Stall intensity math (pure, testable) ───────────────────────────────

    /**
     * Computes the target stall intensity (0..1) from elapsed-since-last-token
     * milliseconds, ignoring tool activity. {@code hasActiveTools=true}
     * suppresses the stall — caller should pass 0 in that case.
     */
    public static double computeStallIntensity(long timeSinceLastTokenMs, boolean hasActiveTools) {
        if (hasActiveTools) return 0.0;
        if (timeSinceLastTokenMs <= STALL_START_MS) return 0.0;
        double after = timeSinceLastTokenMs - STALL_START_MS;
        return Math.min(after / STALL_FADE_MS, 1.0);
    }

    /**
     * Applies one smoothing step toward target — used by callers driving the
     * fade tick-by-tick. Returns the new intensity. Skips smoothing for
     * reduced-motion (caller passes raw target instead).
     */
    public static double smoothStep(double current, double target) {
        double diff = target - current;
        if (Math.abs(diff) < 0.01) return target;
        return current + diff * STALL_SMOOTH_STEP;
    }
}
