package com.claudecode.tools.agent;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.config.ClaudePaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Reads and writes agent {@code .md} files.
 */
public final class AgentFileWriter {

    private AgentFileWriter() {}

    /** Thrown for agent-file-specific failures (duplicate file, built-in write attempt). */
    public static final class AgentFileException extends IOException {
        public AgentFileException(String message) { super(message); }
    }

    /**
     * Renders agent fields as {@code.md} file content: YAML frontmatter + blank line + raw system
     * prompt body.
     */
    public static String formatAsMarkdown(String agentType, String whenToUse, List<String> tools,
            String systemPrompt, String color, String model, String memory) {
        String escapedWhenToUse = whenToUse
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");

        boolean isAllTools = tools == null || (tools.size() == 1 && Strings.CS.equals("*", tools.getFirst()));
        String toolsLine  = isAllTools ? "" : "\ntools: " + String.join(", ", tools);
        String modelLine  = (StringUtils.isNotBlank(model)) ? "\nmodel: " + model : "";
        String colorLine  = (StringUtils.isNotBlank(color)) ? "\ncolor: " + color : "";
        String memoryLine = (StringUtils.isNotBlank(memory)) ? "\nmemory: " + memory : "";

        return "---\n"
            + "name: " + agentType + "\n"
            + "description: \"" + escapedWhenToUse + "\"" + toolsLine + modelLine + colorLine + memoryLine + "\n"
            + "---\n"
            + "\n"
            + systemPrompt + "\n";
    }

    /** {@code ~/.claude/agents/} for {@link AgentSource#USER}, {@code <cwd>/.claude/agents/} for {@link AgentSource#PROJECT}. */
    public static Path directoryFor(AgentSource source, String cwd) {
        return switch (source) {
            case USER -> ClaudePaths.AGENTS_DIR;
            case PROJECT -> Path.of(cwd, ".claude", "agents");
            case MANAGED -> throw new IllegalArgumentException("Managed agents have no writable directory");
            case BUILT_IN -> throw new IllegalArgumentException("Built-in agents have no writable directory");
            case FLAG_SETTINGS -> throw new IllegalArgumentException("CLI agents have no writable directory");
            // Plugin agents live inside the versioned plugin cache — read-only,

            case PLUGIN -> throw new IllegalArgumentException("Plugin agents have no writable directory");
        };
    }

    public static Path newFilePath(AgentSource source, String cwd, String agentType) {
        return directoryFor(source, cwd).resolve(agentType + ".md");
    }

    /** The real on-disk path for an existing (non-built-in) agent. */
    public static Path actualFilePath(BuiltInAgentDefinitions.AgentDefinition agent) {
        if (agent.source() == AgentSource.BUILT_IN) {
            throw new IllegalArgumentException("Built-in agents have no file path");
        }
        if (agent.filePath() != null) return agent.filePath();
        throw new IllegalStateException("Agent has no known file path: " + agent.agentType());
    }

    /**
     * Writes a new agent file.
     */
    public static void save(AgentSource source, String cwd, String agentType, String whenToUse,
            List<String> tools, String systemPrompt, String color, String model, String memory,
            boolean checkExists) throws IOException {
        if (source == AgentSource.BUILT_IN) throw new AgentFileException("Cannot save built-in agents");

        Path dir = directoryFor(source, cwd);
        Files.createDirectories(dir);
        Path filePath = dir.resolve(agentType + ".md");
        String content = formatAsMarkdown(agentType, whenToUse, tools, systemPrompt, color, model, memory);

        StandardOpenOption[] options = checkExists
            ? new StandardOpenOption[] {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE}
            : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
        try {
            writeAndFlush(filePath, content, options);
        } catch (FileAlreadyExistsException _) {
            throw new AgentFileException("Agent file already exists: " + filePath);
        }
    }

    /** Overwrites an existing (non-built-in) agent's file with new field values. */
    public static void update(BuiltInAgentDefinitions.AgentDefinition agent, String newWhenToUse,
            List<String> newTools, String newSystemPrompt, String newColor, String newModel,
            String newMemory) throws IOException {
        if (agent.source() == AgentSource.BUILT_IN) throw new AgentFileException("Cannot update built-in agents");

        Path filePath = actualFilePath(agent);
        String content = formatAsMarkdown(agent.agentType(), newWhenToUse, newTools, newSystemPrompt,
            newColor, newModel, newMemory);
        writeAndFlush(filePath, content,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

/**
     * Deletes an agent's file.
     */
    public static void delete(BuiltInAgentDefinitions.AgentDefinition agent) throws IOException {
        if (agent.source() == AgentSource.BUILT_IN) throw new AgentFileException("Cannot delete built-in agents");
        Files.deleteIfExists(actualFilePath(agent));
    }

    private static void writeAndFlush(Path filePath, String content, StandardOpenOption... options) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        try (FileChannel channel = FileChannel.open(filePath, options)) {
            FileUtils.writeFully(channel, buf);
            channel.force(true);
        }
    }
}
