package com.claudecode.core.attachment;

import java.util.concurrent.atomic.AtomicReference;

import com.claudecode.core.message.PlanModeExitInfo;

/**
 * One-shot carrier for the plan-mode-exit signal, consumed by the {@code plan_mode_exit} attachment
 * provider.
 */
public final class PlanModeExitSignal {

    private static final AtomicReference<PlanModeExitInfo> EXIT = new AtomicReference<>();

    private PlanModeExitSignal() {}

    /** Record that plan mode was just exited with the given plan file state. */
    public static void set(PlanModeExitInfo info) {
        EXIT.set(info);
    }

    /** Read and clear the pending exit signal, or {@code null} if none. */
    public static PlanModeExitInfo consume() {
        return EXIT.getAndSet(null);
    }

    /** Test/reset hook. */
    public static void clear() {
        EXIT.set(null);
    }
}
