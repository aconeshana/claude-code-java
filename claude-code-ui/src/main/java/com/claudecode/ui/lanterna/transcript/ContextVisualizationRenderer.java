package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import com.claudecode.commands.context.ContextSuggestionGenerator;
import com.claudecode.commands.context.ContextSuggestionGenerator.Suggestion;
import com.googlecode.lanterna.TextColor;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import static com.claudecode.core.text.FormatUtils.formatTokens;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Renders a {@link ContextData} snapshot into colored {@link MessagePanel.Segment} lines: the
 * colored square grid on the left, the category legend on the right, followed by the MCP tools /
 * Custom agents / Memory files / Skills sections and actionable suggestions.
 */
public final class ContextVisualizationRenderer {

    private static final String FULL_SQUARE = "⛁ ";
    private static final String PARTIAL_SQUARE = "⛀ ";
    private static final String FREE_SQUARE = "⛶ ";
    private static final String RESERVED_SQUARE = "⛝ ";
    private static final int LEGEND_GAP = 2;
    private static final List<String> SOURCE_DISPLAY_ORDER =
        List.of("Project", "User", "Managed", "Plugin", "Built-in");

    private ContextVisualizationRenderer() {}

    public static List<List<MessagePanel.Segment>> render(ContextData data, int terminalWidth) {
        List<List<MessagePanel.Segment>> lines = new ArrayList<>();
        lines.add(line(" Context Usage", LanternaTheme.inputText()));
        renderGridWithLegend(data, terminalWidth, lines);
        renderMcpTools(data, lines);
        renderAgents(data, lines);
        renderMemoryFiles(data, lines);
        renderSkills(data, lines);
        renderSuggestions(data, lines);
        return lines;
    }

    // ── grid + legend ────────────────────────────────────────────────────────

    record GridSquare(ContextColor color, String glyph) {}

    private static void renderGridWithLegend(ContextData data, int terminalWidth,
                                             List<List<MessagePanel.Segment>> lines) {
        List<List<GridSquare>> gridRows = buildGridRows(data, terminalWidth);
        List<List<MessagePanel.Segment>> legend = buildLegend(data);

        int gridCharWidth = gridRows.isEmpty() ? 0 : gridRows.getFirst().size() * 2;
        int rows = Math.max(gridRows.size(), legend.size());
        for (int i = 0; i < rows; i++) {
            List<MessagePanel.Segment> segments = new ArrayList<>();
            int width = 0;
            segments.add(new MessagePanel.Segment(" ", TextColor.ANSI.DEFAULT));
            if (i < gridRows.size()) {
                for (GridSquare square : gridRows.get(i)) {
                    segments.add(new MessagePanel.Segment(square.glyph(), resolve(square.color())));
                    width += 2;
                }
            }
            if (i < legend.size()) {
                segments.add(new MessagePanel.Segment(
                    " ".repeat(gridCharWidth - width + LEGEND_GAP), TextColor.ANSI.DEFAULT));
                segments.addAll(legend.get(i));
            }
            lines.add(segments);
        }
    }







    static List<List<GridSquare>> buildGridRows(ContextData data, int terminalWidth) {
        long window = data.maxTokens();
        boolean narrow = terminalWidth > 0 && terminalWidth < 80;
        int gridWidth = window >= 1_000_000 ? (narrow ? 5 : 20) : (narrow ? 5 : 10);
        int gridHeight = window >= 1_000_000 ? 10 : (narrow ? 5 : 10);
        int totalSquares = gridWidth * gridHeight;

        List<GridSquare> squares = new ArrayList<>();
        Category reserved = null;
        Category freeSpace = null;
        for (Category cat : data.categories()) {
            if (cat.isReserved()) {
                reserved = cat;
            } else if (ContextData.FREE_SPACE.equals(cat.name())) {
                freeSpace = cat;
            } else {
                appendCategorySquares(squares, cat, window, totalSquares, totalSquares);
            }
        }

        int reservedCount = reserved != null
            ? Math.max(1, (int) Math.round((double) reserved.tokens() / window * totalSquares))
            : 0;
        int freeTarget = totalSquares - reservedCount;
        while (squares.size() < freeTarget) {
            squares.add(new GridSquare(
                freeSpace != null ? freeSpace.color() : ContextColor.PROMPT_BORDER, FREE_SQUARE));
        }
        if (reserved != null) {

            // compact buffer renders as a regular fullness-based square.
            if (ContextData.AUTOCOMPACT_BUFFER.equals(reserved.name())) {
                for (int i = 0; i < reservedCount && squares.size() < totalSquares; i++) {
                    squares.add(new GridSquare(reserved.color(), RESERVED_SQUARE));
                }
            } else {
                appendCategorySquares(squares, reserved, window, totalSquares, totalSquares);
            }
        }

        List<List<GridSquare>> rows = new ArrayList<>();
        for (int i = 0; i < gridHeight; i++) {
            int from = i * gridWidth;
            int to = Math.min(squares.size(), from + gridWidth);
            rows.add(from < to ? squares.subList(from, to) : List.of());
        }
        return rows;
    }

