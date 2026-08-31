package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.text.FormatUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Parses an ANSI-coded string into a list of display lines, each a list of colored {@link
 * MessagePanel.Segment}s.
 */
public final class AnsiToSegments {

    private static final char ESC = '';
    private static final char BEL = '';

    private AnsiToSegments() {}

    /**
     * Parse an ANSI-coded string (output of {@link MarkdownRenderer}) into a list
     * of display lines, each a list of colored {@link MessagePanel.Segment}s.
     */
    public static List<List<MessagePanel.Segment>> ansiToLines(String ansiText, TextColor defaultColor) {

        ansiText = FormatUtils.expandTabs(ansiText);

        if (BidiReorderer.isEnabled() && BidiReorderer.containsBidi(ansiText)) {
            ansiText = BidiReorderer.reorder(ansiText);
        }
        List<List<MessagePanel.Segment>> lines = new ArrayList<>();
        List<MessagePanel.Segment> currentLine = new ArrayList<>();
        TextColor currentColor = defaultColor;
        Set<SGR> currentModifiers = new HashSet<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < ansiText.length()) {
            char c = ansiText.charAt(i);
            // Defensive recovery for a damaged line-leading CSI opener. A terminal/output
            // boundary must never expose Chalk's payload as visible text (for example

            // styling. Java normally receives ESC + '[' here, but if the single ESC byte
            // was lost while a streamed/rendered row crossed an output boundary, recover
            // only at the start of a logical line; this avoids treating ordinary inline
            // bracket text as terminal control data.
            if (c == '[' && currentLine.isEmpty() && buf.isEmpty()) {
                int sgrEnd = bareSgrEnd(ansiText, i);
                if (sgrEnd >= 0) {
                    String code = ansiText.substring(i + 1, sgrEnd);
                    currentColor = parseAnsiSgr(code, defaultColor, currentColor);
                    currentModifiers = parseAnsiModifiers(code, currentModifiers);
                    i = sgrEnd + 1;
                    continue;
                }
            }
            if (c == ESC) {
                char next = i + 1 < ansiText.length() ? ansiText.charAt(i + 1) : 0;
                if (next == '[') {
                    // CSI: [...m  (ANSI SGR color/style codes)
                    int j = i + 2;
                    while (j < ansiText.length() && !Character.isLetter(ansiText.charAt(j))) j++;
                    if (j < ansiText.length() && ansiText.charAt(j) == 'm') {
                        String code = ansiText.substring(i + 2, j);
                        if (!buf.isEmpty()) {
                            currentLine.add(new MessagePanel.Segment(buf.toString(), currentColor,
                                null, null, currentModifiers));
                            buf.setLength(0);
                        }
                        currentColor = parseAnsiSgr(code, defaultColor, currentColor);
                        currentModifiers = parseAnsiModifiers(code, currentModifiers);
                        i = j + 1;
                        continue;
                    }
                    i = j + 1; // other CSI — skip
                    continue;
                } else if (next == ']') {
                    // OSC: check for OSC 8 hyperlink ]8;;url<term>text]8;;<term>
                    if (i + 4 < ansiText.length()
                            && ansiText.charAt(i + 2) == '8'
                            && ansiText.charAt(i + 3) == ';'
                            && ansiText.charAt(i + 4) == ';') {
                        int urlStart = i + 5;
                        int term1 = oscTerminatorStart(ansiText, urlStart);
                        if (term1 > 0) {
                            String url = ansiText.substring(urlStart, term1);
                            int textStart = term1 + terminatorLength(ansiText, term1);
                            // Link text runs until the closing OSC 8 sequence (ESC ]).
                            int closeOsc = ansiText.indexOf(ESC + "]", textStart);
                            if (closeOsc >= 0) {
                                String linkText = ansiText.substring(textStart, closeOsc);
                                if (!buf.isEmpty()) {
                                    currentLine.add(new MessagePanel.Segment(buf.toString(), currentColor,
                                        null, null, currentModifiers));
                                    buf.setLength(0);
                                }
                                if (!linkText.isEmpty()) {
                                    // The link text may itself carry SGR color codes.
                                    // Parse them recursively so the codes don't leak
                                    // into the segment as raw ESC bytes, and each
                                    // colored run keeps the hyperlink URL.
                                    for (List<MessagePanel.Segment> subLine
                                            : ansiToLines(linkText, currentColor)) {
                                        for (MessagePanel.Segment seg : subLine) {
                                            currentLine.add(MessagePanel.Segment.hyperlink(
                                                seg.text(), seg.color(), url, seg.modifiers()));
                                        }
                                    }
                                }
                                // Skip the closing OSC and its terminator.
                                int term2 = oscTerminatorStart(ansiText, closeOsc);
                                i = term2 >= 0
                                    ? term2 + terminatorLength(ansiText, term2)
                                    : closeOsc + 2;
                                continue;
                            }
                        }
                    }
                    // Other OSC — skip to the next terminator (BEL or ST).
                    int term = oscTerminatorStart(ansiText, i);
                    i = term >= 0 ? term + terminatorLength(ansiText, term) : ansiText.length();
                    continue;
                }
                // Lone ESC — skip
                i++;
                continue;
            }
            if (c == '\n') {
                if (!buf.isEmpty()) {
                    currentLine.add(new MessagePanel.Segment(buf.toString(), currentColor,
                        null, null, currentModifiers));
                    buf.setLength(0);
                }
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        if (!buf.isEmpty()) {
            currentLine.add(new MessagePanel.Segment(buf.toString(), currentColor,
                null, null, currentModifiers));
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine);
        }
        return lines;
    }

