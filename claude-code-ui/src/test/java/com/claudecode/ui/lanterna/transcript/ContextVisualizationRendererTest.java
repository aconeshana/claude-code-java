package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextVisualizationRendererTest {

    private static ContextData data200k(List<Category> cats) {
        return new ContextData(cats, 50_000, 200_000, 25, "claude-opus-4-8",
            List.of(), List.of(), List.of(), null, 187_000L, true,
            new ContextData.MessageBreakdown(0, 0, 0, 0, 0, List.of()), null);
    }

    private static List<Category> standardCats() {
        return List.of(
            new Category("System prompt", 3_000, ContextColor.PROMPT_BORDER),
            new Category("Messages", 47_000, ContextColor.PURPLE),
            new Category(ContextData.AUTOCOMPACT_BUFFER, 13_000, ContextColor.INACTIVE),
            new Category(ContextData.FREE_SPACE, 137_000, ContextColor.PROMPT_BORDER));
    }

    private static String flatten(List<MessagePanel.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (MessagePanel.Segment s : segments) sb.append(s.text());
        return sb.toString();
    }

    @Test
    void grid200k_is10x10() {
        var rows = ContextVisualizationRenderer.buildGridRows(
            data200k(standardCats()), 120);
        assertEquals(10, rows.size());
        assertEquals(10, rows.getFirst().size());
        assertEquals(100, rows.stream().mapToInt(List::size).sum());
    }

    @Test
    void grid1m_is20x10() {
        var data = new ContextData(standardCats(), 50_000, 1_000_000, 5, "m[1m]",
            List.of(), List.of(), List.of(), null, null, true, null, null);
        var rows = ContextVisualizationRenderer.buildGridRows(data, 120);
        assertEquals(10, rows.size());
        assertEquals(20, rows.getFirst().size());
    }

    @Test
    void narrowTerminal_shrinksTo5x5() {
        var rows = ContextVisualizationRenderer.buildGridRows(
            data200k(standardCats()), 60);
        assertEquals(5, rows.size());
        assertEquals(5, rows.getFirst().size());
    }

    @Test
    void tinyCategoryStillGetsOneSquare() {
        var cats = List.of(
            new Category("Custom agents", 40, ContextColor.PERMISSION),
            new Category(ContextData.FREE_SPACE, 199_960, ContextColor.PROMPT_BORDER));
        var rows = ContextVisualizationRenderer.buildGridRows(data200k(cats), 120);
        long permissionSquares = rows.stream().flatMap(List::stream)
            .filter(s -> !Strings.CS.equals(s.glyph(), "⛶ "))
            .count();
        assertEquals(1, permissionSquares);
    }

    @Test
    void reservedBufferPinnedToGridTail() {
        var rows = ContextVisualizationRenderer.buildGridRows(
            data200k(standardCats()), 120);
        var flat = rows.stream().flatMap(List::stream).toList();
        // Autocompact buffer = 13k/200k*100 ≈ 6.5 → 7 squares of ⛝ at the end.
        var tail = flat.subList(flat.size() - 7, flat.size());
        assertTrue(tail.stream().allMatch(s -> Strings.CS.equals(s.glyph(), "⛝ ")),
            "last squares must be the reserved ⛝ tail");
        // And nothing before the tail is reserved.
        assertTrue(flat.subList(0, flat.size() - 7).stream()
            .noneMatch(s -> Strings.CS.equals(s.glyph(), "⛝ ")));
    }

    @Test
    void render_containsHeaderLegendAndFooterLines() {
        var lines = ContextVisualizationRenderer.render(data200k(standardCats()), 120);
        String all = String.join("\n", lines.stream().map(
            ContextVisualizationRendererTest::flatten).toList());
        assertTrue(Strings.CS.contains(all, "Context Usage"));
        assertTrue(Strings.CS.contains(all, "claude-opus-4-8 · 50k/200k tokens (25%)"));
        assertTrue(Strings.CS.contains(all, "Estimated usage by category"));
        assertTrue(Strings.CS.contains(all, "System prompt: 3k tokens (1.5%)"));

        assertTrue(Strings.CS.contains(all, "Free space: 137k (68.5%)"));
        assertTrue(Strings.CS.contains(all, "Autocompact buffer: 13k tokens (6.5%)"));
    }

    @Test
    void render_sectionsAndSuggestions() {
        var data = new ContextData(standardCats(), 170_000, 200_000, 85, "m",
            List.of(new ContextData.MemoryFileEntry("/tmp/CLAUDE.md", "Project", 500)),
            List.of(new ContextData.McpToolEntry("mcp__gh__pr", "gh", 700)),
            List.of(new ContextData.AgentEntry("reviewer", "User", 40),
                new ContextData.AgentEntry("planner", "Project", 90)),
            new ContextData.SkillInfo(1, 25,
                List.of(new ContextData.SkillEntry("deploy", "Project", 25))),
            187_000L, true,
            new ContextData.MessageBreakdown(0, 0, 0, 0, 0, List.of()), null);
        var lines = ContextVisualizationRenderer.render(data, 120);
        String all = String.join("\n", lines.stream().map(
            ContextVisualizationRendererTest::flatten).toList());
        assertTrue(Strings.CS.contains(all, "MCP tools · /mcp"));
        assertTrue(Strings.CS.contains(all, "└ mcp__gh__pr: 700 tokens"));
        assertTrue(Strings.CS.contains(all, "Custom agents · /agents"));
        // Project group ordered before User group.
        assertTrue(all.indexOf("planner") < all.indexOf("reviewer"));
        assertTrue(Strings.CS.contains(all, "Memory files · /memory"));
        assertTrue(Strings.CS.contains(all, "Skills · /skills"));
        // 85% full → near-capacity warning suggestion.
        assertTrue(Strings.CS.contains(all, "Suggestions"));
        assertTrue(Strings.CS.contains(all, "Context is 85% full"));
    }
}
