package com.claudecode.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the immutable text channels for built-in tool definitions.
 */
public final class ToolTexts {

    private static final String OFFICIAL_197_ROOT = "/tool-text/official/2.1.197/";
    private static final Map<String, Optional<String>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");


    private static final Set<String> PRESERVE_TRAILING_NEWLINE = Set.of(
        "prompts/Agent.txt",
        "prompts/Agent/inline-intro.txt",
        "prompts/Agent/no-agents.txt",
        "prompts/AskUserQuestion.txt",
        "prompts/AskUserQuestion/html-preview.txt",
        "prompts/AskUserQuestion/markdown-preview.txt",
        "prompts/EnterPlanMode.txt",
        "prompts/ExitPlanMode.txt",
        "prompts/EnterWorktree.txt",
        "prompts/ExitWorktree.txt",
        "prompts/Grep.txt",
        "descriptions/ListMcpResourcesTool.txt",
        "prompts/ListMcpResourcesTool.txt",
        "prompts/ReadMcpResourceDirTool.txt",
        "descriptions/ReadMcpResourceTool.txt",
        "prompts/ReadMcpResourceTool.txt",
        "prompts/ScheduleWakeup.txt",
        "prompts/Skill.txt",
        "prompts/TaskCreate.txt",
        "prompts/TaskCreate/teammate.txt",
        "prompts/TaskGet.txt",
        "prompts/TaskList.txt",
        "prompts/TaskList/teammate.txt",
        "prompts/WebSearch/template.txt",
        "prompts/TaskStop.txt",
        "prompts/TaskUpdate.txt",
        "prompts/TodoWrite/long.txt",
        "prompts/WebFetch.txt",
        "prompts/WebSearch.txt"
    );

    private ToolTexts() {}

    /** Returns the required model-facing prompt for a tool. */
    public static String prompt(String toolName) {
        return require(resourcePath("prompts", toolName, null));
    }

    /** Returns a required model-facing prompt variant. */
    public static String prompt(String toolName, String variant) {
        return require(resourcePath("prompts", toolName, variant));
    }

    /**
     * Returns a tool's presentation description. A missing description file
     * intentionally inherits the tool's prompt.
     */
    public static String description(String toolName) {
        return load(resourcePath("descriptions", toolName, null))
            .orElseGet(() -> prompt(toolName));
    }

    /**
     * Returns a presentation-description variant. A missing override
     * intentionally inherits the matching prompt variant.
     */
    public static String description(String toolName, String variant) {
        return load(resourcePath("descriptions", toolName, variant))
            .orElseGet(() -> prompt(toolName, variant));
    }

    /** Renders a resource template and rejects missing or unused placeholders. */
    public static String render(String template, Map<String, ?> values) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(values, "values");
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        Set<String> consumed = new HashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!values.containsKey(key)) {
                throw new IllegalArgumentException("Missing tool text template value: " + key);
            }
            consumed.add(key);
            matcher.appendReplacement(rendered,
                Matcher.quoteReplacement(Objects.toString(values.get(key), "")));
        }
        matcher.appendTail(rendered);
        if (!consumed.equals(values.keySet())) {
            Set<String> unused = new HashSet<>(values.keySet());
            unused.removeAll(consumed);
            throw new IllegalArgumentException("Unused tool text template values: " + unused);
        }
        return rendered.toString();
    }

    private static String resourcePath(String channel, String toolName, String variant) {
        requirePathSegment(toolName, "tool name");
        if (variant == null) return channel + "/" + toolName + ".txt";
        requirePathSegment(variant, "variant");
        return channel + "/" + toolName + "/" + variant + ".txt";
    }

    private static void requirePathSegment(String value, String label) {
        if (StringUtils.isBlank(value)
                || Strings.CS.contains(value, "/")
                || Strings.CS.contains(value, "\\")
                || Strings.CS.contains(value, "..")) {
            throw new IllegalArgumentException("Invalid tool text " + label + ": " + value);
        }
    }

    private static String require(String relativePath) {
        return load(relativePath).orElseThrow(() -> new IllegalStateException(
            "Missing required tool text resource: " + OFFICIAL_197_ROOT + relativePath));
    }

    private static Optional<String> load(String relativePath) {
        return CACHE.computeIfAbsent(relativePath, ToolTexts::readResource);
    }

    private static Optional<String> readResource(String relativePath) {
        try (InputStream in = ToolTexts.class.getResourceAsStream(
                OFFICIAL_197_ROOT + relativePath)) {
            if (in == null) return Optional.empty();
            String text = restoreCapturedWhitespace(relativePath,
                new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (PRESERVE_TRAILING_NEWLINE.contains(relativePath)) {
                return Optional.of(text);
            }
// For the other tools, the source file's final LF is formatting only and
// was absent from the captured.
            return Optional.of(Strings.CS.endsWith(text, "\n")
                ? text.substring(0, text.length() - 1) : text);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to read tool text resource: " + OFFICIAL_197_ROOT + relativePath, e);
        }
    }

    private static String restoreCapturedWhitespace(String relativePath, String text) {
        if (Strings.CS.equals("prompts/ListMcpResourcesTool.txt", relativePath)) {

            return text.replace("'server' field\n", "'server' field \n");
        }
        return text;
    }
}
