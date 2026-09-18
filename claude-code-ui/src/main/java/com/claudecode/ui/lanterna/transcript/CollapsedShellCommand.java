package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.text.FormatUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Decides whether one shell tool call folds into the collapsed read/search group, and formats the
 * {@code ⎿} hint row that describes it.
 *
 * <ul>
 *   <li>Covers the 2.1.236 bundle's {@code oCT} — bash's {@code isSearchOrReadCommand}: every
 *       command segment's head word must be a search ({@code Q0T}), read ({@code eCT}), or list
 *       ({@code tCT}) builtin, with {@code rCT} words passed over as neutral; a single segment
 *       outside those sets demotes the whole command to a plain shell command.</li>
 *   <li>Covers {@code EMv} — the same rule for PowerShell, whose head words are case-folded, which
 *       has no list category, and which splits on {@code ;} and {@code |} only.</li>
 *   <li>Covers {@code Zk} — the segment split. 236 parses the command with tree-sitter-bash and
 *       walks the tree, flattening {@code wpa = {program, list, pipeline}}, skipping the
 *       {@code oVd} separators and comments, and pushing each leaf command's text. This port scans
 *       the source instead, honouring quotes, {@code $(…)}, {@code ${…}} and backticks; see the
 *       deviations below. {@code V4e = 10000} caps the input either way.</li>
 *   <li>Covers {@code KDa}, {@code koi}, {@code SlS}, {@code vlS} and {@code XDa} — the display
 *       hint: a comment-only command shows its {@code #} title, anything else shows its lines
 *       flattened and joined under a {@code "$ "} prefix, truncated at {@code $Bp = 300}
 *       characters.</li>
 * </ul>
 *
 * <p>Deviations from {@code Zk}, both of which fail towards "plain shell command" rather than
 * towards a read/search claim: a here-document's body is scanned as if it were commands, and a
 * redirection target is left attached to its segment instead of being pruned.
 */
final class CollapsedShellCommand {

    /** 236's {@code V4e}: past this length the command is not parsed at all. */
    private static final int MAX_PARSED_LENGTH = 10_000;
    /** 236's {@code $Bp}: the display hint's character budget. */
    private static final int HINT_MAX_CHARS = 300;

    private static final Set<String> BASH_SEARCH =
        Set.of("find", "grep", "rg", "ag", "ack", "locate", "which", "whereis");
    private static final Set<String> BASH_READ = Set.of("cat", "head", "tail", "less", "more",
        "wc", "stat", "file", "strings", "jq", "awk", "cut", "sort", "uniq", "tr");
    private static final Set<String> BASH_LIST = Set.of("ls", "tree", "du");
    private static final Set<String> BASH_NEUTRAL = Set.of("echo", "printf", "true", "false", ":");

    private static final Set<String> PS_SEARCH =
        Set.of("select-string", "get-childitem", "findstr", "where.exe");
    private static final Set<String> PS_READ = Set.of("get-content", "get-item", "test-path",
        "resolve-path", "get-process", "get-service", "get-childitem", "get-location",
        "get-filehash", "get-acl", "format-hex");
    private static final Set<String> PS_NEUTRAL = Set.of("write-output", "write-host");

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
        boolean powershell = Strings.CS.equals("PowerShell", toolName);
        List<String> segments = powershell ? powershellSegments(command) : bashSegments(command);
        if (segments.isEmpty()) return Kind.NONE;

        boolean search = false;
        boolean read = false;
        boolean list = false;
        boolean sawCommand = false;
        for (String segment : segments) {
            String head = headToken(segment);
            if (head.isEmpty()) continue;
            // Only PowerShell case-folds its head word; bash's sets are matched verbatim, so
            // "GREP" is a plain shell command there.
            String name = powershell ? head.toLowerCase(Locale.ROOT) : head;
            if ((powershell ? PS_NEUTRAL : BASH_NEUTRAL).contains(name)) continue;
            sawCommand = true;
            boolean isSearch = (powershell ? PS_SEARCH : BASH_SEARCH).contains(name);
            boolean isRead = (powershell ? PS_READ : BASH_READ).contains(name);
            boolean isList = !powershell && BASH_LIST.contains(name);
            if (!isSearch && !isRead && !isList) return Kind.NONE;
            search |= isSearch;
            read |= isRead;
            list |= isList;
        }
        return sawCommand ? new Kind(search, read, list) : Kind.NONE;
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
        if (!head.startsWith("#") || head.startsWith("#!")) return null;
        if (newline >= 0 && hasNonCommentLine(command.substring(newline + 1))) return null;
        String title = head.replaceFirst("^#+\\s*", "");
        return title.isEmpty() || hasControlCharacter(title) ? null : title;
    }

    /** 236's {@code SlS}. */
    private static boolean hasNonCommentLine(String rest) {
        for (String line : rest.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
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

    /** 236's {@code EMv} split: on {@code ;} or {@code |} with surrounding whitespace, blanks dropped. */
    private static List<String> powershellSegments(String command) {
        String trimmed = command.strip();
        if (trimmed.isEmpty()) return List.of();
        List<String> segments = new ArrayList<>();
        for (String segment : trimmed.split("\\s*[;|]\\s*")) {
            if (!segment.isEmpty()) segments.add(segment);
        }
        return segments;
    }

    /**
     * The {@code Zk} split, scanned rather than parsed: top-level {@code oVd} separators
     * ({@code && || | ; & |& newline}) end a segment, comments are dropped, and separators inside
     * quotes, {@code $(…)}, {@code ${…}} or backticks are ordinary characters.
     */
    private static List<String> bashSegments(String command) {
        if (command.length() > MAX_PARSED_LENGTH) return List.of(command);
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        boolean backtick = false;
        for (int index = 0; index < command.length(); index++) {
            char ch = command.charAt(index);
            if (quote != 0) {
                if (ch == '\\' && quote == '"' && index + 1 < command.length()) {
                    current.append(ch).append(command.charAt(++index));
                    continue;
                }
                if (ch == quote) quote = 0;
                current.append(ch);
                continue;
            }
            switch (ch) {
                case '\\' -> {
                    current.append(ch);
                    if (index + 1 < command.length()) current.append(command.charAt(++index));
                    continue;
                }
                case '\'', '"' -> {
                    quote = ch;
                    current.append(ch);
                    continue;
                }
                case '`' -> {
                    backtick = !backtick;
                    current.append(ch);
                    continue;
                }
                default -> { }
            }
            if (ch == '$' && index + 1 < command.length()
                    && (command.charAt(index + 1) == '(' || command.charAt(index + 1) == '{')) {
                depth++;
                current.append(ch).append(command.charAt(++index));
                continue;
            }
            if (depth > 0) {
                if (ch == '(' || ch == '{') depth++;
                if (ch == ')' || ch == '}') depth--;
                current.append(ch);
                continue;
            }
            if (backtick) {
                current.append(ch);
                continue;
            }
            if (ch == '#' && startsWord(current)) {
                int lineEnd = command.indexOf('\n', index);
                if (lineEnd < 0) break;
                index = lineEnd - 1;
                continue;
            }
            if (ch == ';' || ch == '\n' || ch == '&' || ch == '|') {
                flush(segments, current);
                // "&&", "||" and "|&" are one separator, not two.
                if (index + 1 < command.length()) {
                    char next = command.charAt(index + 1);
                    if ((ch == '&' && next == '&') || (ch == '|' && (next == '|' || next == '&'))) {
                        index++;
                    }
                }
                continue;
            }
            current.append(ch);
        }
        flush(segments, current);
        return segments;
    }

    /** A {@code #} only opens a comment at the start of a word. */
    private static boolean startsWord(StringBuilder current) {
        if (current.isEmpty()) return true;
        char previous = current.charAt(current.length() - 1);
        return previous == ' ' || previous == '\t';
    }

    private static void flush(List<String> segments, StringBuilder current) {
        String segment = current.toString().strip();
        current.setLength(0);
        if (!segment.isEmpty()) segments.add(segment);
    }

    private static String headToken(String segment) {
        String trimmed = segment.strip();
        if (trimmed.isEmpty()) return "";
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) end++;
        return trimmed.substring(0, end);
    }
}
