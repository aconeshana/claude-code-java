package com.claudecode.ui.lanterna.input;

import com.claudecode.core.process.SubprocessEnvironment;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.function.IntConsumer;

import org.apache.commons.lang3.StringUtils;

public final class MouseScrollHandler {


    private static final int    WHEEL_ACCEL_WINDOW_MS         = 40;
    private static final double WHEEL_ACCEL_STEP              = 0.3;
    private static final int    WHEEL_ACCEL_MAX               = 6;
    private static final int    WHEEL_BOUNCE_GAP_MAX_MS       = 200;
    private static final int    WHEEL_MODE_STEP               = 15;
    private static final int    WHEEL_MODE_CAP                = 15;
    private static final int    WHEEL_MODE_RAMP               = 3;
    private static final int    WHEEL_MODE_IDLE_DISENGAGE_MS  = 1500;
    private static final int    WHEEL_DECAY_HALFLIFE_MS       = 150;
    private static final int    WHEEL_BURST_MS                = 5;

/**
     * Mutable accel state — one instance per scroll target.
     */
    public static final class WheelAccelState {
        long    time;        // ms since some epoch — uses nanoTime / 1_000_000
        double  mult;
        int     dir;         // 0 / 1 / -1
        double  base;        // CLAUDE_CODE_SCROLL_SPEED, default 1
        boolean pendingFlip;
        boolean wheelMode;
        int     burstCount;
    }


    public static WheelAccelState newState() {
        return newState(readScrollSpeedBase());
    }

    static WheelAccelState newState(double base) {
        WheelAccelState s = new WheelAccelState();
        s.time = 0;
        s.mult = base;
        s.dir  = 0;
        s.base = base;
        s.pendingFlip = false;
        s.wheelMode   = false;
        s.burstCount  = 0;
        return s;
    }

    /**
     * Reads {@code CLAUDE_CODE_SCROLL_SPEED} env var — defaults to 1, clamped to (0, 20].
     */
    static double readScrollSpeedBase() {
        String raw = SubprocessEnvironment.get("CLAUDE_CODE_SCROLL_SPEED");
        if (StringUtils.isEmpty(raw)) return 1;
        try {
            double n = Double.parseDouble(raw);
            if (Double.isNaN(n) || n <= 0) return 1;
            return Math.min(n, 20);
        } catch (NumberFormatException _) {
            return 1;
        }
    }


