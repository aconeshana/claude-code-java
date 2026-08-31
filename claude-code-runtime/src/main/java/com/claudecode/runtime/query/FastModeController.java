package com.claudecode.runtime.query;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Shared session state for Fast Mode preference, request eligibility, and cooldown.
 */
public final class FastModeController {

    private final boolean available;
    private final LongSupplier clock;
    private final Consumer<Boolean> preferenceSink;
    private boolean enabled;
    private long cooldownUntilMs;
    private FastModeCooldownReason cooldownReason;

    public FastModeController(boolean available, boolean initiallyEnabled, LongSupplier clock) {
        this(available, initiallyEnabled, clock, _ -> {});
    }

    public FastModeController(boolean available, boolean initiallyEnabled, LongSupplier clock,
                              Consumer<Boolean> preferenceSink) {
        this.available = available;
        this.enabled = available && initiallyEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.preferenceSink = Objects.requireNonNull(preferenceSink, "preferenceSink");
    }

    public boolean setEnabled(boolean value) {
        synchronized (this) {
            if (value && !available) return false;
        }
        preferenceSink.accept(value);
        return setEnabledFromRuntime(value);
    }

    /** Applies an SDK/flag override without writing it into the user settings tier. */
    public synchronized boolean setEnabledFromRuntime(boolean value) {
        if (value && !available) return false;
        enabled = value;
        if (!value) clearCooldown();
        return true;
    }

    public synchronized boolean enabled() {
        return enabled;
    }

    public synchronized FastModeRuntimeState state(String model) {
        expireCooldown();
        if (!available || !enabled || !supportsModel(model)) {
            return FastModeRuntimeState.OFF;
        }
        return cooldownUntilMs > clock.getAsLong()
            ? FastModeRuntimeState.COOLDOWN : FastModeRuntimeState.ON;
    }

    public synchronized boolean isFastRequest(String model) {
        return state(model) == FastModeRuntimeState.ON;
    }

    public synchronized void enterCooldown(Duration duration, FastModeCooldownReason reason) {
        if (!available || !enabled || duration == null || duration.isNegative()) return;
        cooldownUntilMs = Math.max(clock.getAsLong(), clock.getAsLong() + duration.toMillis());
        cooldownReason = reason;
    }

    public synchronized FastModeCooldownReason cooldownReason() {
        expireCooldown();
        return cooldownReason;
    }

    public boolean available() {
        return available;
    }

    public static boolean supportsModel(String model) {
        if (StringUtils.isBlank(model)) return false;
        String normalized = model.trim();
        return Strings.CI.equals("default", normalized)
            || Strings.CI.equals("opus", normalized)
            || Strings.CI.equals("opus[1m]", normalized)
            || Strings.CI.contains(normalized, "claude-opus-4-6")
            || Strings.CI.contains(normalized, "claude-opus-4-8")
            || Strings.CI.contains(normalized, "claude-opus-5");
    }

    private void expireCooldown() {
        if (cooldownUntilMs > 0L && cooldownUntilMs <= clock.getAsLong()) clearCooldown();
    }

    private void clearCooldown() {
        cooldownUntilMs = 0L;
        cooldownReason = null;
    }
}
