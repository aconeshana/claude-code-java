package com.claudecode.ui.lanterna.components;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.ibm.icu.text.Bidi;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Bidirectional (RTL/LTR) text reordering for terminals that don't perform bidi themselves.
 */
public final class BidiReorderer {

    private static final char ESC = 0x1B;

    private BidiReorderer() {}

    /** Whether bidi reordering should run. False on macOS unless forced. */
    public static boolean isEnabled() {
        String force = System.getProperty("claude.code.bidi");
        if (Strings.CS.equals("true", force)) {
            return true;
        }
        if (Strings.CS.equals("false", force)) {
            return false;
        }
        if (isMacOs()) {
            return false;
        }
        return isWindows() || isWsl() || isVscodeTerminal();
    }

    /** Whether the text contains any strongly-RTL (or bidi-control) character. */
    public static boolean containsBidi(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
            int cp = s.codePointAt(i);
            byte dir = Character.getDirectionality(cp);
            switch (dir) {
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT:
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC:
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING:
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE:
                case Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING:
                case Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE:
                case Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT:
                    return true;
                default:
                    // continue
            }
        }
        return false;
    }

    /**
     * Reorder an ANSI-coded string for visual (right-to-left-aware) display.
     * No-op when disabled or when the text contains no bidi characters. Newlines
     * are treated as paragraph breaks (each line reordered independently).
     */
    public static String reorder(String text) {
        if (!isEnabled() || !containsBidi(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int lineStart = 0;
        for (int k = 0; k <= text.length(); k++) {
            if (k == text.length() || text.charAt(k) == '\n') {
                out.append(reorderLine(text.substring(lineStart, k)));
                if (k < text.length()) {
                    out.append('\n');
                }
                lineStart = k + 1;
            }
        }
        return out.toString();
    }

    private static String reorderLine(String line) {
        // Split into plain characters and the ANSI sequences that precede each one.
        StringBuilder plain = new StringBuilder();
        List<String> ansiBefore = new ArrayList<>();
        StringBuilder pendingAnsi = new StringBuilder();
        StringBuilder trailing = new StringBuilder();

        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ESC) {
                int end = ansiSequenceEnd(line, i);
                pendingAnsi.append(line, i, end);
                i = end;
            } else if (c == '\n') {
                trailing.append(c);
                i++;
            } else {
                ansiBefore.add(pendingAnsi.toString());
                pendingAnsi.setLength(0);
                plain.append(c);
                i++;
            }
        }

        if (plain.isEmpty()) {
            return line; // only ANSI / empty — nothing to reorder
        }
        if (!pendingAnsi.isEmpty()) {
            trailing.append(pendingAnsi);
        }

        Bidi bidi = new Bidi(plain.toString(), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        byte[] levels = bidi.getLevels();
        int[] visualToLogical = Bidi.reorderVisual(levels);

        StringBuilder res = new StringBuilder(line.length() + 8);
        for (int l : visualToLogical) {
            res.append(ansiBefore.get(l));
            res.append(plain.charAt(l));
        }
        res.append(trailing);
        return res.toString();
    }

    /** Return the index just past the end of the ANSI escape sequence at {@code start} (ESC). */
    private static int ansiSequenceEnd(String s, int start) {
        if (start + 1 >= s.length()) {
            return start + 1;
        }
        char c1 = s.charAt(start + 1);
        if (c1 == '[') {
            // CSI: terminates at the first final byte (a letter).
            int j = start + 2;
            while (j < s.length()
                    && !((s.charAt(j) >= 'a' && s.charAt(j) <= 'z')
                        || (s.charAt(j) >= 'A' && s.charAt(j) <= 'Z'))) {
                j++;
            }
            return j < s.length() ? j + 1 : s.length();
        }
        if (c1 == ']') {
            // OSC: terminates at BEL (0x07) or ST (ESC \).
            int j = start + 2;
            while (j < s.length()) {
                char cj = s.charAt(j);
                if (cj == 0x07) {
                    return j + 1;
                }
                if (cj == ESC && j + 1 < s.length() && s.charAt(j + 1) == '\\') {
                    return j + 2;
                }
                j++;
            }
            return s.length();
        }
        // Other escape (e.g. ESC ( B) — consume ESC + next char conservatively.
        return Math.min(start + 2, s.length());
    }



    private static boolean isMacOs() {
        return Strings.CS.contains(osName(), "mac") || Strings.CS.contains(osName(), "darwin");
    }

    private static boolean isWindows() {
        return Strings.CS.contains(osName(), "windows");
    }

    private static boolean isWsl() {
        if (System.getenv("WSL_DISTRO_NAME") != null) {
            return true;
        }
        try {
            String release = Files.readString(Paths.get("/proc/version"));
            return Strings.CI.contains(release, "microsoft");
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean isVscodeTerminal() {
        String termProgram = System.getenv("TERM_PROGRAM");
        if (termProgram != null && Strings.CI.contains(termProgram, "vscode")) {
            return true;
        }
        return System.getenv("VSCODE_PID") != null;
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }
}
