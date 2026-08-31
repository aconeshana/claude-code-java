package com.claudecode.tools.bash;


import com.claudecode.permissions.PermissionDecision;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Conservative, side-effect-free Bash security preflight.
 */
final class BashSecurity {

    private static final Pattern CONTROL_CHARS = Pattern.compile(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern UNICODE_WHITESPACE = Pattern.compile(
        "[\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]");
    private static final Pattern DANGEROUS_VARIABLE = Pattern.compile(
        "(?i)(?:^|[;|&\\s])(?:export\\s+)?(?:IFS|BASH_ENV|ENV|CDPATH|GLOBIGNORE|SHELLOPTS|BASHOPTS|PROMPT_COMMAND)\\s*=");
    private static final Pattern PROC_ENVIRON = Pattern.compile(
        "(?i)(?:/proc(?:/self)?|/proc/self)/environ");
    private static final Pattern FIND_SIDE_EFFECT = Pattern.compile(
        "(?is)(?:^|[;&|]\\s*)(?:command\\s+|builtin\\s+|env(?:\\s+[^;&|]+)?\\s+)?find\\b"
            + ".*?(?:^|\\s)-(?:delete|exec(?:dir)?|ok(?:dir)?|fprint0?|fls|fprintf)\\b");

    private BashSecurity() {}

    /** Returns an ASK decision when the command has a security concern, else {@code null}. */
    static PermissionDecision check(String command) {
        String concern = concern(command);
        return concern == null ? null : new PermissionDecision.Ask(null, null, concern, null, null);
    }

    /** Exposed package-locally for focused corpus tests without coupling tests to a record. */
    static String concern(String command) {
        if (StringUtils.isBlank(command)) return null;

        if (CONTROL_CHARS.matcher(command).find()) {
            return "Command contains non-printable control characters that could bypass security checks";
        }
        if (UNICODE_WHITESPACE.matcher(command).find()) {
            return "Command contains Unicode whitespace that could cause parsing inconsistencies";
        }
        if (PROC_ENVIRON.matcher(command).find()) {
            return "Command accesses /proc/environ, which can expose process secrets";
        }
        if (DANGEROUS_VARIABLE.matcher(command).find()
                || command.matches("(?s).*\\$IFS(?:\\W|$).*")
                || command.matches("(?s).*[<>|]\\s*\\$[A-Za-z_].*")
                || command.matches("(?s).*\\$[A-Za-z_][A-Za-z0-9_]*\\s*[|<>].*")) {
            return "Command uses a shell variable in a security-sensitive context";
        }

        if (startsWithIncompleteFragment(command)) {
            return "Command appears to be an incomplete shell fragment";
        }
        if (hasShellQuoteSingleQuoteBug(command)) {
            return "Command contains a single-quoted backslash pattern that can bypass security checks";
        }

        Scan scan = scan(command);
        if (scan.malformed) {
            return "Command could not be parsed safely; manual approval is required";
        }
        if (scan.unquotedCommandSubstitution) {
            return "Command contains command or process substitution";
        }
        if (scan.unquotedHeredoc) {
            return "Command contains a heredoc whose expansion cannot be validated safely";
        }
        if (scan.escapedOperatorOrWhitespace) {
            return "Command contains escaped shell operators or whitespace that can change tokenisation";
        }
        if (scan.braceExpansion) {
            return "Command contains brace expansion that can alter command arguments";
        }
        if (scan.midWordHash || scan.commentQuoteDesync || scan.quotedNewline) {
            return "Command contains comment/quote syntax that can desynchronise permission parsing";
        }
        if (scan.carriageReturn) {
            return "Command contains carriage return which shell tokenizers interpret differently";
        }
        if (hasDangerousJqPattern(command)) {
            return "Command contains jq code or flags that can execute/read arbitrary content";
        }
        if (FIND_SIDE_EFFECT.matcher(command).find()) {
            return "Command uses a find action that can execute commands or modify files";
        }
        if (hasObfuscatedFlags(command)) {
            return "Command contains quoted or special shell syntax that can hide a flag";
        }
        if (hasZshDangerousCommand(command)) {
            return "Command uses a Zsh-specific operation that can bypass security checks";
        }
        if (scan.malformedTokenInjection) {
            return "Command contains ambiguous shell tokens next to a command separator";
        }
// Ordinary redirections are deliberately left to BashPermissions' path extractor.
        return null;
    }

    private static Scan scan(String command) {
        boolean single = false;
        boolean dbl = false;
        boolean escaped = false;
        boolean malformed = false;
        boolean substitution = false;
        boolean heredoc = false;
        boolean escapedOperator = false;
        boolean braceExpansion = false;
        boolean midWordHash = false;
        boolean commentQuoteDesync = false;
        boolean quotedNewline = false;
        boolean carriageReturn = false;
        boolean malformedTokenInjection = false;
        boolean inputRedirect = false;
        boolean outputRedirect = false;
        int braceDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        char quote = 0;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                if (!single && (isShellOperator(c) || Character.isWhitespace(c))) {
                    escapedOperator = true;
                }
                escaped = false;
                continue;
            }
            if (!single && c == '\\') {
                escaped = true;
                continue;
            }
            if (single) {
                if (c == '\'') {
                    single = false;
                    quote = 0;
                }
                if (c == '\r') carriageReturn = true;
                continue;
            }
            if (dbl) {
                if (c == '"') {
                    dbl = false;
                    quote = 0;
                }
                // Double quotes still expand $, ${}, and $[] in Bash. The
                // the compatibility contract validator intentionally checks those expansions after
                // quote extraction, so do the same before consuming the
                // quoted character here.
                if (c == '$' && i + 1 < command.length()
                        && "([{ ".indexOf(command.charAt(i + 1)) >= 0) {
                    substitution = true;
                }
                if (c == '\n' || c == '\r') quotedNewline = true;
                continue;
            }
            if (c == '\'') {
                single = true;
                quote = c;
                continue;
            }
            if (c == '"') {
                dbl = true;
                quote = c;
                continue;
            }

            if (c == '`') {
                substitution = true;
            }

            if (c == '$' && i + 1 < command.length()) {
                char next = command.charAt(i + 1);
                if (next == '(' || next == '{' || next == '[') substitution = true;
            }
            if ((c == '<' || c == '>') && i + 1 < command.length()) {
                char next = command.charAt(i + 1);
                if (next == '(') substitution = true;
                if (c == '<' && (next == '<' || next == '&')) heredoc = next == '<';
            }
            if (c == '<') inputRedirect = true;
            if (c == '>') outputRedirect = true;

            if (c == '{') braceDepth++;
            if (c == '}' && braceDepth > 0) braceDepth--;
            if (braceDepth > 0 && (c == ',' || (c == '.' && i + 1 < command.length()
                    && command.charAt(i + 1) == '.'))) {
                braceExpansion = true;
            }
            if (c == '(') parenDepth++;
            if (c == ')' && parenDepth > 0) parenDepth--;
            if (c == '[') bracketDepth++;
            if (c == ']' && bracketDepth > 0) bracketDepth--;

            if (c == '#') {
                int previous = i - 1;
                if (previous >= 0 && !Character.isWhitespace(command.charAt(previous))) {
                    midWordHash = true;
                }
                int lineEnd = command.indexOf('\n', i + 1);
                if (lineEnd < 0) lineEnd = command.length();
                String comment = command.substring(i + 1, lineEnd);
                if (comment.indexOf('\'') >= 0 || comment.indexOf('"') >= 0) {
                    commentQuoteDesync = true;
                }
            }
            // Quoted newlines are already flagged by the single/double-quote
            // branches above (both continue before reaching this point), and an
            // unquoted newline is not a quoted newline — there is deliberately
            // no bare-LF quotedNewline check here. The stripCommentLines
            // differential is caught by the post-loop pattern below.
            if (c == '\r') carriageReturn = true;
        }