    public static int computeWheelStep(WheelAccelState state, int dir, long now) {
        // Device-switch guard ①: idle disengage. Runs BEFORE pendingFlip
        // resolve so a pending bounce doesn't bypass it via the real-reversal
        // early return. state.time is either the last committed event OR the
        // deferred flip — both count as "last activity".
        if (state.wheelMode && now - state.time > WHEEL_MODE_IDLE_DISENGAGE_MS) {
            state.wheelMode = false;
            state.burstCount = 0;
            state.mult = state.base;
        }

        // Resolve any deferred flip BEFORE touching state.time/dir — we need
        // the pre-flip state.dir to distinguish bounce (flip-back) from real
        // reversal (flip persisted), and state.time (= bounce timestamp) for
        // the gap check.
        if (state.pendingFlip) {
            state.pendingFlip = false;
            if (dir != state.dir || now - state.time > WHEEL_BOUNCE_GAP_MAX_MS) {
                // Real reversal: new dir persisted, OR flip-back arrived too
                // late. Commit. The deferred event's 1 row is lost.
                state.dir = dir;
                state.time = now;
                state.mult = state.base;
                return (int) Math.floor(state.mult);
            }
            // Bounce confirmed: flipped back to original dir within the
            // window. state.dir/mult unchanged from pre-bounce. state.time
            // was advanced to the bounce below, so gap here = flip-back
            // interval — reflects the user's actual click cadence.
            state.wheelMode = true;
        }
        long gap = now - state.time;
        if (dir != state.dir && state.dir != 0) {
            // Flip. Defer — next event decides bounce vs. real reversal.
            // Advance time (but NOT dir/mult): if this turns out to be a
            // bounce, the confirm event's gap will be the flip-back interval,
            // which reflects the user's actual click rate.
            state.pendingFlip = true;
            state.time = now;
            return 0;
        }
        state.dir = dir;
        state.time = now;

        // ─── MOUSE (wheel mode, sticky until device-switch signal) ───
        if (state.wheelMode) {
            if (gap < WHEEL_BURST_MS) {
                // Device-switch guard ②: trackpad flick produces 100+ events
                // at <5 ms (measured); mouse produces ≤3. 5+ consecutive →
                // trackpad flick.
                if (++state.burstCount >= 5) {
                    state.wheelMode = false;
                    state.burstCount = 0;
                    state.mult = state.base;
                } else {
                    return 1;
                }
            } else {
                state.burstCount = 0;
            }
        }
        // Re-check: may have disengaged above.
        if (state.wheelMode) {

            // the curve handles it. No frac — rounding loss minor at high
            // mult.
            double m = Math.pow(0.5, gap / (double) WHEEL_DECAY_HALFLIFE_MS);
            double cap = Math.max(WHEEL_MODE_CAP, state.base * 2);
            double next = 1 + (state.mult - 1) * m + WHEEL_MODE_STEP * m;
            state.mult = Math.min(Math.min(cap, next), state.mult + WHEEL_MODE_RAMP);
            return (int) Math.floor(state.mult);
        }

        // ─── TRACKPAD / HI-RES (native, non-wheel-mode) ───
        // Tight 40 ms burst window: sub-40 ms events ramp, anything slower
        // resets. Trackpad flick delivers 200+ events at <20 ms gaps → rails
        // to cap 6. Trackpad slow swipe at 40-400 ms gaps → resets every
        // event → 1 row each.
        if (gap > WHEEL_ACCEL_WINDOW_MS) {
            state.mult = state.base;
        } else {
            double cap = Math.max(WHEEL_ACCEL_MAX, state.base * 2);
            state.mult = Math.min(cap, state.mult + WHEEL_ACCEL_STEP);
        }
        return (int) Math.floor(state.mult);
    }

    // ── KeyStroke helpers — used by LanternaReplScreen's WindowListener ──

    /**
     * Returns the scroll delta for a Lanterna {@link KeyStroke}, applying
     * the accel state. Positive = scroll-up (older content), negative =
     * scroll-down (newer content). Zero means: not a scroll event, OR a
     * deferred direction-flip — caller should no-op (scrolling by 0 is
     * already a no-op so this is safe to ignore).
     */
    public static int getScrollDelta(KeyStroke key, WheelAccelState state) {
        if (!(key instanceof MouseAction ma) || ma.getKeyType() != KeyType.MOUSE_EVENT) return 0;
        int dir;
        switch (ma.getActionType()) {
            case SCROLL_UP   -> dir = 1;
            case SCROLL_DOWN -> dir = -1;
            default          -> { return 0; }
        }
        int step = computeWheelStep(state, dir, System.nanoTime() / 1_000_000L);
        return dir * step;
    }

    /**
     * Stateless legacy entry point — fixed ±3, no accel.
     */
    @Deprecated
    public static int getScrollDelta(KeyStroke key) {
        if (!(key instanceof MouseAction ma) || ma.getKeyType() != KeyType.MOUSE_EVENT) return 0;
        return switch (ma.getActionType()) {
            case SCROLL_UP   -> 3;
            case SCROLL_DOWN -> -3;
            default          -> 0;
        };
    }

    /**
     * Stateless fire-and-forget helper — kept for legacy call sites that just
     * want a direction without accel. New code should hold a
     * {@link WheelAccelState} and call {@link #getScrollDelta(KeyStroke, WheelAccelState)}.
     */
    public static boolean handle(KeyStroke key, IntConsumer onScroll) {
        if (!(key instanceof MouseAction ma) || ma.getKeyType() != KeyType.MOUSE_EVENT) return false;
        if (ma.getActionType() == MouseActionType.SCROLL_UP) {
            onScroll.accept(1);
            return true;
        }
        if (ma.getActionType() == MouseActionType.SCROLL_DOWN) {
            onScroll.accept(-1);
            return true;
        }
        return false;
    }

    private MouseScrollHandler() {}
}
