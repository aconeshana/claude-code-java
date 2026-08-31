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
     * Generate a suggestion for a Bash command.
     * Extracts the first word as a prefix pattern: "git add ." → ruleContent="git:*".
     */
    public static Optional<CommandSuggestion> forBash(String command, String cwd) {
        if (StringUtils.isBlank(command)) return Optional.empty();
        String stripped = stripRedirections(command).strip();
        if (stripped.isEmpty()) return Optional.empty();
        String[] tokens = stripped.split("\\s+", 2);
        String firstWord = tokens[0];
        if (firstWord.isEmpty()) return Optional.empty();

        String cwdBase = baseName(cwd);
        if (tokens.length == 1) {
            // Single-word command → exact match
            return Optional.of(new CommandSuggestion(
                "Bash", firstWord,
                "\"" + firstWord + "\" in " + cwdBase));
        }
        // Multi-word → prefix pattern
        return Optional.of(new CommandSuggestion(
            "Bash", firstWord + ":*",
            firstWord + " commands in " + cwdBase));
    }

    private static String stripRedirections(String cmd) {
        return cmd.replaceAll("\\s+[>]{1,2}\\s*\\S+", "")
                  .replaceAll("\\s+[<]{1,2}\\s*\\S+", "")
                  .trim();
    }

    private static String baseName(String path) {
        if (StringUtils.isBlank(path)) return ".";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }
}
