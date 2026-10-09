package com.claudecode.permissions;

import org.apache.commons.lang3.StringUtils;
import java.util.Optional;

/**
 * A suggested "always allow" permission rule derived from a tool invocation.
 */
public record CommandSuggestion(
    String toolName,
    String ruleContent,  // e.g. "git:*" or "npm run:*" (prefix) or "git status" (exact)
    String label         // short human-readable, e.g. "git commands" or "\"git status\""
) {

    /**
     * Generates a suggestion for a Bash command: {@code "git add ."} → {@code "git:*"}.
     *
     * <p>Empty when {@link BashCommandPrefix} cannot read a command word off the command.
     * The caller is expected to drop the persistent-rule option entirely in that case
     * rather than offer a rule derived from a guess.
     */
    public static Optional<CommandSuggestion> forBash(String command, String cwd) {
        return BashCommandPrefix.resolve(command).map(resolved -> {
            String word = resolved.word();
            String cwdBase = baseName(cwd);
            return resolved.wholeCommand()
                ? new CommandSuggestion("Bash", word, "\"" + word + "\" in " + cwdBase)
                : new CommandSuggestion("Bash", word + ":*", word + " commands in " + cwdBase);
        });
    }

    private static String baseName(String path) {
        if (StringUtils.isBlank(path)) return ".";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }
}
