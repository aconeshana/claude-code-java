package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.text.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates the <em>content</em> of a single permission rule string ({@code "Bash(git *)"}, {@code
 * "mcp__server__tool"},...) — the pre-filter that runs before {@link SettingsSchema} so one bad
 * rule doesn't poison the whole settings file.
 */
final class PermissionRuleValidation {

    private PermissionRuleValidation() {}


    private static final List<String> FILE_PATTERN_TOOLS =
        List.of("Read", "Write", "Edit", "Glob", "NotebookRead", "NotebookEdit");


    private static final List<String> BASH_PREFIX_TOOLS = List.of("Bash");


    private static final Pattern WILDCARD_BOUNDARY =
        Pattern.compile("^\\*|\\*$|\\*\\*|/\\*|\\*\\.|\\*\\)");


    record Result(boolean valid, String error, String suggestion) {
        static final Result VALID = new Result(true, null, null);

        static Result invalid(String error) {
            return new Result(false, error, null);
        }

        static Result invalid(String error, String suggestion) {
            return new Result(false, error, suggestion);
        }
    }


    static Result validatePermissionRule(String rule) {
        if (rule == null || rule.trim().isEmpty()) {
            return Result.invalid("Permission rule cannot be empty");
        }

        int openCount = countUnescapedChar(rule, '(');
        int closeCount = countUnescapedChar(rule, ')');
        if (openCount != closeCount) {
            return Result.invalid("Mismatched parentheses",
                "Ensure all opening parentheses have matching closing parentheses");
        }

        if (hasUnescapedEmptyParens(rule)) {
            String toolName = rule.substring(0, rule.indexOf('('));
            if (toolName.isEmpty()) {
                return Result.invalid("Empty parentheses with no tool name",
                    "Specify a tool name before the parentheses");
            }
            return Result.invalid("Empty parentheses",
                "Either specify a pattern or use just \"" + toolName + "\" without parentheses");
        }


        // behavior/source arguments are irrelevant here — only toolName/pattern are read.
        PermissionRule parsed = PermissionEngine.permissionRuleFromString(
            rule, PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        String toolName = parsed.toolName();
        String ruleContent = parsed.pattern().orElse(null);

        String mcpServer = mcpServerNameFromString(toolName);
        if (mcpServer != null) {
            if (ruleContent != null || openCount > 0) {
                return Result.invalid("MCP rules do not support patterns in parentheses",
                    "Use \"" + toolName + "\" without parentheses, or use \"mcp__"
                        + mcpServer + "__*\" for all tools");
            }
            return Result.VALID;
        }

        if (org.apache.commons.lang3.StringUtils.isEmpty(toolName)) {
            return Result.invalid("Tool name cannot be empty");
        }

        char first = toolName.charAt(0);
        if (first != Character.toUpperCase(first)) {
            return Result.invalid("Tool names must start with uppercase",
                "Use \"" + StringUtils.capitalize(toolName) + "\"");
        }

        if (ruleContent != null) {
            Result custom = customValidation(toolName, ruleContent);
            if (custom != null && !custom.valid()) {
                return custom;
            }
        }

        if (BASH_PREFIX_TOOLS.contains(toolName) && ruleContent != null) {
            if (Strings.CS.contains(ruleContent, ":*") && !Strings.CS.endsWith(ruleContent, ":*")) {
                return Result.invalid("The :* pattern must be at the end",
                    "Move :* to the end for prefix matching, or use * for wildcard matching");
            }
            if (Strings.CS.equals(ruleContent, ":*")) {
                return Result.invalid("Prefix cannot be empty before :*",
                    "Specify a command prefix before :*");
            }
        }

        if (FILE_PATTERN_TOOLS.contains(toolName) && ruleContent != null) {
            if (Strings.CS.contains(ruleContent, ":*")) {
                return Result.invalid("The \":*\" syntax is only for Bash prefix rules",
                    "Use glob patterns like \"*\" or \"**\" for file matching");
            }
            if (Strings.CS.contains(ruleContent, "*")
                && !WILDCARD_BOUNDARY.matcher(ruleContent).find()
                && !Strings.CS.contains(ruleContent, "**")) {
                return Result.invalid("Wildcard placement might be incorrect",
                    "Wildcards are typically used at path boundaries");
            }
        }

        return Result.VALID;
    }


    private static Result customValidation(String toolName, String content) {
        return switch (toolName) {
            case "WebSearch" -> {
                if (Strings.CS.contains(content, "*") || Strings.CS.contains(content, "?")) {
                    yield Result.invalid("WebSearch does not support wildcards",
                        "Use exact search terms without * or ?");
                }
                yield Result.VALID;
            }
            case "WebFetch" -> {
                if (Strings.CS.contains(content, "://") || Strings.CS.startsWith(content, "http")) {
                    yield Result.invalid("WebFetch permissions use domain format, not URLs",
                        "Use \"domain:hostname\" format");
                }
                if (!Strings.CS.startsWith(content, "domain:")) {
                    yield Result.invalid("WebFetch permissions must use \"domain:\" prefix",
                        "Use \"domain:hostname\" format");
                }
                yield Result.VALID;
            }
            default -> null;
        };
    }


    private static String mcpServerNameFromString(String toolName) {
        if (toolName == null) return null;
        String[] parts = toolName.split("__", -1);
        if (parts.length < 2 || !Strings.CS.equals("mcp", parts[0]) || parts[1].isEmpty()) {
            return null;
        }
        return parts[1];
    }


    private static boolean isEscaped(String s, int index) {
        int backslashes = 0;
        for (int j = index - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }


    static int countUnescapedChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c && !isEscaped(s, i)) {
                count++;
            }
        }
        return count;
    }


    private static boolean hasUnescapedEmptyParens(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '(' && s.charAt(i + 1) == ')' && !isEscaped(s, i)) {
                return true;
            }
        }
        return false;
    }

}
