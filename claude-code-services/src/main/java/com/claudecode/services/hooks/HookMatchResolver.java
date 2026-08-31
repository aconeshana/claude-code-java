package com.claudecode.services.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.tool.LegacyToolNames;

/**
 * Resolves hook source snapshots into the commands eligible for one dispatch.
 */
final class HookMatchResolver {

    List<MatchedHook> resolve(HookEvent event, HookInput input,
                              List<? extends List<HookMatcher>> sourceLayers) {
        String query = matchQuery(event, input);
        List<MatchedHook> matches = new ArrayList<>();
        for (List<HookMatcher> sourceLayer : sourceLayers) {
            for (HookMatcher matcher : sourceLayer) {
                if (!matcher.matches(query)) {
                    continue;
                }
                for (HookCommand command : matcher.hooks()) {
                    matches.add(new MatchedHook(matcher, command));
                }
            }
        }
        return matches;
    }

    boolean matchesIfCondition(HookCommand command, HookInput input) {
        Optional<String> ifCond = command.ifCondition();
        if (ifCond.isEmpty() || StringUtils.isBlank(ifCond.get())) {
            return true;
        }

        String condition = ifCond.get().trim();
        int openParen = findFirstUnescapedChar(condition, '(');
        if (openParen <= 0) {
            return LegacyToolNames.normalize(condition)
                .equals(LegacyToolNames.normalize(input.toolName().orElse("")));
        }
        int closeParen = findLastUnescapedChar(condition, ')');
        if (closeParen != condition.length() - 1) {
            return LegacyToolNames.normalize(condition)
                .equals(LegacyToolNames.normalize(input.toolName().orElse("")));
        }

        String hookTool = LegacyToolNames.normalize(condition.substring(0, openParen));
        String eventTool = LegacyToolNames.normalize(input.toolName().orElse(""));
        if (!hookTool.equals(eventTool)) {
            return false;
        }

        String ruleContent = unescapeParens(condition.substring(openParen + 1, closeParen));
        if (ruleContent.isEmpty() || Strings.CS.equals(ruleContent, "*")) {
            return true;
        }

        Optional<JsonNode> toolInput = input.toolInput();
        return switch (hookTool) {
            case "Bash" -> matchWildcardPattern(ruleContent,
                toolInput.map(node -> node.path("command").asText("")).orElse(""));
            case "Read", "Edit", "Write", "MultiEdit" -> matchWildcardPattern(ruleContent,
                toolInput.map(node -> node.path("file_path").asText("")).orElse(""));
            case "Glob", "Grep" -> matchWildcardPattern(ruleContent,
                toolInput.map(node -> node.path("pattern").asText("")).orElse(""));
            default -> false;
        };
    }

    private static String matchQuery(HookEvent event, HookInput input) {
        return switch (event) {
            case SESSION_START -> extraString(input, "source");
            case SESSION_END -> extraString(input, "reason");
            case INSTRUCTIONS_LOADED -> extraString(input, "load_reason");
            case PRE_COMPACT, POST_COMPACT -> extraString(input, "trigger");
            case SUBAGENT_START, SUBAGENT_STOP -> extraString(input, "agent_type");
            case STOP_FAILURE -> extraString(input, "error");
            default -> input.toolName().orElse("");
        };
    }

    private static String extraString(HookInput input, String key) {
        Object value = input.extra().get(key);
        return value != null ? String.valueOf(value) : "";
    }

    static boolean matchWildcardPattern(String pattern, String command) {
        String trimmedPattern = pattern.strip();
        if (Strings.CS.endsWith(trimmedPattern, ":*")) {
            String prefix = trimmedPattern.substring(0, trimmedPattern.length() - 2);
            return command.equals(prefix) || Strings.CS.startsWith(command, prefix + " ");
        }

        StringBuilder regex = new StringBuilder();
        int unescapedStarCount = 0;
        int optionalTrailingArgumentStart = -1;
        int index = 0;
        while (index < trimmedPattern.length()) {
            char character = trimmedPattern.charAt(index);
            if (character == '\\' && index + 1 < trimmedPattern.length()) {
                char next = trimmedPattern.charAt(index + 1);
                if (next == '*') {
                    regex.append(Pattern.quote("*"));
                    index += 2;
                    continue;
                }
                if (next == '\\') {
                    regex.append(Pattern.quote("\\"));
                    index += 2;
                    continue;
                }
            }
            if (character == '*') {
                unescapedStarCount++;
                if (index == trimmedPattern.length() - 1 && index > 0
                    && trimmedPattern.charAt(index - 1) == ' ') {
                    optionalTrailingArgumentStart = regex.length() - Pattern.quote(" ").length();
                }
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
            index++;
        }
        if (unescapedStarCount == 1 && optionalTrailingArgumentStart >= 0) {
            regex.replace(optionalTrailingArgumentStart, regex.length(), "( .*)?");
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(command).matches();
    }

    private static int findFirstUnescapedChar(String value, char target) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\\') {
                index++;
                continue;
            }
            if (value.charAt(index) == target) {
                return index;
            }
        }
        return -1;
    }

    private static int findLastUnescapedChar(String value, char target) {
        int found = -1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\\') {
                index++;
                continue;
            }
            if (value.charAt(index) == target) {
                found = index;
            }
        }
        return found;
    }

    private static String unescapeParens(String value) {
        return value.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\");
    }

    record MatchedHook(HookMatcher matcher, HookCommand command) {
    }
}
