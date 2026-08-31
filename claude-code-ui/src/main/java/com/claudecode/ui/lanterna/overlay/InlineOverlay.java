package com.claudecode.ui.lanterna.overlay;

import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;



























public interface InlineOverlay {

    /** Whether this overlay currently owns keyboard input. */
    boolean isActive();

    /** Whether this overlay remains mounted and visible in the scene. */
    default boolean isVisibleInScene() { return isActive(); }

    /**
     * Whether the active component paints over transcript rows.
     */
    default boolean overlaysTranscript() { return true; }

    /**
     * Handles a key while this overlay is active. Implementations should set
     * {@code deliver} to {@code false} when handled; the host additionally enforces
     * exclusive ownership for unhandled keys as an input-isolation boundary.
     *
     * @param key     the keystroke to consider
     * @param deliver mutable flag — {@code false} means "consumed, stop here"
     */
    void handleKey(KeyStroke key, AtomicBoolean deliver);

    /**
     * Handles a terminal-drain run of printable characters. Stateful overlays
     * may override this to mutate once; the default preserves exact key order.
     */
    default void handlePlainText(String text, AtomicBoolean deliver) {
        if (text == null) return;
        for (int index = 0; index < text.length(); index++) {
            handleKey(new KeyStroke(text.charAt(index), false, false), new AtomicBoolean(true));
        }
        deliver.set(false);
    }

    /** Handles one repeated key run while preserving exact order by default. */
    default void handleRepeatedKey(KeyStroke key, int count, AtomicBoolean deliver) {
        for (int index = 0; index < count; index++) {
            handleKey(key, new AtomicBoolean(true));
        }
        deliver.set(false);
    }

    /**
     * Advances an option index by {@code delta} within {@code [0, size)}, wrapping around at either
     * end.
     */
    static int cycleIndex(int current, int delta, int size) {
        return Math.floorMod(current + delta, size);
    }

    /**
     * Truncates {@code s} to at most {@code max} terminal columns, appending a single {@code …} when
     * clipped.
     */
    static String clip(String s, int max) {
        if (s == null || max <= 0) {
            return "";
        }
        if (TerminalTextUtils.getColumnWidth(s) <= max) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int cw = TerminalTextUtils.isCharDoubleWidth(ch) ? 2 : 1;
            if (w + cw > max - 1) {
                break;
            }
            sb.append(ch);
            w += cw;
        }
        return sb.append('…').toString();
    }
}
