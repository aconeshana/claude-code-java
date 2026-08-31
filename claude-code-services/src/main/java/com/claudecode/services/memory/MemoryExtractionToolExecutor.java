package com.claudecode.services.memory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.services.session.MessageExtractor;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool restriction for the background memory-extraction sub-agent.
 */
public final class MemoryExtractionToolExecutor implements ToolExecutor {

    private static final Set<String> ALLOWED_UNRESTRICTED = Set.of("Read", "Grep", "Glob");
    private static final Set<String> EDIT_WRITE_TOOLS = Set.of("Edit", "Write");
    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
        "ls", "find", "grep", "rg", "cat", "stat", "wc", "head", "tail", "pwd", "tree", "echo", "which");
    private static final Pattern COMPOUND_SPLIT = Pattern.compile("&&|\\|\\||;");

    private final ToolExecutor delegate;
    private final Path memoryDir;

    public MemoryExtractionToolExecutor(ToolExecutor delegate, Path memoryDir) {
        this.delegate = delegate;
        this.memoryDir = memoryDir.toAbsolutePath().normalize();
    }

    @Override
    public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
        if (ALLOWED_UNRESTRICTED.contains(toolName)) {
            return delegate.execute(toolName, input, context);
        }
        if (Strings.CS.equals("Bash", toolName)) {
            String command = textField(input, "command");
            if (isReadOnlyCommand(command)) {
                return delegate.execute(toolName, input, context);
            }
            return ToolResult.error("Only read-only shell commands are permitted in this context "
                + "(ls, find, grep, cat, stat, wc, head, tail, and similar)");
        }
        if (EDIT_WRITE_TOOLS.contains(toolName)) {
            String filePath = textField(input, "file_path");
            if (filePath != null && isWithinMemoryDir(filePath)) {
                return delegate.execute(toolName, input, context);
            }
            return ToolResult.error("Only " + String.join("/", EDIT_WRITE_TOOLS)
                + " within " + memoryDir + " are allowed");
        }
        return ToolResult.error("only Read, Grep, Glob, read-only Bash, and Edit/Write within "
            + memoryDir + " are allowed");
    }


    static boolean isReadOnlyCommand(String command) {
        if (StringUtils.isBlank(command)) return false;
        // Output/append redirection writes to the filesystem regardless of which
        // command precedes it (e.g. "echo x > foo.md") — reject unconditionally
        // rather than trying to parse quoting to tell a real redirect from a
        // literal '>' in a string argument (conservative: false denials are safe,
        // false allows are not).
        if (Strings.CS.contains(command, ">")) return false;
        for (String segment : COMPOUND_SPLIT.split(command)) {
            String cli = MessageExtractor.extractCliName(segment);
            if (cli == null || !READ_ONLY_COMMANDS.contains(cli)) return false;
        }
        return true;
    }

    private boolean isWithinMemoryDir(String filePath) {
        Path path = Path.of(filePath);
        Path resolved = path.isAbsolute()
            ? path.normalize()
            : memoryDir.resolve(filePath).normalize();
        return resolved.startsWith(memoryDir);
    }

    private static String textField(JsonNode input, String field) {
        if (input == null || !input.has(field)) return null;
        JsonNode n = input.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {

        // fork must retain the parent's complete tool catalog so its prompt
        // cache key remains identical; unsupported calls are denied when the
        // model actually attempts them. Filtering here caused Java Dream to
        // advertise only four tools and produced a different cache prefix.
        return delegate.getToolDefinitions();
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
            ToolExecutionContext context) {
        return delegate.getToolDefinitions(context);
    }

    @Override
    public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions(
            Set<String> discoveredToolNames, ToolExecutionContext context) {
        return delegate.getToolDefinitions(discoveredToolNames, context);
    }
}
