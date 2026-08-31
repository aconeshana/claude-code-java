package com.claudecode.commands.context;

import com.claudecode.commands.context.ContextData.AgentEntry;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.McpToolEntry;
import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.SkillEntry;

import java.util.List;

import static com.claudecode.core.text.FormatUtils.formatTokens;

/**
 * Plain Markdown rendering of {@link ContextData} — the {@code /context} output for non-interactive
 * sessions (headless / bridge / print mode) where the Lanterna colored grid is unavailable.
 */
public final class ContextMarkdownFormatter {

    private ContextMarkdownFormatter() {}

    public static String format(ContextData data) {
        StringBuilder out = new StringBuilder();
        out.append("## Context Usage\n\n");
        out.append("**Model:** ").append(data.model()).append("  \n");
        out.append("**Tokens:** ").append(formatTokens(data.totalTokens()))
            .append(" / ").append(formatTokens(data.maxTokens()))
            .append(" (").append(data.percentage()).append("%)\n\n");

        appendCategories(out, data);
        appendMcpTools(out, data.mcpTools());
        appendAgents(out, data.agents());
        appendMemoryFiles(out, data.memoryFiles());
        appendSkills(out, data);
        return out.toString();
    }

    private static void appendCategories(StringBuilder out, ContextData data) {

        // the manual-mode 'Compact buffer' renders as a regular row.
        List<Category> visible = data.categories().stream()
            .filter(c -> c.tokens() > 0
                && !ContextData.FREE_SPACE.equals(c.name())
                && !ContextData.AUTOCOMPACT_BUFFER.equals(c.name()))
            .toList();
        if (visible.isEmpty()) return;

        out.append("### Estimated usage by category\n\n");
        out.append("| Category | Tokens | Percentage |\n");
        out.append("|----------|--------|------------|\n");
        for (Category cat : visible) {
            appendCategoryRow(out, cat, data.maxTokens());
        }
        data.categories().stream()
            .filter(c -> ContextData.FREE_SPACE.equals(c.name()) && c.tokens() > 0)
            .findFirst()
            .ifPresent(c -> appendCategoryRow(out, c, data.maxTokens()));
        data.categories().stream()
            .filter(c -> ContextData.AUTOCOMPACT_BUFFER.equals(c.name()) && c.tokens() > 0)
            .findFirst()
            .ifPresent(c -> appendCategoryRow(out, c, data.maxTokens()));
        out.append('\n');
    }

    private static void appendCategoryRow(StringBuilder out, Category cat, long maxTokens) {
        String percent = String.format("%.1f", cat.tokens() * 100.0 / maxTokens);
        out.append("| ").append(cat.name())
            .append(" | ").append(formatTokens(cat.tokens()))
            .append(" | ").append(percent).append("% |\n");
    }

    private static void appendMcpTools(StringBuilder out, List<McpToolEntry> tools) {
        if (tools.isEmpty()) return;
        out.append("### MCP Tools\n\n");
        out.append("| Tool | Server | Tokens |\n");
        out.append("|------|--------|--------|\n");
        for (McpToolEntry tool : tools) {
            out.append("| ").append(tool.name())
                .append(" | ").append(tool.serverName())
                .append(" | ").append(formatTokens(tool.tokens())).append(" |\n");
        }
        out.append('\n');
    }

    private static void appendAgents(StringBuilder out, List<AgentEntry> agents) {
        if (agents.isEmpty()) return;
        out.append("### Custom Agents\n\n");
        out.append("| Agent Type | Source | Tokens |\n");
        out.append("|------------|--------|--------|\n");
        for (AgentEntry agent : agents) {
            out.append("| ").append(agent.agentType())
                .append(" | ").append(agent.sourceDisplay())
                .append(" | ").append(formatTokens(agent.tokens())).append(" |\n");
        }
        out.append('\n');
    }

    private static void appendMemoryFiles(StringBuilder out, List<MemoryFileEntry> files) {
        if (files.isEmpty()) return;
        out.append("### Memory Files\n\n");
        out.append("| Type | Path | Tokens |\n");
        out.append("|------|------|--------|\n");
        for (MemoryFileEntry file : files) {
            out.append("| ").append(file.type())
                .append(" | ").append(file.path())
                .append(" | ").append(formatTokens(file.tokens())).append(" |\n");
        }
        out.append('\n');
    }

    private static void appendSkills(StringBuilder out, ContextData data) {
        if (data.skills() == null || data.skills().tokens() <= 0
                || data.skills().skillFrontmatter().isEmpty()) {
            return;
        }
        out.append("### Skills\n\n");
        out.append("| Skill | Source | Tokens |\n");
        out.append("|-------|--------|--------|\n");
        for (SkillEntry skill : data.skills().skillFrontmatter()) {
            out.append("| ").append(skill.name())
                .append(" | ").append(skill.sourceDisplay())
                .append(" | ").append(formatTokens(skill.tokens())).append(" |\n");
        }
        out.append('\n');
    }
}