    private static void appendCategorySquares(List<GridSquare> out, Category cat,
                                              long window, int totalSquares, int cap) {
        double exact = (double) cat.tokens() / window * totalSquares;
        int count = Math.max(1, (int) Math.round(exact));
        int whole = (int) Math.floor(exact);
        double fraction = exact - whole;
        for (int i = 0; i < count && out.size() < cap; i++) {
            double fullness = (i == whole && fraction > 0) ? fraction : 1.0;
            out.add(new GridSquare(cat.color(), fullness >= 0.7 ? FULL_SQUARE : PARTIAL_SQUARE));
        }
    }

    private static List<List<MessagePanel.Segment>> buildLegend(ContextData data) {
        List<List<MessagePanel.Segment>> legend = new ArrayList<>();
        legend.add(line(data.model() + " · " + formatTokens(data.totalTokens()) + "/"
                + formatTokens(data.maxTokens()) + " tokens (" + data.percentage() + "%)",
            LanternaTheme.ghostText()));
        legend.add(line("", TextColor.ANSI.DEFAULT));
        legend.add(line("Estimated usage by category", LanternaTheme.ghostText()));

        for (Category cat : data.categories()) {
            boolean visible = cat.tokens() > 0
                && !ContextData.FREE_SPACE.equals(cat.name())
                && !ContextData.AUTOCOMPACT_BUFFER.equals(cat.name());
            if (!visible) continue;
            legend.add(List.of(
                new MessagePanel.Segment(FULL_SQUARE.trim(), resolve(cat.color())),
                new MessagePanel.Segment(" " + cat.name() + ": ", LanternaTheme.inputText()),
                new MessagePanel.Segment(formatTokens(cat.tokens()) + " tokens ("
                    + percent(cat.tokens(), data.maxTokens()) + "%)", LanternaTheme.ghostText())));
        }
        data.categories().stream()
            .filter(c -> ContextData.FREE_SPACE.equals(c.name()) && c.tokens() > 0)
            .findFirst()
            .ifPresent(free -> legend.add(List.of(
                new MessagePanel.Segment(FREE_SQUARE.trim(), LanternaTheme.ghostText()),
                new MessagePanel.Segment(" Free space: ", LanternaTheme.inputText()),
                new MessagePanel.Segment(formatTokens(free.tokens()) + " ("
                    + percent(free.tokens(), data.maxTokens()) + "%)", LanternaTheme.ghostText()))));
        data.categories().stream()
            .filter(c -> ContextData.AUTOCOMPACT_BUFFER.equals(c.name()) && c.tokens() > 0)
            .findFirst()
            .ifPresent(buffer -> legend.add(List.of(
                new MessagePanel.Segment(RESERVED_SQUARE.trim(), resolve(buffer.color())),
                new MessagePanel.Segment(" " + buffer.name() + ": ", LanternaTheme.ghostText()),
                new MessagePanel.Segment(formatTokens(buffer.tokens()) + " tokens ("
                    + percent(buffer.tokens(), data.maxTokens()) + "%)", LanternaTheme.ghostText()))));
        return legend;
    }

    // ── sections ─────────────────────────────────────────────────────────────

    private static void renderMcpTools(ContextData data, List<List<MessagePanel.Segment>> lines) {
        if (data.mcpTools().isEmpty()) return;
        lines.add(line("", TextColor.ANSI.DEFAULT));
        lines.add(sectionHeader("MCP tools", " · /mcp"));
        for (ContextData.McpToolEntry tool : data.mcpTools()) {
            lines.add(detailLine(tool.name(), formatTokens(tool.tokens()) + " tokens"));
        }
    }

    private static void renderAgents(ContextData data, List<List<MessagePanel.Segment>> lines) {
        if (data.agents().isEmpty()) return;
        lines.add(line("", TextColor.ANSI.DEFAULT));
        lines.add(sectionHeader("Custom agents", " · /agents"));
        Map<String, List<ContextData.AgentEntry>> groups = groupBySource(
            data.agents(), ContextData.AgentEntry::sourceDisplay, ContextData.AgentEntry::tokens);
        for (Map.Entry<String, List<ContextData.AgentEntry>> group : groups.entrySet()) {
            lines.add(line("", TextColor.ANSI.DEFAULT));
            lines.add(line(" " + group.getKey(), LanternaTheme.ghostText()));
            for (ContextData.AgentEntry agent : group.getValue()) {
                lines.add(detailLine(agent.agentType(), formatTokens(agent.tokens()) + " tokens"));
            }
        }
    }

    private static void renderMemoryFiles(ContextData data, List<List<MessagePanel.Segment>> lines) {
        if (data.memoryFiles().isEmpty()) return;
        lines.add(line("", TextColor.ANSI.DEFAULT));
        lines.add(sectionHeader("Memory files", " · /memory"));
        for (ContextData.MemoryFileEntry file : data.memoryFiles()) {
            lines.add(detailLine(displayPath(file.path()), formatTokens(file.tokens()) + " tokens"));
        }
    }

