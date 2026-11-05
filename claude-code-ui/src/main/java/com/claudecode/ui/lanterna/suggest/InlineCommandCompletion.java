package com.claudecode.ui.lanterna.suggest;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;

/**
 * Mid-input slash-command completion: a {@code /command} typed after other text completes as dim
 * ghost text after the caret, and Tab splices the full command into place without touching the
 * text on either side. Column-zero commands use the dropdown instead.
 *
 * <p>Pure functions over the prompt text and caret, so the token rules can be pinned without a
 * live text box.
 *
 * <ul>
 *   <li>2.1.236 bundle {@code kms(input, cursorOffset)} — {@link #locate}: the {@code /} must
 *       follow whitespace or one of {@code 。、？！}, the caret may not pass the end of the command
 *       name, and a leading {@code /add-dir}, {@code /cd}, {@code /resume}, {@code /plugin},
 *       {@code /plugins} or {@code /marketplace} ({@code jIE}) disables it because their
 *       arguments are paths and identifiers.</li>
 *   <li>2.1.236 bundle {@code xDc(commands, partialCommand, …)} — {@link #complete}: the first
 *       ranked command whose name extends the typed prefix case-insensitively, skipping an exact
 *       match that leaves nothing to add.</li>
 *   <li>2.1.236 bundle prompt-input ghost accept ({@code yt}) — {@link #accept}: re-locates the
 *       token and replaces it with {@code /fullCommand } before the untouched tail.</li>
 * </ul>
 */
public final class InlineCommandCompletion {

    /** The located token: the index of its {@code /} and the full command name after it. */
    public record Token(int slash, String partial) {}

    /**
     * Ghost text to paint after the token, the command it completes to, and the text offset the
     * ghost belongs at ({@code slash + 1 + partial.length()}).
     */
    public record Ghost(String suffix, String fullCommand, int insertPosition) {}

    /** The prompt after accepting a ghost, with the caret just past the inserted space. */
    public record Accepted(String text, int caret) {}

    /**
     * JavaScript's {@code \s} includes U+3000, which Java's {@code \s} does not; the CJK sentence
     * marks are the ones 2.1.236 lists explicitly.
     */
    private static final Pattern TOKEN_BEFORE_CARET =
        Pattern.compile("[\\s\\u3000\\u3002\\u3001\\uFF1F\\uFF01]/([a-zA-Z0-9._:-]*)$");

    private static final Pattern COMMAND_NAME_HEAD = Pattern.compile("^[a-zA-Z0-9._:-]*");

    /** 2.1.236's {@code jIE}: leading commands whose arguments must not complete as commands. */
    private static final Set<String> ARGUMENT_COMMANDS =
        Set.of("add-dir", "cd", "resume", "plugin", "plugins", "marketplace");

    private InlineCommandCompletion() {}

    /** The mid-input command token holding the caret, or null. */
    public static Token locate(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) return null;
        if (Strings.CS.startsWith(text, "/")) {
            int space = text.indexOf(' ');
            String leading = space < 0 ? text.substring(1) : text.substring(1, space);
            if (ARGUMENT_COMMANDS.contains(leading)) return null;
        }
        Matcher before = TOKEN_BEFORE_CARET.matcher(text.substring(0, caret));
        if (!before.find()) return null;
        int slash = before.start() + 1;
        Matcher name = COMMAND_NAME_HEAD.matcher(text.substring(slash + 1));
        String partial = name.find() ? name.group() : "";
        if (caret > slash + 1 + partial.length()) return null;
        return new Token(slash, partial);
    }

    /**
     * The ghost for {@code token} given command names in suggestion rank order, or null when the
     * token is empty or no name has anything left to add.
     */
    public static Ghost complete(Token token, List<String> rankedNames) {
        if (token == null || token.partial().isEmpty()) return null;
        String prefix = token.partial();
        for (String name : rankedNames) {
            if (name.length() > prefix.length() && Strings.CI.startsWith(name, prefix)) {
                return new Ghost(name.substring(prefix.length()), name,
                    token.slash() + 1 + token.partial().length());
            }
        }
        return null;
    }

    /** The prompt with {@code ghost} accepted, or null when it no longer fits the text. */
    public static Accepted accept(String text, int caret, Ghost ghost) {
        if (ghost == null) return null;
        Token token = locate(text, caret);
        if (token == null || token.slash() + 1 + token.partial().length() != ghost.insertPosition()
                || !Strings.CI.startsWith(ghost.fullCommand(), token.partial())
                || ghost.fullCommand().length() <= token.partial().length()) {
            return null;
        }
        String head = text.substring(0, token.slash()) + "/" + ghost.fullCommand() + " ";
        String tail = text.substring(token.slash() + 1 + token.partial().length());
        return new Accepted(head + tail, head.length());
    }
}
