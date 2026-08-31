package com.claudecode.tools.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;

/**
 * Parses and simulates the small, safe subset of in-place sed substitutions used by
 * the permission UI.
 *
 * <ul>
 *   <li>recognizes
 *       one-file {@code sed -i} substitutions, backup suffixes, {@code -e}, BRE/ERE,
 *       and the established safe flag set.</li>
 *   <li>produces
 *       the exact proposed content that is previewed and later written after approval.</li>
 * </ul>
 */
public final class SedEditParser {
    private static final Pattern START = Pattern.compile("^\\s*sed\\s+");
    private static final Pattern VALID_FLAGS = Pattern.compile("^[gpimIM1-9]*$");
    private static final String BS = "\u0000BACKSLASH\u0000";
    private static final String PLUS = "\u0000PLUS\u0000";
    private static final String QUESTION = "\u0000QUESTION\u0000";
    private static final String PIPE = "\u0000PIPE\u0000";
    private static final String LPAREN = "\u0000LPAREN\u0000";
    private static final String RPAREN = "\u0000RPAREN\u0000";
    private static final String ESCAPED_AMP = "\u0000ESCAPED_AMP\u0000";

    private SedEditParser() {}

    public record SedEditInfo(String filePath, String pattern, String replacement,
                              String flags, boolean extendedRegex) {}

    public static SedEditInfo parse(String command) {
        if (command == null) return null;
        Matcher start = START.matcher(command.trim());
        if (!start.find()) return null;
        List<String> args = new ArrayList<>();
        for (ShellQuoteParse.Token token : ShellQuoteParse.parse(
                command.trim().substring(start.end()))) {
            if (token instanceof ShellQuoteParse.Glob) return null;
            if (token instanceof ShellQuoteParse.Word(String value)) args.add(value);
        }

        boolean inPlace = false;
        boolean extended = false;
        String expression = null;
        String filePath = null;
        int i = 0;
        while (i < args.size()) {
            String arg = args.get(i);
            if (Strings.CS.equalsAny(arg, "-i", "--in-place")) {
                inPlace = true;
                i++;
                if (i < args.size()) {
                    String next = args.get(i);
                    if (!Strings.CS.startsWith(next, "-")
                            && (next.isEmpty() || Strings.CS.startsWith(next, "."))) i++;
                }
                continue;
            }
            if (Strings.CS.startsWith(arg, "-i")) {
                inPlace = true;
                i++;
                continue;
            }
            if (Strings.CS.equalsAny(arg, "-E", "-r", "--regexp-extended")) {
                extended = true;
                i++;
                continue;
            }
            if (Strings.CS.equalsAny(arg, "-e", "--expression")) {
                if (expression != null || i + 1 >= args.size()) return null;
                expression = args.get(i + 1);
                i += 2;
                continue;
            }
            if (Strings.CS.startsWith(arg, "--expression=")) {
                if (expression != null) return null;
                expression = arg.substring("--expression=".length());
                i++;
                continue;
            }
            if (Strings.CS.startsWith(arg, "-")) return null;
            if (expression == null) expression = arg;
            else if (filePath == null) filePath = arg;
            else return null;
            i++;
        }
        if (!inPlace || expression == null || expression.isEmpty() || filePath == null) return null;
        if (!Strings.CS.startsWith(expression, "s/")) return null;

        String rest = expression.substring(2);
        StringBuilder pattern = new StringBuilder();
        StringBuilder replacement = new StringBuilder();
        StringBuilder flags = new StringBuilder();
        int state = 0;
        for (int j = 0; j < rest.length();) {
            char c = rest.charAt(j);
            if (c == '\\' && j + 1 < rest.length()) {
                target(state, pattern, replacement, flags).append(c).append(rest.charAt(j + 1));
                j += 2;
                continue;
            }
            if (c == '/') {
                if (state == 0) state = 1;
                else if (state == 1) state = 2;
                else return null;
                j++;
                continue;
            }
            target(state, pattern, replacement, flags).append(c);
            j++;
        }
        if (state != 2 || !VALID_FLAGS.matcher(flags).matches()) return null;
        return new SedEditInfo(filePath, pattern.toString(), replacement.toString(),
            flags.toString(), extended);
    }

