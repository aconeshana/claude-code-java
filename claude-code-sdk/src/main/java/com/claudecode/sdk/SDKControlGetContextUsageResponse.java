package com.claudecode.sdk;

import java.util.List;
import java.util.Objects;

/** Breakdown of current context-window usage returned by the official Query API. */
public record SDKControlGetContextUsageResponse(
        List<Category> categories, int totalTokens, int maxTokens, int rawMaxTokens,
        double percentage, List<List<GridCell>> gridRows, String model,
        List<MemoryFile> memoryFiles, List<McpTool> mcpTools,
        List<NamedTokens> deferredBuiltinTools, List<NamedTokens> systemTools,
        List<NamedTokens> systemPromptSections, List<AgentUsage> agents,
        SlashCommandUsage slashCommands, SkillUsage skills, Integer autoCompactThreshold,
        boolean isAutoCompactEnabled, MessageBreakdown messageBreakdown, ApiUsage apiUsage) {
    public SDKControlGetContextUsageResponse {
        categories = copy(categories);
        gridRows = gridRows == null ? List.of()
            : gridRows.stream().map(List::copyOf).toList();
        Objects.requireNonNull(model, "model");
        memoryFiles = copy(memoryFiles);
        mcpTools = copy(mcpTools);
        deferredBuiltinTools = copy(deferredBuiltinTools);
        systemTools = copy(systemTools);
        systemPromptSections = copy(systemPromptSections);
        agents = copy(agents);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record Category(String name, int tokens, String color, Boolean isDeferred) {}
    public record GridCell(String color, boolean isFilled, String categoryName, int tokens,
                           double percentage, double squareFullness) {}
    public record MemoryFile(String path, String type, int tokens) {}
    public record McpTool(String name, String serverName, int tokens, Boolean isLoaded) {}
    public record NamedTokens(String name, int tokens, Boolean isLoaded) {}
    public record AgentUsage(String agentType, String source, int tokens) {}
    public record SlashCommandUsage(int totalCommands, int includedCommands, int tokens) {}
    public record SkillUsage(int totalSkills, int includedSkills, int tokens,
                             List<SkillFrontmatter> skillFrontmatter) {
        public SkillUsage {
            skillFrontmatter = skillFrontmatter == null ? List.of() : List.copyOf(skillFrontmatter);
        }
    }
    public record SkillFrontmatter(String name, String source, int tokens) {}
    public record MessageBreakdown(int toolCallTokens, int toolResultTokens, int attachmentTokens,
                                   int assistantMessageTokens, int userMessageTokens,
                                   List<ToolCallUsage> toolCallsByType,
                                   List<AttachmentUsage> attachmentsByType) {
        public MessageBreakdown {
            toolCallsByType = toolCallsByType == null ? List.of() : List.copyOf(toolCallsByType);
            attachmentsByType = attachmentsByType == null ? List.of() : List.copyOf(attachmentsByType);
        }
    }
    public record ToolCallUsage(String name, int callTokens, int resultTokens) {}
    public record AttachmentUsage(String name, int tokens) {}
    public record ApiUsage(int inputTokens, int outputTokens, int cacheCreationInputTokens,
                           int cacheReadInputTokens) {}
}
