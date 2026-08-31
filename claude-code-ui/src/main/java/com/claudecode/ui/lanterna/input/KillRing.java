package com.claudecode.ui.lanterna.input;

import java.util.ArrayDeque;
import java.util.Deque;

import org.apache.commons.lang3.StringUtils;
/**
 * Shared, process-global kill ring — matches the module-level state in ({@code killRing}, {@code
 * lastActionWasKill}, {@code lastActionWasYank}, {@code killRingIndex}).
 */
public final class KillRing {

    public static final KillRing INSTANCE = new KillRing();

    /** Direction controls how consecutive kills accumulate. */
    public enum Direction { APPEND, PREPEND }

    private static final int MAX_SIZE = 10;

    private final Deque<String> ring = new ArrayDeque<>();
    private boolean lastWasKill = false;
    private boolean lastWasYank = false;
    private int yankIndex = 0;

    private KillRing() {}

    /**
     * Push killed text — matches {@code pushToKillRing(text, direction)}.
     * Consecutive kills (lastWasKill == true) accumulate into the top entry;
     * otherwise a new entry is prepended. Resets yank state.
     */
    public void push(String text, Direction direction) {
        if (StringUtils.isEmpty(text)) return;
        if (lastWasKill && !ring.isEmpty()) {
            String top = ring.peekFirst();
            String merged = direction == Direction.PREPEND ? text + top : top + text;
            ring.pollFirst();
            ring.addFirst(merged);
        } else {
            ring.addFirst(text);
            if (ring.size() > MAX_SIZE) ring.pollLast();
        }
        lastWasKill = true;
        lastWasYank = false;
        yankIndex = 0;
    }

/** Returns the most recently killed text, or "" if ring is empty. matches {@code getLastKill}. */
    public String getLast() {
        String s = ring.peekFirst();
        return s != null ? s : "";
    }

    /**
     * Record a successful yank — matches {@code recordYank(start, length)}.
     * Callers track position separately via their own fields.
     */
    public void recordYank() {
        lastWasKill = false;
        lastWasYank = true;
        yankIndex = 0;
    }

    /** Keeps a yank-pop chain active without rewinding its current ring index. */
    public void continueYank() {
        lastWasKill = false;
        lastWasYank = true;
    }

    /**
     * Cycle to the next kill ring entry after a yank — matches {@code yankPop}.
     * Returns the next entry's text, or null if yank-pop is not applicable
     * (ring has ≤ 1 entry, or last action was not a yank).
     */
    public String yankPop() {
        if (!lastWasYank || ring.size() <= 1) return null;
        yankIndex = (yankIndex + 1) % ring.size();
        return getByIndex(yankIndex);
    }

    /**
     * Reset kill accumulation — matches {@code resetKillAccumulation}.
     * Called for every non-kill keypress so consecutive kills stop accumulating.
     */
    public void resetAccumulation() {
        lastWasKill = false;
    }

    /**
     * Break the yank-pop chain — matches {@code resetYankState}.
     * Called for every non-yank keypress so Alt+Y only works right after a yank.
     */
    public void resetYankState() {
        lastWasYank = false;
    }

    private String getByIndex(int idx) {
        int i = 0;
        for (String s : ring) {
            if (i++ == idx) return s;
        }
        return getLast();
    }
}
