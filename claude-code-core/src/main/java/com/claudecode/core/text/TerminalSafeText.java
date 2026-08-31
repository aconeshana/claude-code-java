package com.claudecode.core.text;

import com.claudecode.core.annotation.Explanation;

/**
 * Reduces arbitrary text to characters a terminal cell grid can hold: ANSI
 * escape sequences and control codes are removed, tabs become column-aligned
 * spaces, and line breaks are normalized to {@code \n}.
 *
 * <p>Transcript text is not guaranteed to be plain: recorded tool output, shell
 * captures and pasted terminal buffers routinely carry raw {@code ESC}. Lanterna
 * refuses such a character at paint time
 * ({@code TextCharacter} rejects {@code c < 32 || c == 127}), and the failure
 * surfaces on the GUI thread — one line of hostile text can therefore kill a
 * whole frame rather than the component that produced it. Callers that render
 * unstyled text into {@code Label}s pass it through here first.
 *
 * <p>Use {@code AnsiToSegments} instead when the escape sequences carry meaning
 * and should become real colors; this class is for plain-text surfaces where
 * they are noise.
 */
@Explanation("""
    Lanterna rejects raw control bytes during painting. Sanitizing plain-text
    surfaces prevents one malformed line from aborting the entire GUI frame.""")
public final class TerminalSafeText {

    private static final char ESC = 0x1B;
    private static final char DEL = 0x7F;
    private static final char C1_FIRST = 0x80;
    private static final char C1_LAST = 0x9F;

    private TerminalSafeText() {
    }

    /**
     * Returns {@code text} without escape sequences or control characters,
     * with tabs expanded and {@code \r\n} / {@code \r} folded to {@code \n}.
     * Null-safe; clean text is returned unchanged.
     */
    public static String sanitize(String text) {
        if (text == null) return null;
        return FormatUtils.expandTabs(stripEscapesAndControls(text));
    }

    /**
     * Like {@link #sanitize(String)} for single-row surfaces: line breaks become
     * spaces so the text cannot silently grow the height of its component.
     * Returns an empty string for {@code null}.
     */
    public static String sanitizeLine(String text) {
        if (text == null) return "";
        return sanitize(text).replace('\n', ' ');
    }

    private static String stripEscapesAndControls(String text) {
        if (!hasEscapeOrControl(text)) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ESC) {
                i = FormatUtils.ansiSequenceEnd(text, i);
                continue;
            }
            if (c == '\r') {
                out.append('\n');
                i += i + 1 < n && text.charAt(i + 1) == '\n' ? 2 : 1;
                continue;
            }
            if (c == '\n' || c == '\t') {
                out.append(c);
            } else if (!isControl(c)) {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static boolean hasEscapeOrControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') continue;
            if (isControl(c)) return true;
        }
        return false;
    }

    /** C0, DEL and C1 code points — none of them address a terminal cell. */
    private static boolean isControl(char c) {
        return c < 0x20 || c == DEL || c >= C1_FIRST && c <= C1_LAST;
    }
}
