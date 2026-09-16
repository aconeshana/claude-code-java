package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendDimResult;

import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TextColor;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Transient progress rows a running tool emits before its result: MCP progress
 * notifications (bar + percentage), WebSearch query/result updates, the shell output
 * summary ({@code ⟳ N lines · size · Ns}) and the TaskOutput {@code Waiting for task} hint.
 *
 * <ul>
 *   <li>{@code src/components/messages/MCPProgressMessage.tsx} — {@code Running…}, message +
 *       20-cell bar with percentage, {@code Processing… N} without a total.</li>
 *   <li>{@code src/tools/WebSearchTool/UI.tsx} — {@code Searching: q} and {@code Found N
 *       results for "q"} progress rows.</li>
 *   <li>{@code src/components/ShellProgressMessage.tsx} — lines / bytes / elapsed summary
 *       with a {@code ✓} once complete.</li>
 *   <li>{@code src/tools/TaskOutputTool/UI.tsx} — {@code Waiting for task (esc to give
 *       additional instructions)}.</li>
 * </ul>
 */
final class ToolProgressRenderer {

    private ToolProgressRenderer() {}

    /** TaskOutput blocking on a background task: description row plus the esc hint. */
    static void waitingForTask(String content, MessagePanel panel) {
        String description = content == null ? ""
            : Strings.CS.removeStart(content, "Waiting for task ").strip();
        if (!StringUtils.isBlank(description)) {
            panel.appendLine("  " + description, TextColor.ANSI.DEFAULT);
        }
        panel.appendMixed(List.of(
            new MessagePanel.Segment("     Waiting for task ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment("(esc to give additional instructions)",
                LanternaTheme.welcomeDim())));
    }

    /** Structured shell progress: {@code ⟳ N lines · size · Ns [✓]}. */
    static void shellSummary(ProgressMessage.ProgressData data, MessagePanel panel) {
        StringBuilder sb = new StringBuilder("⟳ ");
        if (data.totalLines() != null && data.totalLines() > 0) {
            sb.append(data.totalLines()).append(" lines");
        }
        if (data.totalBytes() != null && data.totalBytes() > 0) {
            if (sb.length() > 2) sb.append(" · ");
            sb.append(FormatUtils.formatFileSize(data.totalBytes()));
        }
        if (data.elapsedTimeSeconds() != null && data.elapsedTimeSeconds() > 0) {
            if (sb.length() > 2) sb.append(" · ");
            sb.append(data.elapsedTimeSeconds().longValue()).append("s");
        }
        if (Boolean.FALSE.equals(data.isIncomplete())) {
            sb.append(" ✓");
        }
        panel.appendLine(sb.toString(), LanternaTheme.agentCyan());
    }

    static void mcp(ProgressMessage.ProgressData data, MessagePanel panel) {
        Double progress = data.progress();
        Double total = data.total();
        String message = data.progressMessage();
        if (progress == null) {
            appendDimResult(panel, -1, "Running…");
            return;
        }
        if (total != null && total > 0) {
            double ratio = Math.max(0.0, Math.min(1.0, progress / total));
            int percentage = (int) Math.round(ratio * 100);
            if (StringUtils.isNotBlank(message)) appendDimResult(panel, -1, message);
            int filled = (int) Math.round(ratio * 20);
            String bar = "█".repeat(filled) + "░".repeat(Math.max(0, 20 - filled));
            appendDimResult(panel, -1, bar + " " + percentage + "%");
            return;
        }
        appendDimResult(panel, -1, StringUtils.isBlank(message)
            ? "Processing… " + formatProgressNumber(progress) : message);
    }

    private static String formatProgressNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    static void webSearch(ProgressMessage.ProgressData data, MessagePanel panel) {
        String query = data.query() == null ? "" : data.query();
        if (Strings.CS.equals("query_update", data.type())) {
            appendDimResult(panel, -1, "Searching: " + query);
            return;
        }
        long count = data.resultCount() == null ? 0L : data.resultCount();
        appendDimResult(panel, -1, "Found " + count + " results for \"" + query + "\"");
    }
}