        if (escaped || single || dbl || quote != 0 || braceDepth != 0
                || parenDepth != 0 || bracketDepth != 0) {
            malformed = true;
        }
        // A #-prefixed line following a quoted newline is the specific the compatibility contract
        // stripCommentLines differential. Keep this separate from ordinary LF.
        if (command.indexOf('\n') >= 0 && command.matches("(?s).*['\"][^'\"]*\\n\\s*#.*")) {
            quotedNewline = true;
        }
        if (command.matches("(?s).*\\{[^{}]*['\"][^'\"]*[;|&][^'\"]*['\"][^{}]*}.*")) {
            malformedTokenInjection = true;
        }
        return new Scan(malformed, substitution, heredoc, escapedOperator,
            braceExpansion, midWordHash, commentQuoteDesync, quotedNewline,
            carriageReturn, malformedTokenInjection, inputRedirect, outputRedirect);
    }

    private static boolean startsWithIncompleteFragment(String command) {
        String trimmed = command.stripLeading();
        return Strings.CS.startsWith( command, "\t") ||Strings.CS.startsWith( trimmed, "-")
            || trimmed.matches("^(?:&&|\\|\\||;|>>?|<).*");
    }

/** matches shellQuote.hasShellQuoteSingleQuoteBug without invoking a shell. */
    private static boolean hasShellQuoteSingleQuoteBug(String command) {
        boolean single = false;
        boolean dbl = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && !single) { escaped = true; continue; }
            if (c == '"' && !single) { dbl = !dbl; continue; }
            if (c != '\'' || dbl) continue;
            boolean closing = single;
            single = !single;
            if (!closing) continue;
            int count = 0;
            for (int j = i - 1; j >= 0 && command.charAt(j) == '\\'; j--) count++;
            if (count > 0 && (count % 2 == 1 || command.indexOf('\'', i + 1) >= 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDangerousJqPattern(String command) {
        String trimmed = command.stripLeading();
        if (!trimmed.matches("(?i)^jq(?:\\s|$).*")) return false;
        return command.matches("(?s).*\\bsystem\\s*\\(.*")
            || command.matches("(?s).*(?:^|\\s)(?:-f|--from-file|--rawfile|--slurpfile|-L|--library-path)(?:=|\\s|$).*");
    }

    private static boolean hasZshDangerousCommand(String command) {
        String[] dangerous = {"zmodload", "emulate", "sysopen", "sysread", "syswrite",
            "sysseek", "zpty", "ztcp", "zsocket", "mapfile", "zf_rm", "zf_mv",
            "zf_ln", "zf_chmod", "zf_chown", "zf_mkdir", "zf_rmdir", "zf_chgrp"};
        for (String segment : command.split("[;&|\\n]+")) {
            String token = segment.stripLeading();
            while (token.matches("(?:command|builtin|noglob|nocorrect)\\s+.*")) {
                token = token.replaceFirst("^(?:command|builtin|noglob|nocorrect)\\s+", "");
            }
            token = token.replaceFirst("^[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+", "");
            String base = token.split("\\s+", 2)[0];
            for (String candidate : dangerous) if (Strings.CS.equals(candidate, base)) return true;
            if (Strings.CS.equals("fc", base) && token.matches("(?s).*\\s-\\S*e.*")) return true;
        }
        return false;
    }

    private static boolean hasObfuscatedFlags(String command) {
        return command.matches("(?s).*\\$'[^']*'.*")
            || command.matches("(?s).*\\$\"[^\"]*\".*")
            || command.matches("(?s).*(?:^|\\s)(?:''|\"\")+\\s*-.*")
            || command.matches("(?s).*(?:\"\"|''|\"''|''\")+[\"']-.*")
            || command.matches("(?s)(?:^|\\s)[\"']{3,}.*");
    }

    private static boolean isShellOperator(char c) {
        return "|&;()<>".indexOf(c) >= 0;
    }

    private record Scan(boolean malformed, boolean unquotedCommandSubstitution,
                        boolean unquotedHeredoc, boolean escapedOperatorOrWhitespace,
                        boolean braceExpansion, boolean midWordHash,
                        boolean commentQuoteDesync, boolean quotedNewline,
                        boolean carriageReturn, boolean malformedTokenInjection,
                        boolean unquotedInputRedirect, boolean unquotedOutputRedirect) {}
}
