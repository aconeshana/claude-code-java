package com.claudecode.commands.context;

import com.claudecode.core.message.Usage;

import java.util.List;

/**
 * Immutable snapshot of context-window usage produced by {@link ContextUsageAnalyzer} and consumed
 * by both renderers (the Lanterna colored-grid visualization and the headless Markdown table).
 */
public record ContextData(
    List<Category> categories,
    long totalTokens,
    long maxTokens,
    int percentage,
    String model,
    List<MemoryFileEntry> memoryFiles,
    List<McpToolEntry> mcpTools,
    List<AgentEntry> agents,
    SlashCommandInfo slashCommands,
    SkillInfo skills,
    Long autoCompactThreshold,
    String autoCompactSource,
    boolean autoCompactEnabled,
    MessageBreakdown messageBreakdown,
    Usage apiUsage
) {

    /** Compatibility constructor for renderers/tests created before source reporting. */
    public ContextData(
            List<Category> categories, long totalTokens, long maxTokens,
            int percentage, String model, List<MemoryFileEntry> memoryFiles,
            List<McpToolEntry> mcpTools, List<AgentEntry> agents,
            SkillInfo skills, Long autoCompactThreshold,
            boolean autoCompactEnabled, MessageBreakdown messageBreakdown,
            Usage apiUsage) {
        this(categories, totalTokens, maxTokens, percentage, model, memoryFiles,
            mcpTools, agents, null, skills, autoCompactThreshold, "auto",
            autoCompactEnabled, messageBreakdown, apiUsage);
    }

    /** Category display names shared by analyzer and renderers. */
    public static final String FREE_SPACE = "Free space";
    public static final String AUTOCOMPACT_BUFFER = "Autocompact buffer";
    public static final String MANUAL_COMPACT_BUFFER = "Compact buffer";


    public enum ContextColor {
        PROMPT_BORDER, INACTIVE, CYAN, PERMISSION, CLAUDE, WARNING, PURPLE
    }

    public record Category(String name, long tokens, ContextColor color) {
        public boolean isReserved() {
            return AUTOCOMPACT_BUFFER.equals(name) || MANUAL_COMPACT_BUFFER.equals(name);
        }
    }

    public record MemoryFileEntry(String path, String type, long tokens) {}

    public record McpToolEntry(String name, String serverName, long tokens) {}

    public record AgentEntry(String agentType, String sourceDisplay, long tokens) {}

    public record SkillEntry(String name, String sourceDisplay, long tokens) {}

    public record SlashCommandInfo(int totalCommands, int includedCommands, long tokens) {}

    public record SkillInfo(int totalSkills, long tokens, List<SkillEntry> skillFrontmatter) {}

    /** Per-tool call/result token pair, sorted by combined size descending. */
    public record ToolIo(String name, long callTokens, long resultTokens) {}

    public record MessageBreakdown(
        long totalTokens,
        long toolCallTokens,
        long toolResultTokens,
        long assistantMessageTokens,
        long userMessageTokens,
        long redirectedContextTokens,
        long unattributedTokens,
        List<ToolIo> toolCallsByType
    ) {
/**
         * Compatibility constructor for callers without.
         */
        public MessageBreakdown(
                long totalTokens, long toolCallTokens, long toolResultTokens,
                long assistantMessageTokens, long userMessageTokens,
                List<ToolIo> toolCallsByType) {
            this(totalTokens, toolCallTokens, toolResultTokens,
                assistantMessageTokens, userMessageTokens, 0, 0,
                toolCallsByType);
        }
    }
}