    private static void renderSkills(ContextData data, List<List<MessagePanel.Segment>> lines) {
        if (data.skills() == null || data.skills().tokens() <= 0) return;
        lines.add(line("", TextColor.ANSI.DEFAULT));
        lines.add(sectionHeader("Skills", " · /skills"));
        Map<String, List<ContextData.SkillEntry>> groups = groupBySource(
            data.skills().skillFrontmatter(),
            ContextData.SkillEntry::sourceDisplay, ContextData.SkillEntry::tokens);
        for (Map.Entry<String, List<ContextData.SkillEntry>> group : groups.entrySet()) {
            lines.add(line("", TextColor.ANSI.DEFAULT));
            lines.add(line(" " + group.getKey(), LanternaTheme.ghostText()));
            for (ContextData.SkillEntry skill : group.getValue()) {
                lines.add(detailLine(skill.name(), formatTokens(skill.tokens()) + " tokens"));
            }
        }
    }

    private static void renderSuggestions(ContextData data, List<List<MessagePanel.Segment>> lines) {
        List<Suggestion> suggestions = ContextSuggestionGenerator.generate(data);
        if (suggestions.isEmpty()) return;
        lines.add(line("", TextColor.ANSI.DEFAULT));
        lines.add(line(" Suggestions", LanternaTheme.inputText()));
        boolean first = true;
        for (Suggestion suggestion : suggestions) {
            if (!first) lines.add(line("", TextColor.ANSI.DEFAULT));
            first = false;
            boolean warning = suggestion.severity() == ContextSuggestionGenerator.Severity.WARNING;
            List<MessagePanel.Segment> title = new ArrayList<>(List.of(
                new MessagePanel.Segment(warning ? " ⚠ " : " ℹ ",
                    warning ? LanternaTheme.toolWarning() : LanternaTheme.suggestion()),
                new MessagePanel.Segment(suggestion.title(), LanternaTheme.inputText())));
            if (suggestion.savingsTokens() != null) {
                title.add(new MessagePanel.Segment(
                    " → save ~" + formatTokens(suggestion.savingsTokens()),
                    LanternaTheme.ghostText()));
            }
            lines.add(title);
            lines.add(line("   " + suggestion.detail(), LanternaTheme.ghostText()));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static <T> Map<String, List<T>> groupBySource(
            List<T> items,
            Function<T, String> sourceOf,
            ToLongFunction<T> tokensOf) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T item : items) {
            groups.computeIfAbsent(sourceOf.apply(item), _ -> new ArrayList<>()).add(item);
        }
        Map<String, List<T>> ordered = new LinkedHashMap<>();
        for (String source : SOURCE_DISPLAY_ORDER) {
            List<T> group = groups.remove(source);
            if (group != null) {
                group.sort((a, b) -> Long.compare(tokensOf.applyAsLong(b), tokensOf.applyAsLong(a)));
                ordered.put(source, group);
            }
        }
        // Unknown sources keep insertion order after the known ones.
        ordered.putAll(groups);
        return ordered;
    }

    private static String displayPath(String path) {
        String home = System.getProperty("user.home");
        String cwd = System.getProperty("user.dir");
        try {
            Path p = Path.of(path);
            if (cwd != null && p.isAbsolute() && p.startsWith(Path.of(cwd))) {
                Path rel = Path.of(cwd).relativize(p);
                if (!rel.toString().isEmpty()) return rel.toString();
            }
        } catch (Exception _) {
            return path;
        }
        if (home != null && Strings.CS.startsWith(path, home + File.separator)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private static String percent(long tokens, long max) {
        return String.format("%.1f", tokens * 100.0 / max);
    }

    private static List<MessagePanel.Segment> sectionHeader(String title, String hint) {
        return List.of(
            new MessagePanel.Segment(" " + title, LanternaTheme.inputText()),
            new MessagePanel.Segment(hint, LanternaTheme.ghostText()));
    }

    private static List<MessagePanel.Segment> detailLine(String name, String dimSuffix) {
        return List.of(
            new MessagePanel.Segment(" └ " + name + ": ", LanternaTheme.inputText()),
            new MessagePanel.Segment(dimSuffix, LanternaTheme.ghostText()));
    }

    private static List<MessagePanel.Segment> line(String text, TextColor color) {
        return List.of(new MessagePanel.Segment(text, color));
    }

    private static TextColor resolve(ContextColor color) {
        return switch (color) {
            case PROMPT_BORDER -> LanternaTheme.promptBorder();
            case INACTIVE -> LanternaTheme.ghostText();
            case CYAN -> LanternaTheme.agentCyan();
            case PERMISSION -> LanternaTheme.permission();
            case CLAUDE -> LanternaTheme.claude();
            case WARNING -> LanternaTheme.toolWarning();
            case PURPLE -> LanternaTheme.agentPurple();
        };
    }
}
