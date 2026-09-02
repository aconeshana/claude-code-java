package com.claudecode.tools.tasks;

import com.claudecode.core.io.PathUtils;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.text.FormatUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Released-compatible activity descriptions consumed by the teammate task board. */
final class TaskActivityDescription {

    private static final int SUMMARY_WIDTH = 50;

    private TaskActivityDescription() {}

    static String describe(ToolUseBlock toolUse, Path cwd) {
        if (toolUse == null) return null;
        JsonNode input = toolUse.input();
        String name = toolUse.name();
        return switch (name == null ? "" : name) {
            case "Write", "FileWrite" -> pathActivity(
                input, "file_path", cwd, "Writing ", "Writing file");
            case "Edit", "FileEdit" -> pathActivity(
                input, "file_path", cwd, "Editing ", "Editing file");
            case "Read", "FileRead" -> pathActivity(
                input, "file_path", cwd, "Reading ", "Reading file");
            case "NotebookEdit" -> pathActivity(
                input, "notebook_path", cwd, "Editing notebook ", "Editing notebook");
            case "Grep" -> summarizedActivity(
                input, "pattern", "Searching for ", "Searching");
            case "Glob" -> summarizedActivity(
                input, "pattern", "Finding ", "Finding files");
            case "WebFetch" -> summarizedActivity(
                input, "url", "Fetching ", "Fetching web page");
            case "WebSearch" -> summarizedActivity(
                input, "query", "Searching for ", "Searching the web");
            case "Monitor" -> monitorActivity(input);
            case "Agent", "Task" -> agentActivity(input);
            case "Bash", "PowerShell" -> commandActivity(input);
            default -> null;
        };
    }

    private static String pathActivity(JsonNode input, String field, Path cwd,
                                       String prefix, String fallback) {
        String path = truthyText(input, field);
        return path == null ? fallback : prefix + displayPath(path, cwd);
    }

    private static String summarizedActivity(JsonNode input, String field,
                                             String prefix, String fallback) {
        String value = truthyText(input, field);
        return value == null ? fallback : prefix + FormatUtils.truncate(value, SUMMARY_WIDTH);
    }

    private static String monitorActivity(JsonNode input) {
        String description = truthyText(input, "description");
        return description == null ? "Monitoring" : "Monitoring: " + description;
    }

    private static String agentActivity(JsonNode input) {
        String description = nullableText(input, "description");
        if (description == null) return "Running task";
        String normalized = description.replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() ? "Running task" : normalized;
    }

    private static String commandActivity(JsonNode input) {
        String command = truthyText(input, "command");
        if (command == null) return "Running command";
        String description = nullableText(input, "description");
        return "Running " + (description != null
            ? description : FormatUtils.truncate(command, SUMMARY_WIDTH));
    }

    private static String displayPath(String original, Path cwd) {
        Path effectiveCwd = cwd != null ? cwd : Path.of(System.getProperty("user.dir", "."));
        try {
            Path normalizedCwd = effectiveCwd.toAbsolutePath().normalize();
            Path normalizedPath = PathUtils.expandPath(original, normalizedCwd.toString())
                .toAbsolutePath().normalize();
            try {
                String relative = normalizedCwd.relativize(normalizedPath).toString();
                if (!relative.isEmpty() && !Strings.CS.startsWith(relative, "..")) {
                    return relative;
                }
            } catch (IllegalArgumentException _) {
                // Different filesystem roots cannot be relativized; keep the original below.
            }
            String home = System.getProperty("user.home", "");
            if (!home.isEmpty() && Strings.CS.startsWith(original, home + File.separator)) {
                return "~" + original.substring(home.length());
            }
            return original;
        } catch (InvalidPathException | SecurityException _) {
            return original;
        }
    }

    /** JavaScript truthiness for schema-validated optional string fields. */
    private static String truthyText(JsonNode input, String field) {
        String value = nullableText(input, field);
        return StringUtils.isEmpty(value) ? null : value;
    }

    /** Preserves an explicit empty string while treating an absent/null field as null. */
    private static String nullableText(JsonNode input, String field) {
        if (input == null || !input.has(field) || input.get(field).isNull()) return null;
        return input.get(field).asText();
    }
}
