package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.regex.Pattern;

/**
 * Pure helpers for building MCP-derived identifiers in a way that survives arbitrary server /
 * prompt names.
 */
public final class McpNameNormalizer {

    private McpNameNormalizer() {}

    private static final String CLAUDE_AI_PREFIX = "claude.ai ";

    private static final Pattern VALID_SERVER_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");


    static boolean isValidServerName(String name) {
        return name != null && VALID_SERVER_NAME.matcher(name).matches();
    }

    /**
     * Returns a human-readable reason why {@code name} is invalid, or
     * {@code null} when it's valid. Callers use this to build user-facing
     * error messages without duplicating the character-class description.
     */
    static String invalidNameReason(String name) {
        if (StringUtils.isEmpty(name)) return "server name must not be empty";
        if (!isValidServerName(name)) {
            return "invalid server name '" + name + "' — only letters, digits, "
                + "hyphens (-), and underscores (_) are allowed";
        }
        return null;
    }

    /**
     * Normalises a single MCP identifier segment (server name or prompt name).
     * Every character outside {@code [A-Za-z0-9_-]} becomes {@code _}. When
     * {@code name} starts with the {@code "claude.ai "} prefix (their hosted-
     * agent naming), also collapse consecutive underscores and strip
     * leading/trailing underscores — otherwise the double-underscore delimiter
     * used in {@link #mcpCommandName} would be corrupted.
     */
    public static String normalize(String name) {
        if (StringUtils.isEmpty(name)) return "";
        boolean isClaudeAi = Strings.CS.startsWith(name, CLAUDE_AI_PREFIX);

        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
            sb.append(safe ? c : '_');
        }
        String result = sb.toString();
        if (isClaudeAi) {
            result = result.replaceAll("_+", "_");
            int start = 0, end = result.length();
            while (start < end && result.charAt(start) == '_') start++;
            while (end > start && result.charAt(end - 1) == '_') end--;
            result = result.substring(start, end);
        }
        return result;
    }


    static String mcpCommandName(String serverName, String promptOrToolName) {
        return "mcp__" + normalize(serverName) + "__" + normalize(promptOrToolName);
    }
}
