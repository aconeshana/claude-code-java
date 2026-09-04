package com.claudecode.ui.lanterna.dialog.question;

import com.googlecode.lanterna.TerminalTextUtils;

/**
 * The visible slice of a single-line text buffer, plus the cursor's char offset within that slice.
 *
 * <p>The window scrolls so the insertion point always stays on screen — long pasted text no longer
 * hides edits happening at the tail (a plain prefix-clip made backspace look dead). Widths are
 * measured in display columns, not chars: double-width (CJK) input occupies two columns per char,
 * and slicing by char count would push the tail past the right edge where
 * {@code InlineOverlay.clip} cuts it off.
 *
 * <ul>
 *   <li>Covers: the horizontal-scroll behaviour of {@code TextInput} for the {@code Other} row of
 *       the list card and the design card's {@code Rl} notes editor. TODO: the bundle's input is
 *       {@code multiline}; this window stays single-line.</li>
 * </ul>
 *
 * @param start        index of the first visible char within the full text
 * @param visible      the visible slice
 * @param cursorColumn the cursor's char offset within {@link #visible}
 */
public record TextWindow(int start, String visible, int cursorColumn) {

    /**
     * Computes the window that keeps {@code cursor} visible within {@code viewWidth} columns.
     *
     * @param text      the whole buffer
     * @param cursor    the insertion point, clamped into {@code text}
     * @param viewWidth available columns; below 1 is treated as 1
     */
    public static TextWindow of(String text, int cursor, int viewWidth) {
        int safeWidth = Math.max(1, viewWidth);
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        // colPrefix[i] = display columns of text[0..i)
        int[] colPrefix = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            colPrefix[i + 1] = colPrefix[i]
                + (TerminalTextUtils.isCharDoubleWidth(text.charAt(i)) ? 2 : 1);
        }
        int start = 0;
        if (colPrefix[text.length()] > safeWidth) {
            int startCol = Math.min(
                Math.max(0, colPrefix[safeCursor] - safeWidth + 1),
                Math.max(0, colPrefix[text.length()] - safeWidth));
            while (start < safeCursor && colPrefix[start] < startCol) start++;
        }
        int end = start;
        while (end < text.length()
                && colPrefix[end + 1] - colPrefix[start] <= safeWidth) end++;
        return new TextWindow(start, text.substring(start, end), safeCursor - start);
    }
}
