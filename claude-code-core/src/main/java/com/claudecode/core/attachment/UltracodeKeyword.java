package com.claudecode.core.attachment;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a bare {@code ultracode} mention in a prompt — the keyword that opts a single turn
 * into multi-agent orchestration.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/utils/keywords.ts} — keyword scanning that skips quoted, bracketed and
 *       tag-like spans so a keyword pasted inside code or a quotation does not fire.</li>
 * </ul>
 *
 * <p>The exclusions matter: prompts routinely quote the word while discussing it, and a
 * mention inside backticks or a code sample is talking <em>about</em> the keyword rather than
 * asking for it.
 */
public final class UltracodeKeyword {

    private UltracodeKeyword() {}

    private static final String KEYWORD = "ultracode";

    private static final Pattern WORD =
        Pattern.compile("\\b" + KEYWORD + "\\b", Pattern.CASE_INSENSITIVE);

    /** Opening delimiter to its closing counterpart. */
    private static final Map<Character, Character> PAIRS = Map.of(
        '`', '`', '"', '"', '\'', '\'', '<', '>', '{', '}', '[', ']', '(', ')');

    /**
     * Whether {@code input} asks for orchestration by naming the keyword outside of any quoted
     * or bracketed span. Slash-command input never counts — {@code /effort ultracode} sets the
     * effort level and must not also fire the per-turn trigger.
     */
    public static boolean mentionedIn(String input) {
        if (StringUtils.isBlank(input)) return false;
        if (!Strings.CI.contains(input, KEYWORD)) return false;
        if (Strings.CS.startsWith(input, "/")) return false;

        List<int[]> quoted = quotedSpans(input);
        Matcher matcher = WORD.matcher(input);
        while (matcher.find()) {
            if (!isInside(quoted, matcher.start())) return true;
        }
        return false;
    }

    /**
     * Spans covered by a closed delimiter pair. Unterminated openers yield no span, so a lone
     * apostrophe or bracket cannot swallow the rest of the prompt.
     */
    private static List<int[]> quotedSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        char open = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (open != 0) {
                // A second "[" restarts the span, so "[[link]]" closes at the inner bracket.
                if (open == '[' && c == '[') {
                    start = i;
                    continue;
                }
                if (c != PAIRS.get(open)) continue;
                // An apostrophe followed by a letter is a contraction, not a closing quote.
                if (open == '\'' && isWordChar(text, i + 1)) continue;
                spans.add(new int[] {start, i + 1});
                open = 0;
            } else if (opens(text, i, c)) {
                open = c;
                start = i;
            }
        }
        return spans;
    }

    private static boolean opens(String text, int index, char c) {
        if (c == '<') {
            return index + 1 < text.length()
                && (Character.isLetter(text.charAt(index + 1)) || text.charAt(index + 1) == '/');
        }
        // Only treat an apostrophe as an opening quote when it does not follow a word.
        if (c == '\'') return !isWordChar(text, index - 1);
        return PAIRS.containsKey(c);
    }

    private static boolean isWordChar(String text, int index) {
        if (index < 0 || index >= text.length()) return false;
        char c = text.charAt(index);
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isInside(List<int[]> spans, int index) {
        for (int[] span : spans) {
            if (index >= span[0] && index < span[1]) return true;
        }
        return false;
    }
}
