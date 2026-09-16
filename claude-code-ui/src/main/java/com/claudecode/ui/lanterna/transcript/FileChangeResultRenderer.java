package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.appendDiffHunkSeparator;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.appendHighlightedCodeLine;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.appendInlineDiffHunk;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.countVisibleLines;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.diffLanguageForPath;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.isPlanFile;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.relativeToCwd;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.tokenLine;
import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.tokenizeCode;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_CONT;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_PREFIX;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendOrReplaceToolResultLine;

import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.ui.lanterna.dialog.RejectedFileChangePreview;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.ToolInvocation;
import com.claudecode.ui.lanterna.transcript.ToolResultRenderer.Placement;
import com.claudecode.ui.syntax.TmTokenizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders file-mutation tool results: structured Edit diffs with an added/removed summary,
 * Write previews (numbered, truncated to 10 lines on {@code create}), NotebookEdit cell
 * sources, and the dimmed "User rejected …" preview painted when a permission prompt
 * declines the change.
 *
 * <ul>
 *   <li>{@code src/tools/FileEditTool/UI.tsx} — {@code Added N lines, removed M lines} and
 *       the inline structured patch.</li>
 *   <li>{@code src/tools/FileWriteTool/UI.tsx} — {@code Wrote/Updated N lines to path}
 *       with a highlighted preview; {@code isResultTruncated} folds only {@code create}.</li>
 *   <li>{@code src/tools/NotebookEditTool/UI.tsx} — {@code Updated cell id:} plus the new
 *       source.</li>
 *   <li>{@code src/components/FileEditToolUseRejectedMessage.tsx} and the Write/Notebook
 *       rejected siblings — dimmed diff or source preview under the rejection header; the
 *       input-only fallback reconstructs the preview without filesystem access.</li>
 *   <li>Plan files under the plans directory collapse to {@code /plan to preview} outside
 *       verbose mode.</li>
 * </ul>
 */
final class FileChangeResultRenderer {

    private static final Logger log = LoggerFactory.getLogger(FileChangeResultRenderer.class);

    private final ToolResultRenderer.Host host;
    private final PendingToolLedger tools;

    FileChangeResultRenderer(ToolResultRenderer.Host host, PendingToolLedger tools) {
        this.host = host;
        this.tools = tools;
    }

