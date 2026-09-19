package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.bash.ShellQuoteParse;
import com.claudecode.tools.powershell.PowerShellTool;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Decides whether one shell tool call folds into the collapsed read/search group, and formats the
 * {@code ⎿} hint row that describes it.
 *
 * <ul>
 *   <li>The classification itself is <b>not</b> implemented here. The 2.1.236 bundle's {@code oCT}
 *       (bash's {@code isSearchOrReadCommand}, with its {@code Q0T}/{@code eCT}/{@code tCT} word
 *       sets and {@code rCT} neutrals) is covered by
 *       {@link BashTool#classifySearchOrReadCommand}, and {@code EMv} (the PowerShell rule, which
 *       case-folds its head word and has no list category) by
 *       {@link PowerShellTool#classifySearchOrReadCommand}. This class delegates to both so the
 *       transcript collapser and the teammate task board can never disagree about what a command
 *       is — they did, for PowerShell aliases, while this class kept its own copy of the word sets
 *       and never resolved {@code gci}/{@code sls}/{@code dir} to their canonical cmdlets.</li>
 *   <li>{@code Zk} — the segment split — is likewise covered by {@code BashTool}'s
 *       {@code segmentLeadingCommands}, which is built on the shared {@link ShellQuoteParse}
 *       tokenizer. {@code V4e = 10000} is still applied here, because it gates whether the command
 *       is handed to that segmenter at all.</li>
 *   <li>Covers {@code KDa}, {@code koi}, {@code SlS}, {@code vlS} and {@code XDa} — the display
 *       hint: a comment-only command shows its {@code #} title, anything else shows its lines
 *       flattened and joined under a {@code "$ "} prefix, truncated at {@code $Bp = 300}
 *       characters. This has no counterpart in the tool classes and stays here.</li>
 * </ul>
 *
 * <p>Delegating removed the two {@code Zk} deviations this class used to document — a
 * here-document's body being scanned as commands, and a redirection target staying attached to its
 * segment. {@link ShellQuoteParse} emits redirections as {@code Op} tokens that the segmenter drops
 * outright, so a redirection target is now pruned as 236 prunes it.
 *
 * <p>One deviation remains, and it is inherited rather than chosen: {@code ShellQuoteParse} ends a
 * {@code #} comment at the end of the <em>input</em> rather than the end of the line, so everything
 * after a comment is swallowed with it. {@link #stripFullLineComments} compensates for the common
 * shape — a command whose leading lines are comments — before delegating. A comment that trails a
 * real command on the same line still hides the lines below it, which fails towards "plain shell
 * command" rather than towards a read/search claim.
 */
final class CollapsedShellCommand {

    /** 236's {@code V4e}: past this length the command is not parsed at all. */
    private static final int MAX_PARSED_LENGTH = 10_000;
    /** 236's {@code $Bp}: the display hint's character budget. */
    private static final int HINT_MAX_CHARS = 300;

    private CollapsedShellCommand() {}

    /** 236's {@code gpe}: the two tools whose input is a shell command. */
    static boolean isShellTool(String toolName) {
        return Strings.CS.equals("Bash", toolName) || Strings.CS.equals("PowerShell", toolName);
    }

    /**
     * 236's {@code isSearchOrReadCommand} result. A command that satisfies none of the three still
     * folds into the group — as a shell command rather than as a read, search, or list.
     */
    record Kind(boolean search, boolean read, boolean list) {
        static final Kind NONE = new Kind(false, false, false);

        boolean readSearchLike() {
            return search || read || list;
        }
    }

    static Kind classify(String toolName, String command) {
        if (StringUtils.isBlank(command)) return Kind.NONE;
        if (Strings.CS.equals("PowerShell", toolName)) {
            // PowerShell has no list category, so its list-like cmdlets report as search + read.
            PowerShellTool.SearchReadClassification ps =
                PowerShellTool.classifySearchOrReadCommand(command);
            return new Kind(ps.isSearch(), ps.isRead(), false);
        }
        // Past V4e, 236 skips the parse and judges the command as a single unsegmented unit, which
        // is its head word — so hand the segmenter only that word rather than the whole string.
        String subject = command.length() > MAX_PARSED_LENGTH
            ? headToken(command) : stripFullLineComments(command);
        if (StringUtils.isBlank(subject)) return Kind.NONE;
        BashTool.SearchReadClassification bash = BashTool.classifySearchOrReadCommand(subject);
        return new Kind(bash.isSearch(), bash.isRead(), bash.isList());
    }

    /**
     * Drops whole lines that are nothing but a {@code #} comment, so the shared tokenizer's
     * end-of-input comment handling cannot swallow the commands that follow them. Comments that
     * trail real code on a line are left alone: removing them would mean re-implementing the quote
     * tracking this class delegates precisely to avoid.
     */
    private static String stripFullLineComments(String command) {
        if (command.indexOf('\n') < 0) return command;
        StringBuilder kept = new StringBuilder();
        for (String line : command.split("\n", -1)) {
            if (Strings.CS.startsWith(line.strip(), "#")) continue;
            if (!kept.isEmpty()) kept.append('\n');
            kept.append(line);
        }
        return kept.toString();
    }

    /**
     * 236's {@code koi(f) !== undefined ? XDa(koi(f)) : KDa(f)} — the hint the {@code isBash}
     * branch stores, or {@code null} when the command is blank.
     */
    static String displayHint(String command) {
        if (StringUtils.isBlank(command)) return null;
        String title = commentTitle(command);
        return truncateHint(title != null ? title : shellPreview(command));
    }

    /**
     * 236's {@code KDa} — the hint the {@code isList} and read branches store for a shell command.
     * Unlike {@link #displayHint} they do not consult {@code koi}, so a comment-only {@code ls}
     * still shows its {@code "$ "} form.
     */
    static String preview(String command) {
        return StringUtils.isBlank(command) ? null : truncateHint(shellPreview(command));
    }

    /**
     * 236's {@code KDa} minus its {@code XDa} tail: each line stripped of ANSI and collapsed to
     * single spaces, blank lines dropped, the rest joined by newlines under a {@code "$ "} prefix.
     */
    private static String shellPreview(String command) {
        StringBuilder preview = new StringBuilder("$ ");
        boolean first = true;
        for (String line : command.split("\n", -1)) {
            String flattened = FormatUtils.flattenToSingleLine(line);
            if (flattened.isEmpty()) continue;
            if (!first) preview.append('\n');
            preview.append(flattened);
            first = false;
        }
        return preview.toString();
    }

    /**
     * 236's {@code koi}: a command whose first line is a {@code #} comment and whose remaining
     * lines are comments or blank shows that comment as its title. {@code #!} shebangs, empty
     * titles, and titles carrying control characters ({@code vlS}) are rejected.
     */
    private static String commentTitle(String command) {
        int newline = command.indexOf('\n');
        String head = (newline < 0 ? command : command.substring(0, newline)).strip();
        if (!Strings.CS.startsWith(head, "#") || Strings.CS.startsWith(head, "#!")) return null;
        if (newline >= 0 && hasNonCommentLine(command.substring(newline + 1))) return null;
        String title = head.replaceFirst("^#+\\s*", "");
        return title.isEmpty() || hasControlCharacter(title) ? null : title;
    }

    /** 236's {@code SlS}. */
    private static boolean hasNonCommentLine(String rest) {
        for (String line : rest.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || Strings.CS.startsWith(trimmed, "#")) continue;
            return true;
        }
        return false;
    }

    /** 236's {@code vlS}. */
    private static boolean hasControlCharacter(String value) {
        return value.chars().anyMatch(ch -> ch < 32 || (ch >= 127 && ch <= 159));
    }

    /** 236's {@code XDa}, which truncates by character count rather than by terminal columns. */
    private static String truncateHint(String hint) {
        return hint.length() <= HINT_MAX_CHARS
            ? hint : hint.substring(0, HINT_MAX_CHARS - 1) + "…";
    }

    private static String headToken(String segment) {
        String trimmed = segment.strip();
        if (trimmed.isEmpty()) return "";
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) end++;
        return trimmed.substring(0, end);
    }
}
