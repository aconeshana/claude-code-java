package com.claudecode.commands.context;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.ToolIo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.claudecode.core.text.FormatUtils.formatTokens;

/**
 * Generates actionable suggestions from a {@link ContextData} snapshot for the {@code /context}
 * visualization footer.
 */
public final class ContextSuggestionGenerator {

    public enum Severity { INFO, WARNING }

    public record Suggestion(Severity severity, String title, String detail, Long savingsTokens) {}

    private static final int LARGE_TOOL_RESULT_PERCENT = 15;
    private static final long LARGE_TOOL_RESULT_TOKENS = 10_000;
    private static final int READ_BLOAT_PERCENT = 5;
    private static final int NEAR_CAPACITY_PERCENT = 80;
    private static final int MEMORY_HIGH_PERCENT = 5;
    private static final long MEMORY_HIGH_TOKENS = 5_000;

    private ContextSuggestionGenerator() {}

    public static List<Suggestion> generate(ContextData data) {
        List<Suggestion> suggestions = new ArrayList<>();
        checkNearCapacity(data, suggestions);
        checkLargeToolResults(data, suggestions);
        checkReadResultBloat(data, suggestions);
        checkMemoryBloat(data, suggestions);
        checkAutoCompactDisabled(data, suggestions);

        suggestions.sort((a, b) -> {
            if (a.severity() != b.severity()) {
                return a.severity() == Severity.WARNING ? -1 : 1;
            }
            long sa = a.savingsTokens() != null ? a.savingsTokens() : 0;
            long sb = b.savingsTokens() != null ? b.savingsTokens() : 0;
            return Long.compare(sb, sa);
        });
        return suggestions;
    }

    private static void checkNearCapacity(ContextData data, List<Suggestion> out) {
        if (data.percentage() >= NEAR_CAPACITY_PERCENT) {
            out.add(new Suggestion(Severity.WARNING,
                "Context is " + data.percentage() + "% full",
                data.autoCompactEnabled()
                    ? "Autocompact will trigger soon, which discards older messages. Use /compact now to control what gets kept."
                    : "Autocompact is disabled. Use /compact to free space, or enable autocompact in /config.",
                null));
        }
    }

    private static void checkLargeToolResults(ContextData data, List<Suggestion> out) {
        if (data.messageBreakdown() == null) return;
        for (ToolIo tool : data.messageBreakdown().toolCallsByType()) {
            long total = tool.callTokens() + tool.resultTokens();
            double percent = total * 100.0 / data.maxTokens();
            if (percent < LARGE_TOOL_RESULT_PERCENT || total < LARGE_TOOL_RESULT_TOKENS) {
                continue;
            }
            Suggestion suggestion = largeToolSuggestion(tool.name(), total, percent);
            if (suggestion != null) {
                out.add(suggestion);
            }
        }
    }

    private static Suggestion largeToolSuggestion(String toolName, long tokens, double percent) {
        String tokenStr = formatTokens(tokens);
        String pct = String.format("%.0f", percent);
        return switch (toolName) {
            case "Bash" -> new Suggestion(Severity.WARNING,
                "Bash results using " + tokenStr + " tokens (" + pct + "%)",
                "Pipe output through head, tail, or grep to reduce result size. "
                    + "Avoid cat on large files — use Read with offset/limit instead.",
                tokens / 2);
            case "Read" -> new Suggestion(Severity.INFO,
                "Read results using " + tokenStr + " tokens (" + pct + "%)",
                "Use offset and limit parameters to read only the sections you need. "
                    + "Avoid re-reading entire files when you only need a few lines.",
                (long) Math.floor(tokens * 0.3));
            case "Grep" -> new Suggestion(Severity.INFO,
                "Grep results using " + tokenStr + " tokens (" + pct + "%)",
                "Add more specific patterns or use the glob or type parameter to narrow file types. "
                    + "Consider Glob for file discovery instead of Grep.",
                (long) Math.floor(tokens * 0.3));
            case "WebFetch" -> new Suggestion(Severity.INFO,
                "WebFetch results using " + tokenStr + " tokens (" + pct + "%)",
                "Web page content can be very large. Consider extracting only the specific information needed.",
                (long) Math.floor(tokens * 0.4));
            default -> percent >= 20
                ? new Suggestion(Severity.INFO,
                    toolName + " using " + tokenStr + " tokens (" + pct + "%)",
                    "This tool is consuming a significant portion of context.",
                    (long) Math.floor(tokens * 0.2))
                : null;
        };
    }

    private static void checkReadResultBloat(ContextData data, List<Suggestion> out) {
        if (data.messageBreakdown() == null) return;
        ToolIo readTool = data.messageBreakdown().toolCallsByType().stream()
            .filter(t -> Strings.CS.equals("Read", t.name()))
            .findFirst().orElse(null);
        if (readTool == null) return;

        long totalReadTokens = readTool.callTokens() + readTool.resultTokens();
        double totalReadPercent = totalReadTokens * 100.0 / data.maxTokens();
        double readPercent = readTool.resultTokens() * 100.0 / data.maxTokens();

        // Skip if already covered by checkLargeToolResults (>= 15% band).
        if (totalReadPercent >= LARGE_TOOL_RESULT_PERCENT
                && totalReadTokens >= LARGE_TOOL_RESULT_TOKENS) {
            return;
        }
        if (readPercent >= READ_BLOAT_PERCENT
                && readTool.resultTokens() >= LARGE_TOOL_RESULT_TOKENS) {
            out.add(new Suggestion(Severity.INFO,
                "File reads using " + formatTokens(readTool.resultTokens())
                    + " tokens (" + String.format("%.0f", readPercent) + "%)",
                "If you are re-reading files, consider referencing earlier reads. Use offset/limit for large files.",
                (long) Math.floor(readTool.resultTokens() * 0.3)));
        }
    }

    private static void checkMemoryBloat(ContextData data, List<Suggestion> out) {
        long totalMemoryTokens = data.memoryFiles().stream()
            .mapToLong(MemoryFileEntry::tokens).sum();
        double memoryPercent = totalMemoryTokens * 100.0 / data.maxTokens();
        if (memoryPercent >= MEMORY_HIGH_PERCENT && totalMemoryTokens >= MEMORY_HIGH_TOKENS) {
            String largestFiles = data.memoryFiles().stream()
                .sorted(Comparator.comparingLong(MemoryFileEntry::tokens).reversed())
                .limit(3)
                .map(f -> DisplayPath.shorten(f.path()) + " (" + formatTokens(f.tokens()) + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            out.add(new Suggestion(Severity.INFO,
                "Memory files using " + formatTokens(totalMemoryTokens)
                    + " tokens (" + String.format("%.0f", memoryPercent) + "%)",
                "Largest: " + largestFiles + ". Use /memory to review and prune stale entries.",
                (long) Math.floor(totalMemoryTokens * 0.3)));
        }
    }

    private static void checkAutoCompactDisabled(ContextData data, List<Suggestion> out) {
        if (!data.autoCompactEnabled()
                && data.percentage() >= 50
                && data.percentage() < NEAR_CAPACITY_PERCENT) {
            out.add(new Suggestion(Severity.INFO,
                "Autocompact is disabled",
                "Without autocompact, you will hit context limits and lose the conversation. "
                    + "Enable it in /config or use /compact manually.",
                null));
        }
    }
}