    boolean renderRejected(RejectedFileChangePreview prepared,
                                             ToolInvocation invocation, MessagePanel panel,
                                             int replaceLine) {
        RejectedFileChangePreview preview = prepared != null
            ? prepared : fallbackRejectedFileChange(invocation);
        if (preview == null || StringUtils.isBlank(preview.filePath())) return false;
        String shownPath = host.verbose() ? preview.filePath() : relativeToCwd(preview.filePath());
        if (preview.kind() == RejectedFileChangePreview.Kind.NOTEBOOK) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(
                    INDENT_PREFIX + "User rejected " + preview.operation() + " ",
                    LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(shownPath, LanternaTheme.welcomeDim(), null, null,
                    Set.of(SGR.BOLD)),
                new MessagePanel.Segment(
                    " at cell " + preview.cellId(), LanternaTheme.welcomeDim())));
        } else {
            appendRejectedHeader(panel, replaceLine, preview.operation(), shownPath);
        }
        if (!preview.hunks().isEmpty()) {
            appendRejectedDiff(panel, preview.hunks(), preview.language());
        } else if (!StringUtils.isEmpty(preview.content())) {
            appendRejectedSourcePreview(panel, preview.content(), preview.language());
        }
        return true;
    }

    /** Input-only replay/remote fallback; deliberately performs no filesystem access. */
    private static RejectedFileChangePreview fallbackRejectedFileChange(
            ToolInvocation invocation) {
        if (invocation == null || invocation.inputJson() == null) return null;
        if (!Strings.CS.equalsAny(invocation.toolName(), "Edit", "FileEdit", "Write", "FileWrite",
                "NotebookEdit")) return null;
        JsonNode input;
        try {
            input = JsonUtils.getMapper().readTree(invocation.inputJson());
        } catch (JsonProcessingException _) {
            return null;
        }
        boolean notebook = Strings.CS.equals("NotebookEdit", invocation.toolName());
        String rawPath = notebook ? input.path("notebook_path").asText("")
            : input.path("file_path").asText(input.path("path").asText(""));
        if (StringUtils.isBlank(rawPath)) return null;
        String path = PathUtils.expandPath(
            rawPath, System.getProperty("user.dir", ".")).toString();
        if (notebook) {
            String mode = input.path("edit_mode").asText("replace");
            String operation = Strings.CS.equals("delete", mode)
                ? "delete" : mode + " cell in";
            String content = Strings.CS.equals("delete", mode)
                ? "" : input.path("new_source").asText("");
            return RejectedFileChangePreview.notebook(
                operation, path, input.path("cell_id").asText(""), List.of(), content,
                Strings.CS.equals("markdown", input.path("cell_type").asText())
                    ? "file.md" : "notebook.py");
        }
        if (Strings.CS.equalsAny(invocation.toolName(), "Edit", "FileEdit")) {
            String oldString = input.path("old_string").asText(input.path("old_str").asText(""));
            String newString = input.path("new_string").asText(input.path("new_str").asText(""));
            return RejectedFileChangePreview.inputEdit(path, oldString, newString);
        }
        return RejectedFileChangePreview.source(
            "write", path, input.path("content").asText(""), path);
    }

    private static void appendRejectedHeader(MessagePanel panel, int replaceLine,
                                             String operation, String shownPath) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "User rejected " + operation + " to ",
                LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(shownPath, LanternaTheme.welcomeDim(), null, null,
                Set.of(SGR.BOLD))));
    }

    private static void appendRejectedDiff(MessagePanel panel, List<StructuredPatchHunk> hunks,
                                           String filePath) {
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) appendDiffHunkSeparator(panel);
            appendInlineDiffHunk(panel, hunks.get(i), diffLanguageForPath(filePath), true);
        }
    }

    private static void appendRejectedSourcePreview(MessagePanel panel, String content,
                                                    String filePath) {
        String preview = StringUtils.isEmpty(content) ? "(No content)" : content;
        String[] lines = preview.split("\\n", -1);
        int available = lines.length;
        int visible = Math.min(available, 10);
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, diffLanguageForPath(filePath));
        for (int i = 0; i < visible; i++) {
            appendHighlightedCodeLine(panel, INDENT_CONT, lines[i],
                tokenLine(tokenized, i), true);
        }
        if (available > visible) {
            panel.appendLine(INDENT_CONT + "… +" + (available - visible) + " lines",
                LanternaTheme.welcomeDim());
        }
    }

    boolean renderStructured(Object payload, ToolResultBlock result,
                                                     MessagePanel panel) {
        if (payload == null) return false;
        var node = JsonUtils.getMapper().valueToTree(payload);
        if (node.path("filePath").isTextual()
                && node.path("structuredPatch").isArray()
                && !node.path("structuredPatch").isEmpty()
                && !Strings.CS.equals("create", node.path("type").asText())) {
            List<StructuredPatchHunk> hunks = decodeStructuredHunks(node);
            if (hunks == null || hunks.isEmpty()) return false;
            Placement placement = host.beginToolResult(result, panel);
            tools.forgetInvocation(result.toolUseId());
            renderStructuredEditResult(node, hunks, panel, placement.replaceLine());
            return true;
        }
        String type = node.path("type").asText();
        // Accept Write results that carry full content for both {@code create}
        // and {@code update}. An {@code update} with an empty {@code structuredPatch}
        // (identical rewrite) previously bailed here and fell through to the generic
        // folded output; 197's isResultTruncated never folds {@code update}.
        if (!isWriteContentType(type)
                || !node.path("filePath").isTextual()
                || !node.path("content").isTextual()) {
            return false;
        }

        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());

        String filePath = node.path("filePath").asText();
        String content = node.path("content").asText();
        if (!host.verbose() && isPlanFile(filePath)) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "/plan to preview",
                    LanternaTheme.welcomeDim())));
            return true;
        }
        int lineCount = countVisibleLines(content);
        boolean update = Strings.CS.equals("update", type);
        String shownPath = host.verbose() ? filePath : relativeToCwd(filePath);
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(update ? "Updated " : "Wrote ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(Integer.toString(lineCount), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(" lines to ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(shownPath, TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))
        ));

        String preview = content.isEmpty() ? "(No content)" : content;
        String[] lines = preview.split("\n", -1);
        int available = lines.length;
        // preview is always non-empty (the empty case maps to "(No content)"),
        // so only the trailing-newline adjustment remains.
        if (Strings.CS.endsWith(preview, "\n")) available--;
        // create truncates to 10 lines; update renders the full content because
        // 197's isResultTruncated only ever folds {@code create}.
        int visible = host.verbose() || update ? available : Math.min(available, 10);
        int numberWidth = Math.max(1, Integer.toString(Math.max(lineCount, visible)).length());
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, diffLanguageForPath(filePath));
        for (int i = 0; i < visible; i++) {
            String num = Integer.toString(i + 1);
            String number = " ".repeat(Math.max(0, numberWidth - num.length())) + num + " ";
            appendHighlightedCodeLine(panel, INDENT_CONT + number, lines[i],
                tokenLine(tokenized, i), false);
        }
        if (!update && !host.verbose() && lineCount > 10) {
            int hidden = lineCount - 10;
            panel.appendMixed(List.of(new MessagePanel.Segment(
                INDENT_CONT + "… +" + hidden + " " + (hidden == 1 ? "line" : "lines")
                    + " " + host.expandHint(),
                LanternaTheme.welcomeDim())));
        }
        return true;
    }

    boolean renderNotebookEdit(Object payload, ToolResultBlock result,
                                                       MessagePanel panel) {
        if (payload == null) return false;
        var node = JsonUtils.getMapper().valueToTree(payload);
        if (!node.path("notebook_path").isTextual()
                || !node.path("new_source").isTextual()
                || !node.path("edit_mode").isTextual()) {
            return false;
        }
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        String error = node.path("error").asText("");
        if (!StringUtils.isBlank(error)) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(error, LanternaTheme.toolError())));
            return true;
        }
        String cellId = node.path("cell_id").asText("");
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Updated cell ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(cellId, TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(":", TextColor.ANSI.DEFAULT)));
        appendNotebookSourcePreview(panel, node.path("new_source").asText(""));
        return true;
    }

    private static void appendNotebookSourcePreview(MessagePanel panel, String source) {
        String preview = StringUtils.isEmpty(source) ? "(No content)" : source;
        String[] lines = preview.split("\\n", -1);
        int available = Strings.CS.endsWith(preview, "\n") ? lines.length - 1 : lines.length;
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, "python");
        for (int i = 0; i < available; i++) {
            appendHighlightedCodeLine(panel, INDENT_CONT + "  ", lines[i],
                tokenLine(tokenized, i), false);
        }
    }

    /** Write results that carry a full {@code content} payload: new-file {@code create} and
     * overwrite {@code update}. Both belong to the FileWriteTool UI family and must never
     * fold to the generic collapsed output (197 folds only {@code create}, to 10 lines). */
    private static boolean isWriteContentType(String type) {
        return Strings.CS.equals("create", type) || Strings.CS.equals("update", type);
    }

    private static List<StructuredPatchHunk> decodeStructuredHunks(
            JsonNode node) {
        try {
            List<StructuredPatchHunk> hunks = new ArrayList<>();
            for (var hunkNode : node.path("structuredPatch")) {
                hunks.add(JsonUtils.getMapper().treeToValue(hunkNode, StructuredPatchHunk.class));
            }
            return hunks;
        } catch (JsonProcessingException e) {
            log.debug("Unable to render structured Edit result", e);
            return null;
        }
    }

    private void renderStructuredEditResult(JsonNode node,
                                            List<StructuredPatchHunk> hunks,
                                            MessagePanel panel, int replaceLine) {
        String filePath = node.path("filePath").asText();
        if (!host.verbose() && isPlanFile(filePath)) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "/plan to preview",
                    LanternaTheme.welcomeDim())));
            return;
        }

        int additions = hunks.stream().mapToInt(StructuredPatchHunk::addedCount).sum();
        int removals = hunks.stream().mapToInt(StructuredPatchHunk::removedCount).sum();
        appendOrReplaceToolResultLine(panel, replaceLine,
            editSummarySegments(additions, removals));

        String language = diffLanguageForPath(filePath);
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) appendDiffHunkSeparator(panel);
            appendInlineDiffHunk(panel, hunks.get(i), language, false);
        }
    }

    private static List<MessagePanel.Segment> editSummarySegments(int additions, int removals) {
        List<MessagePanel.Segment> segments = new ArrayList<>();
        segments.add(new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()));
        if (additions > 0) {
            segments.add(new MessagePanel.Segment("Added ", TextColor.ANSI.DEFAULT));
            segments.add(new MessagePanel.Segment(Integer.toString(additions),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            segments.add(new MessagePanel.Segment(
                " " + (additions == 1 ? "line" : "lines"), TextColor.ANSI.DEFAULT));
        }
        if (removals > 0) {
            segments.add(new MessagePanel.Segment(
                additions > 0 ? ", removed " : "Removed ", TextColor.ANSI.DEFAULT));
            segments.add(new MessagePanel.Segment(Integer.toString(removals),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            segments.add(new MessagePanel.Segment(
                " " + (removals == 1 ? "line" : "lines"), TextColor.ANSI.DEFAULT));
        }
        return List.copyOf(segments);
    }
}
