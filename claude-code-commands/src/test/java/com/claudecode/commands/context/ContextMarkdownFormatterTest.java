package com.claudecode.commands.context;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData.AgentEntry;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import com.claudecode.commands.context.ContextData.McpToolEntry;
import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.SkillEntry;
import com.claudecode.commands.context.ContextData.SkillInfo;
import com.claudecode.core.text.FormatUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextMarkdownFormatterTest {

    private static ContextData fullData() {
        return new ContextData(
            List.of(
                new Category("System prompt", 3_000, ContextColor.PROMPT_BORDER),
                new Category("Messages", 47_000, ContextColor.PURPLE),
                new Category(ContextData.AUTOCOMPACT_BUFFER, 13_000, ContextColor.INACTIVE),
                new Category(ContextData.FREE_SPACE, 137_000, ContextColor.PROMPT_BORDER)),
            50_000, 200_000, 25, "claude-opus-4-8",
            List.of(new MemoryFileEntry("/p/CLAUDE.md", "Project", 1_200)),
            List.of(new McpToolEntry("mcp__gh__issue", "gh", 800)),
            List.of(new AgentEntry("reviewer", "User", 40)),
            new SkillInfo(1, 30, List.of(new SkillEntry("deploy", "Project", 30))),
            187_000L, true, null, null);
    }

    @Test
    void rendersHeaderAndCategoryTable() {
        String out = ContextMarkdownFormatter.format(fullData());
        assertTrue(Strings.CS.startsWith(out, "## Context Usage\n\n"));
        assertTrue(Strings.CS.contains(out, "**Model:** claude-opus-4-8"));
        assertTrue(Strings.CS.contains(out, "**Tokens:** 50k / 200k (25%)"));
        assertTrue(Strings.CS.contains(out, "| Category | Tokens | Percentage |"));
        assertTrue(Strings.CS.contains(out, "| System prompt | 3k | 1.5% |"));
        // Free space and Autocompact buffer pinned after regular rows.
        int messages = out.indexOf("| Messages |");
        int free = out.indexOf("| Free space |");
        int buffer = out.indexOf("| Autocompact buffer |");
        assertTrue(messages < free && free < buffer);
    }

    @Test
    void rendersAllSections() {
        String out = ContextMarkdownFormatter.format(fullData());
        assertTrue(Strings.CS.contains(out, "### MCP Tools"));
        assertTrue(Strings.CS.contains(out, "| mcp__gh__issue | gh | 800 |"));
        assertTrue(Strings.CS.contains(out, "### Custom Agents"));
        assertTrue(Strings.CS.contains(out, "| reviewer | User | 40 |"));
        assertTrue(Strings.CS.contains(out, "### Memory Files"));
        assertTrue(Strings.CS.contains(out, "| Project | /p/CLAUDE.md | 1.2k |"));
        assertTrue(Strings.CS.contains(out, "### Skills"));
        assertTrue(Strings.CS.contains(out, "| deploy | Project | 30 |"));
    }

    @Test
    void emptySectionsAreOmitted() {
        ContextData data = new ContextData(
            List.of(new Category(ContextData.FREE_SPACE, 200_000, ContextColor.PROMPT_BORDER)),
            0, 200_000, 0, "m",
            List.of(), List.of(), List.of(), null, null, false, null, null);
        String out = ContextMarkdownFormatter.format(data);
        assertFalse(Strings.CS.contains(out, "### MCP Tools"));
        assertFalse(Strings.CS.contains(out, "### Custom Agents"));
        assertFalse(Strings.CS.contains(out, "### Memory Files"));
        assertFalse(Strings.CS.contains(out, "### Skills"));
    }

    @Test
    void manualCompactBufferRendersAsRegularRow() {
        ContextData data = new ContextData(
            List.of(
                new Category("Messages", 10_000, ContextColor.PURPLE),
                new Category(ContextData.MANUAL_COMPACT_BUFFER, 3_000, ContextColor.INACTIVE),
                new Category(ContextData.FREE_SPACE, 187_000, ContextColor.PROMPT_BORDER)),
            10_000, 200_000, 5, "m",
            List.of(), List.of(), List.of(), null, null, false, null, null);
        String out = ContextMarkdownFormatter.format(data);

        // regular row that appears before Free space.
        int compact = out.indexOf("| Compact buffer |");
        int free = out.indexOf("| Free space |");
        assertTrue(compact > 0 && compact < free);
    }

    @Test
    void formatTokens_matchesTsCompactNotation() {
        assertEquals("900", FormatUtils.formatTokens(900));
        assertEquals("1.3k", FormatUtils.formatTokens(1_321));
        assertEquals("1k", FormatUtils.formatTokens(1_000));
        assertEquals("2k", FormatUtils.formatTokens(1_999));
        assertEquals("200k", FormatUtils.formatTokens(200_000));
        assertEquals("1m", FormatUtils.formatTokens(1_000_000));
        assertEquals("1.5m", FormatUtils.formatTokens(1_500_000));
        // Folded in from the former commands-local TokenFormat: billions tier + negatives.
        assertEquals("2b", FormatUtils.formatTokens(2_000_000_000L));
        assertEquals("1.5b", FormatUtils.formatTokens(1_500_000_000L));
        assertEquals("-5", FormatUtils.formatTokens(-5));
    }
}
