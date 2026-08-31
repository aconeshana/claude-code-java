package com.claudecode.tools.hints;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the Claude Code hints protocol.
 */
public final class ClaudeCodeHints {

    /** Spec versions this harness understands. */
    private static final Set<Integer> SUPPORTED_VERSIONS = Set.of(1);
    /** Hint types understood at the supported versions. */
    private static final Set<String> SUPPORTED_TYPES = Set.of("plugin");

    /**
     * Outer tag match — anchored to whole lines (multiline mode) so a hint marker buried in a larger
     * line (e.g.
     */
    private static final Pattern HINT_TAG_RE =
        Pattern.compile("^[ \t]*<claude-code-hint\\s+([^>]*?)\\s*\\/>[ \t]*$", Pattern.MULTILINE);

    /**
     * Attribute matcher — accepts {@code key="value"} and {@code key=value} (terminated by whitespace
     * or the {@code />} close).
     */
    private static final Pattern ATTR_RE =
        Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^\\s/>]+))");

    private ClaudeCodeHints() {}

    /** Result of {@link #extractClaudeCodeHints}: parsed hints + the stripped output. */
    public record HintExtraction(List<ClaudeCodeHint> hints, String stripped) {}

    /**
     * Scan shell-tool output for hint tags, returning the parsed hints and the output with hint lines
     * removed.
     */
    public static HintExtraction extractClaudeCodeHints(String output, String command) {
        // Fast path: no tag open sequence → no work, no allocation.
        if (output == null || !Strings.CS.contains(output, "<claude-code-hint")) {
            return new HintExtraction(List.of(), output == null ? "" : output);
        }

        String sourceCommand = firstCommandToken(command);
        List<ClaudeCodeHint> hints = new ArrayList<>();
        Matcher m = HINT_TAG_RE.matcher(output);
        String stripped = m.replaceAll(mr -> {
            Map<String, String> attrs = parseAttrs(mr.group(1));
            int v;
            try {
                v = Integer.parseInt(attrs.getOrDefault("v", "-1"));
            } catch (NumberFormatException _) {
                v = -1;
            }
            String type = attrs.get("type");
            String value = attrs.get("value");
            if (!SUPPORTED_VERSIONS.contains(v)) {
                return "";
            }
            if (type == null || !SUPPORTED_TYPES.contains(type)) {
                return "";
            }
            if (StringUtils.isEmpty(value)) {
                return "";
            }
            hints.add(new ClaudeCodeHint(v, type, value, sourceCommand));
            return "";
        });

        // Dropping a matched line leaves a blank line (surrounding newlines remain).
        // Collapse runs of blank lines so the model-visible output doesn't grow.
        String collapsed = (hints.isEmpty() && stripped.equals(output))
            ? stripped
            : stripped.replaceAll("\n{3,}", "\n\n");
        return new HintExtraction(List.copyOf(hints), collapsed);
    }

    private static Map<String, String> parseAttrs(String body) {
        Map<String, String> attrs = new HashMap<>();
        if (body == null) return attrs;
        Matcher am = ATTR_RE.matcher(body);
        while (am.find()) {
            String key = am.group(1);
            String val = am.group(2) != null ? am.group(2) : (am.group(3) != null ? am.group(3) : "");
            attrs.put(key, val);
        }
        return attrs;
    }

    private static String firstCommandToken(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx == -1 ? trimmed : trimmed.substring(0, spaceIdx);
    }
}
