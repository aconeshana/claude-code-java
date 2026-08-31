package com.claudecode.commands.parsing;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Optional;

/**
 * Stateless utility for parsing raw slash-command input strings into structured {@link
 * ParsedSlashCommand} values.
 */
public final class SlashCommandParser {

    private SlashCommandParser() {}

    /**
     * Parse a raw slash-command input string.
     *
     * @param rawInput the raw string as typed by the user, e.g. {@code "/search foo bar"} or
     *     {@code "/mcp:tool (MCP) arg1"}
     * @return an {@link Optional} containing the parsed command, or {@code Optional.empty} for
     *     null/blank/non-slash/bare-slash input
     */
    public static Optional<ParsedSlashCommand> parse(String rawInput) {
        if (StringUtils.isBlank(rawInput)) {
            return Optional.empty();
        }

        String trimmed = rawInput.trim();

        if (!Strings.CS.startsWith(trimmed, "/")) {
            return Optional.empty();
        }

        // Strip the leading '/'
        String withoutSlash = trimmed.substring(1);


        String[] words = withoutSlash.split(" ");

        // Bare "/" — first token is empty string
        if (words[0].isEmpty()) {
            return Optional.empty();
        }

        String commandName = words[0];
        boolean isMcp = false;
        int argsStartIndex = 1;

        // MCP detection: second token is literally "(MCP)"
        if (words.length > 1 && Strings.CS.equals("(MCP)", words[1])) {
            commandName = commandName + " (MCP)";
            isMcp = true;
            argsStartIndex = 2;
        }

        // Collect remaining words as the args string
        String args = buildArgs(words, argsStartIndex);

        return Optional.of(new ParsedSlashCommand(commandName, args, isMcp));
    }

    /** Join tokens from {@code startIndex} (inclusive) with a single space. */
    private static String buildArgs(String[] words, int startIndex) {
        if (startIndex >= words.length) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < words.length; i++) {
            if (i > startIndex) {
                sb.append(' ');
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }


    public static boolean looksLikeCommand(String commandName) {
        if (StringUtils.isEmpty(commandName)) return false;
        return commandName.matches("[a-zA-Z0-9:\\-_]+");
    }
}
