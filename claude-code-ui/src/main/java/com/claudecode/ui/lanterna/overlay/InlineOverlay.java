package com.claudecode.ui.lanterna.overlay;

import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.concurrent.atomic.AtomicBoolean;



























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
     * Called by the scene host immediately before it redraws this overlay because a component
     * painted <em>beneath</em> it (typically the transcript) is being repainted in the same frame,
     * or because the whole scene is being repainted. Overlays whose renderer reuses cells it painted
     * on an earlier frame (pointer-only arrow-key updates, cached static chrome) must discard that
     * assumption here and repaint completely on the following draw, since the backdrop is about to
     * overwrite every cell they previously owned. Overlays that always paint their full area may keep
     * the no-op default.
     */
    default void onBackdropRepainted() { }

    /**
     * Whether this overlay wants the mouse wheel for itself.
     *
     * <p>Keyboard input is owned exclusively by the active overlay, but the wheel is a terminal
     * gesture rather than a keybinding: a modal question card in the released client does not stop
     * the user from scrolling the transcript behind it. The host therefore lets wheel events fall
     * through to the scroll handler unless an overlay with its own scrollable body opts in here.</p>
     */
    default boolean consumesMouseWheel() { return false; }

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
