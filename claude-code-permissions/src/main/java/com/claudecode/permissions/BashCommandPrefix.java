package com.claudecode.permissions;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Resolves the command word that a Bash "don't ask again" rule is written for.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code utils/bash/parser.ts} — {@code findCommandNode} / {@code extractEnvVars} /
 *       {@code extractCommandArguments}: the command word is the first {@code command_name}
 *       in the script, and {@code variable_assignment} nodes are collected separately
 *       instead of being mistaken for it.</li>
 *   <li>{@code utils/bash/prefix.ts} — {@code getCommandPrefixStatic}: yields no prefix at
 *       all when no command node resolves, which is what suppresses the dialog's
 *       persistent-rule row rather than offering a rule nobody can verify.</li>
 * </ul>
 *
 * <p>Deviation from the released client, deliberate: 2.1.236 runs a real tree-sitter-bash
 * parse, so it can descend into a loop body and name the command inside it. (2.1.197 also
 * had a Haiku pre-check layered on top; the policy-spec prompt and its
 * {@code command_injection_detected} sentinel are absent from the 2.1.236 bundle, so that
 * layer is gone and the static parse is the whole story.) This is a lexical approximation
 * with no grammar behind it, so instead of guessing at structure it declines: control
 * keywords, command substitution, newlines and interpreter invocations all resolve to
 * {@link Optional#empty()}. Declining costs the user a shortcut, whereas guessing writes a
 * persistent allow rule whose text they were shown but whose scope they never agreed to.
 */
final class BashCommandPrefix {

    /** Matches a leading {@code NAME=} or {@code NAME+=} assignment, as the original does. */
    private static final Pattern ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\+?=");

    /**
     * A bare command word: a name or a path to one. Anything carrying quoting, expansion or
     * an option leader is not a word we can key a rule on.
     */
    private static final Pattern COMMAND_WORD =
        Pattern.compile("^[A-Za-z0-9_./][A-Za-z0-9_./+@-]*$");

    /**
     * Shell structure whose operand is a loop variable, a pattern or a block rather than a
     * command. Resolving past it needs the parse we do not have.
     */
    private static final Set<String> CONTROL_KEYWORDS = Set.of(
        "for", "while", "until", "select", "case", "esac", "if", "then", "elif", "else",
        "fi", "do", "done", "function", "in", "coproc", "time", "{", "}", "!", "[[");

    /** Separators between the simple commands of a list; the first segment carries the word. */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("&&|\\|\\||[;|&]");

    private BashCommandPrefix() {}

    /**
     * The resolved command word, plus whether it is the entire command. A command that is
     * nothing but its own word gets an exact rule; anything with arguments gets a prefix.
     */
    record Resolved(String word, boolean wholeCommand) {}

    static Optional<Resolved> resolve(String command) {
        if (StringUtils.isBlank(command)) return Optional.empty();
        // Substitution decides at run time what actually executes, so a rule keyed on the
        // literal text would be allowing something nobody can read off the dialog.
        if (Strings.CS.containsAny(command, "$(", "${", "`")) return Optional.empty();
        // A newline means a script, not a command list, and the word that matters may sit
        // inside a block this class cannot see into.
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return Optional.empty();

        String stripped = stripRedirections(command);
        if (stripped.isEmpty()) return Optional.empty();

        String segment = SEGMENT_SEPARATOR.split(stripped, 2)[0].strip();
        if (segment.isEmpty()) return Optional.empty();

        List<String> words = List.of(segment.split("\\s+"));
        String word = null;
        for (String candidate : words) {
            if (ASSIGNMENT.matcher(candidate).find()) continue;
            word = candidate;
            break;
        }
        if (word == null) return Optional.empty();
        if (CONTROL_KEYWORDS.contains(word)) return Optional.empty();
        if (!COMMAND_WORD.matcher(word).matches()) return Optional.empty();
        // An interpreter prefix allows arbitrary code under a rule that reads like one
        // command. PermissionGate already owns that judgement for rules in settings.
        if (PermissionGate.isDangerousBashRule("Bash", word + ":*")) return Optional.empty();

        return Optional.of(new Resolved(word, Strings.CS.equals(stripped, word)));
    }

    /**
     * Drops redirections so a trailing {@code > out.txt} cannot become the command word of a
     * command that is otherwise a bare name.
     */
    private static String stripRedirections(String command) {
        return command.replaceAll("\\s+[>]{1,2}\\s*\\S+", "")
                      .replaceAll("\\s+[<]{1,2}\\s*\\S+", "")
                      .strip();
    }
}
