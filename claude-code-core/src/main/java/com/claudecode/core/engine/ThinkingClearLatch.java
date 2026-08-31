package com.claudecode.core.engine;




public final class ThinkingClearLatch {

    private static volatile boolean latched = false;

    private ThinkingClearLatch() {}

/** Whether the latch has tripped — once true, stays true until {@link #reset}. */
    public static boolean isLatched() {
        return latched;
    }

    /** Trips the latch. Called by {@code LlmClientAdapter} once the idle gap since the
     *  last completed turn exceeds the prompt-cache TTL. */
    public static void trip() {
        latched = true;
    }

    /** Resets the latch to untripped. Called on {@code /clear} and after a completed
     *  compaction so a fresh conversation re-evaluates from "keep all thinking". */
    public static void reset() {
        latched = false;
    }
}