    private static StringBuilder target(int state, StringBuilder pattern,
                                        StringBuilder replacement, StringBuilder flags) {
        return state == 0 ? pattern : state == 1 ? replacement : flags;
    }

    public static String apply(String content, SedEditInfo info) {
        if (content == null || info == null) return content;
        String regex = info.pattern().replace("\\/", "/");
        if (!info.extendedRegex()) {
            regex = regex.replace("\\\\", BS)
                .replace("\\+", PLUS).replace("\\?", QUESTION)
                .replace("\\|", PIPE).replace("\\(", LPAREN).replace("\\)", RPAREN)
                .replace("+", "\\+").replace("?", "\\?")
                .replace("|", "\\|").replace("(", "\\(").replace(")", "\\)")
                .replace(BS, "\\\\").replace(PLUS, "+").replace(QUESTION, "?")
                .replace(PIPE, "|").replace(LPAREN, "(").replace(RPAREN, ")");
        }
        int options = 0;
        if (Strings.CS.contains(info.flags(), "i") || Strings.CS.contains(info.flags(), "I")) {
            options |= Pattern.CASE_INSENSITIVE;
        }
        if (Strings.CS.contains(info.flags(), "m") || Strings.CS.contains(info.flags(), "M")) {
            options |= Pattern.MULTILINE;
        }
        try {
            Matcher matcher = Pattern.compile(regex, options).matcher(content);
            boolean global = Strings.CS.contains(info.flags(), "g");
            String replacement = javascriptReplacement(info.replacement());
            StringBuilder out = new StringBuilder(content.length());
            int cursor = 0;
            while (matcher.find()) {
                out.append(content, cursor, matcher.start());
                out.append(expandJavascriptReplacement(replacement, matcher, content));
                cursor = matcher.end();
                if (!global) break;
                if (matcher.start() == matcher.end() && cursor < content.length()) {
                    out.append(content.charAt(cursor++));
                }
            }
            if (cursor == 0) return content;
            out.append(content, cursor, content.length());
            return out.toString();
        } catch (RuntimeException _) {
            return content;
        }
    }

    private static String javascriptReplacement(String replacement) {
        return replacement.replace("\\/", "/")
            .replace("\\&", ESCAPED_AMP)
            .replace("&", "$&")
            .replace(ESCAPED_AMP, "&");
    }

    /** Expands replacement tokens for the full match, captures, and surrounding text. */
    private static String expandJavascriptReplacement(String replacement, Matcher matcher,
                                                      String content) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c != '$' || i + 1 >= replacement.length()) {
                out.append(c);
                continue;
            }
            char next = replacement.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i++;
            } else if (next == '&') {
                out.append(matcher.group());
                i++;
            } else if (next == '`') {
                out.append(content, 0, matcher.start());
                i++;
            } else if (next == '\'') {
                out.append(content, matcher.end(), content.length());
                i++;
            } else if (next >= '1' && next <= '9') {
                int first = next - '0';
                int group = first;
                int consumed = 1;
                if (i + 2 < replacement.length()
                        && Character.isDigit(replacement.charAt(i + 2))) {
                    int twoDigit = first * 10 + (replacement.charAt(i + 2) - '0');
                    if (twoDigit <= matcher.groupCount()) {
                        group = twoDigit;
                        consumed = 2;
                    }
                }
                if (group <= matcher.groupCount()) {
                    String value = matcher.group(group);
                    if (value != null) out.append(value);
                    i += consumed;
                } else {
                    out.append('$');
                }
            } else {
                out.append('$');
            }
        }
        return out.toString();
    }
}
