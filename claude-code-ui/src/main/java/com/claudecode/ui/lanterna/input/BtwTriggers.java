package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.TextColor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Locates {@code /btw} occurrences at the start of input text so they can be highlighted in the
 * prompt bar.
 */
public final class BtwTriggers {

    /**
     * Pattern matching {@code /btw} at the very start of input, case-insensitive, with a word boundary
     * after.
     */
    private static final Pattern BTW_PATTERN =
        Pattern.compile("^/btw\\b", Pattern.CASE_INSENSITIVE);

    private BtwTriggers() {}

    /**
     * A single highlight span: which substring of the input to colour.
     *
     * @param word  the matched text (e.g. {@code "/btw"})
     * @param start inclusive start offset within the input
     * @param end   exclusive end offset within the input
     */
    public record Trigger(String word, int start, int end) {}

    /**
     * Finds {@code /btw} positions in {@code text}.
     */
    public static List<Trigger> find(String text) {
        if (!couldMatch(text)) return List.of();
        Matcher m = BTW_PATTERN.matcher(text);
        return m.find()
            ? List.of(new Trigger(m.group(), m.start(), m.end()))
            : List.of();
    }

    /**
     * Cheap prefix gate for the input hot path.
     */
    private static boolean couldMatch(String text) {
        if (text == null || text.length() < 4 || text.charAt(0) != '/') return false;
        return (text.charAt(1) == 'b' || text.charAt(1) == 'B')
            && (text.charAt(2) == 't' || text.charAt(2) == 'T')
            && (text.charAt(3) == 'w' || text.charAt(3) == 'W');
    }

    /**
     * Convenience: returns the TextColor used when highlighting {@code /btw}.
     */
    public static TextColor highlightColor() {
        return LanternaTheme.statusCost(); // theme.warning
    }
}