    /** Returns the index of {@code m} for a supported line-leading bare SGR, else {@code -1}. */
    private static int bareSgrEnd(String text, int start) {
        int end = start + 1;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == 'm') {
                String code = text.substring(start + 1, end);
                return isSupportedSgr(code) ? end : -1;
            }
            if (!(Character.isDigit(c) || c == ';')) return -1;
            end++;
        }
        return -1;
    }

    private static boolean isSupportedSgr(String code) {
        if (code.isEmpty()) return true;
        if (code.matches("0|1|2|3|4|9|22|23|24|29|3[1-7]|9[0-7]")) return true;
        return code.matches("38;5;\\d{1,3}")
            || code.matches("38;2;\\d{1,3};\\d{1,3};\\d{1,3}");
    }

    /**
     * Index of the next OSC terminator at/after {@code from} — either BEL
     * ({@code U+0007}) or ST ({@code ESC \}). Returns -1 if none is found.
     */
    private static int oscTerminatorStart(String s, int from) {
        for (int k = from; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == BEL) return k;
            if (ch == ESC && k + 1 < s.length() && s.charAt(k + 1) == '\\') return k;
        }
        return -1;
    }

    /** Byte length of the terminator at {@code at}: BEL is 1, ST ({@code ESC \}) is 2. */
    private static int terminatorLength(String s, int at) {
        return s.charAt(at) == BEL ? 1 : 2;
    }

    /**
     * Map an ANSI SGR code string to a Lanterna {@link TextColor}. Text modifiers
     * are tracked separately by {@link #parseAnsiModifiers} and carried on the
     * resulting {@link MessagePanel.Segment}.
     * <p>
     * Supports the three common foreground variants:
     * <ul>
     *   <li>3-bit named: {@code 31..37, 90}</li>
     *   <li>8-bit indexed: {@code 38;5;n} (xterm 256-color)</li>
     *   <li>24-bit RGB: {@code 38;2;r;g;b} — emitted by
     *       {@link com.claudecode.ui.Ansi#coloredRgb} for theme-keyed colors
     *       (e.g. markdown codespan → {@code theme.permission}).</li>
     * </ul>
     */
    private static TextColor parseAnsiSgr(String code, TextColor defaultColor, TextColor currentColor) {
        // 24-bit / 256-color compound codes always contain ';'
        if (code.indexOf(';') >= 0) {
            String[] parts = code.split(";");
            if (parts.length >= 5 && Strings.CS.equals("38", parts[0]) && Strings.CS.equals("2", parts[1])) {
                try {
                    int r = clamp(Integer.parseInt(parts[2]));
                    int g = clamp(Integer.parseInt(parts[3]));
                    int b = clamp(Integer.parseInt(parts[4]));
                    return new TextColor.RGB(r, g, b);
                } catch (NumberFormatException _) { return currentColor; }
            }
            if (parts.length >= 3 && Strings.CS.equals("38", parts[0]) && Strings.CS.equals("5", parts[1])) {
                try {
                    int n = clamp(Integer.parseInt(parts[2]));
                    return new TextColor.Indexed(n);
                } catch (NumberFormatException _) { return currentColor; }
            }
            return currentColor;
        }
        return switch (code) {
            case "", "0"       -> defaultColor;              // full reset
            case "1"           -> LanternaTheme.inputText(); // bold → bright/white
            case "2"           -> LanternaTheme.welcomeDim(); // dim → gray
            case "3", "4", "9" -> currentColor;              // italic/underline/strike — keep
            case "22", "23", "24", "29" -> currentColor;
            case "31"          -> TextColor.ANSI.RED;
            case "32"          -> TextColor.ANSI.GREEN;
            case "33"          -> TextColor.ANSI.YELLOW;
            case "34"          -> TextColor.ANSI.BLUE;
            case "35"          -> TextColor.ANSI.MAGENTA;
            case "36"          -> TextColor.ANSI.CYAN;
            case "37"          -> TextColor.ANSI.WHITE;
            case "90"          -> LanternaTheme.welcomeDim(); // bright black / dark gray
            default            -> currentColor;
        };
    }

    private static Set<SGR> parseAnsiModifiers(String code, Set<SGR> current) {
        Set<SGR> next = new HashSet<>(current == null ? Set.of() : current);
        String[] parts = code.isEmpty() ? new String[]{"0"} : code.split(";");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (Strings.CS.equals("38", part) && i + 1 < parts.length) {
                if (Strings.CS.equals("2", parts[i + 1])) i = Math.min(parts.length - 1, i + 4);
                else if (Strings.CS.equals("5", parts[i + 1])) i = Math.min(parts.length - 1, i + 2);
                continue;
            }
            switch (part) {
                case "0" -> next.clear();
                case "1" -> next.add(SGR.BOLD);
                case "3" -> next.add(SGR.ITALIC);
                case "4" -> next.add(SGR.UNDERLINE);
                case "9" -> next.add(SGR.CROSSED_OUT);
                case "22" -> next.remove(SGR.BOLD);
                case "23" -> next.remove(SGR.ITALIC);
                case "24" -> next.remove(SGR.UNDERLINE);
                case "29" -> next.remove(SGR.CROSSED_OUT);
                default -> { }
            }
        }
        return next;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
