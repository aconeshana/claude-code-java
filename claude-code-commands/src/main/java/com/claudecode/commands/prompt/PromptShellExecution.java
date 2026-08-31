package com.claudecode.commands.prompt;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and expands shell interpolation embedded in markdown prompt text.
 */
public final class PromptShellExecution {

    private static final Pattern BLOCK_PATTERN =
        Pattern.compile("```!\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern INLINE_PATTERN = Pattern.compile("!`([^`]+)`");

    private PromptShellExecution() {}

    @FunctionalInterface
    public interface Runner {
        String run(String command, String originalPattern);
    }

    public static boolean containsShellPattern(String text) {
        if (StringUtils.isEmpty(text)) return false;
        return BLOCK_PATTERN.matcher(text).find() || inlineMatches(text).stream().findAny().isPresent();
    }

    public static String expand(String text, Runner runner) {
        if (StringUtils.isEmpty(text)) return text == null ? "" : text;
        List<ShellMatch> matches = new ArrayList<>();
        Matcher blocks = BLOCK_PATTERN.matcher(text);
        while (blocks.find()) {
            matches.add(new ShellMatch(blocks.group(), blocks.group(1)));
        }
        matches.addAll(inlineMatches(text));

        String result = text;
        for (ShellMatch match : matches) {
            String command = match.command() == null ? "" : match.command().trim();
            if (command.isEmpty()) continue;
            String output = runner.run(command, match.pattern());
            int index = result.indexOf(match.pattern());
            if (index >= 0) {
                result = result.substring(0, index)
                    + (output == null ? "" : output)
                    + result.substring(index + match.pattern().length());
            }
        }
        return result;
    }

    private static List<ShellMatch> inlineMatches(String text) {
        if (text == null || !Strings.CS.contains(text, "!`")) return List.of();
        List<ShellMatch> matches = new ArrayList<>();
        Matcher inline = INLINE_PATTERN.matcher(text);
        while (inline.find()) {
            int start = inline.start();
            if (start == 0 || Character.isWhitespace(text.charAt(start - 1))) {
                matches.add(new ShellMatch(inline.group(), inline.group(1)));
            }
        }
        return matches;
    }

    private record ShellMatch(String pattern, String command) {}
}
