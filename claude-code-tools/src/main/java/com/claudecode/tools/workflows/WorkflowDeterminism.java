package com.claudecode.tools.workflows;

import java.util.regex.Pattern;

/** Lexically checks only executable tokens, ignoring comments and string literals. */
final class WorkflowDeterminism {

    static final String ERROR = "Workflow scripts must be deterministic: "
        + "Date.now()/Math.random()/new Date() are unavailable (breaks resume). "
        + "Stamp results after the workflow returns, or pass timestamps via args.";

    private static final Pattern DATE_NOW = Pattern.compile("\\bDate\\s*\\.\\s*now\\s*\\(");
    private static final Pattern RANDOM = Pattern.compile("\\bMath\\s*\\.\\s*random\\s*\\(");
    private static final Pattern NEW_DATE = Pattern.compile("\\bnew\\s+Date\\s*\\(\\s*\\)");

    private WorkflowDeterminism() {}

    static void validate(String script) {
        String executable = maskStringsAndComments(script == null ? "" : script);
        if (DATE_NOW.matcher(executable).find()
                || RANDOM.matcher(executable).find()
                || NEW_DATE.matcher(executable).find()) {
            throw new WorkflowRuntimeException(ERROR);
        }
    }

    private static String maskStringsAndComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    out.append(next == '\n' ? '\n' : ' ');
                    if (next == '\\' && i < source.length()) {
                        char escaped = source.charAt(i++);
                        out.append(escaped == '\n' ? '\n' : ' ');
                    } else if (next == quote) {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                out.append("  ");
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    out.append(next == '\n' ? '\n' : ' ');
                    if (next == '*' && i < source.length() && source.charAt(i) == '/') {
                        out.append(' ');
                        i++;
                        break;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
