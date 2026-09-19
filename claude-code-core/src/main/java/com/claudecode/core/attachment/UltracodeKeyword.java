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
     *
     * <p>A span never crosses a line break. A delimiter left open at the end of its line is
     * abandoned rather than paired with a closer further down the prompt: quotations and code
     * spans that matter here are single-line, and pairing across lines let one stray {@code <}
     * or {@code (} silently cover the rest of a multi-paragraph prompt.
     */
    private static List<int[]> quotedSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        char open = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                // Abandon an unterminated opener at the line break instead of letting it run on.
                open = 0;
                continue;
            }
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
        if (c == '<') return tagEnd(text, index) >= 0;
        // Only treat an apostrophe as an opening quote when it does not follow a word.
        if (c == '\'') return !isWordChar(text, index - 1);
        return PAIRS.containsKey(c);
    }

    /** Longest {@code <...>} run still treated as a tag; real tags are far shorter than prose. */
    private static final int MAX_TAG_SPAN = 48;

    /**
     * Index of the {@code >} closing a tag-like span that starts at {@code index}, or {@code -1}
     * when the {@code <} is a less-than sign in prose.
     *
     * <p>A bare tag-name check is not enough. In {@code "if tokens<limit and ultracode is on,
     * keep budget>0"} the name {@code limit} is perfectly plausible, and the old rule let that
     * {@code <} pair with the {@code >} forty characters later, swallowing the keyword and
     * silently dropping the orchestration attachment. So the whole run has to look like a tag:
     *
     * <ul>
     *   <li>a plausible name after an optional {@code /} — {@code <thinking>}, {@code </div>},
     *       {@code <a:b>}, {@code <br/>}, {@code <foo attr="x">};</li>
     *   <li>closed by {@code >} on the same line and within {@link #MAX_TAG_SPAN} characters;</li>
     *   <li>no sentence punctuation ({@code , ; ? !}) in the attribute list;</li>
     *   <li>at most one unquoted word after the name, since real attributes are
     *       {@code name="value"} pairs rather than a run of bare words.</li>
     * </ul>
     *
     * <p>Those last three are what separate markup from a comparison clause. Ordinary prose like
     * {@code n<10 ... k>2} or {@code a<b and ultracode then c>d} no longer opens a span.
     *
     * <p>Generics such as {@code List<String>} do match, so a keyword inside a type argument
     * stays suppressed. That is deliberate: it is code being quoted, the same reason backticks
     * and brackets suppress it.
     *
     * <p>Note this covers the tag itself, not a tag's <em>contents</em> — {@code <p>ultracode</p>}
     * still fires, matching the previous behavior, because each tag closes its own span.
     */
    private static int tagEnd(String text, int index) {
        int i = index + 1;
        if (i < text.length() && text.charAt(i) == '/') i++;
        if (i >= text.length() || !isNameStart(text.charAt(i))) return -1;
        i++;
        while (i < text.length() && isNameChar(text.charAt(i))) i++;
        if (i >= text.length()) return -1;
        char after = text.charAt(i);
        if (after != '>' && after != '/' && after != ' ' && after != '\t') return -1;
        int limit = Math.min(text.length(), index + MAX_TAG_SPAN);
        boolean quoted = false;
        boolean inWord = false;
        int words = 0;
        for (int j = i; j < limit; j++) {
            char c = text.charAt(j);
            if (c == '"' || c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '\n' || c == '<') return -1;
            if (c == ',' || c == ';' || c == '?' || c == '!') return -1;
            if (c == '>') return j;
            if (c == ' ' || c == '\t') {
                inWord = false;
                continue;
            }
            if (c == '=') {
                // An "=" binds the preceding word to its value, so the pair counts once.
                inWord = false;
                words--;
                continue;
            }
            if (!inWord) {
                inWord = true;
                if (++words > 1) return -1;
            }
        }
        return -1;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
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
