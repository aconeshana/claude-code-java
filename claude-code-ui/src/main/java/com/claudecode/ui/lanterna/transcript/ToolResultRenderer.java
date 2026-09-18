package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.displayPath;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_CONT;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_PREFIX;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendDimResult;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendOrReplaceToolResultLine;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.fallbackToolErrorText;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.planFromRejectionResult;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.renderClassifierDenial;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.renderInterruptedToolResult;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.renderRejectedPlan;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.singular;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.toolResultText;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.dialog.RejectedFileChangePreview;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.PendingTool;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Renders the body of a resolved tool call under its header: the per-tool summaries
 * selected by {@link ToolVisualContractRegistry#resultMode} (search counts, task output,
 * skills, worktrees, LSP, MCP, web, plan mode, cron) and the generic fallback that folds
 * plain text output, rejections, interruptions and classifier denials.
 *
 * <p>The renderer never touches dispatcher state directly: the {@link Host} completes the
 * tool card and tells it where the body goes ({@link Placement}), while the shared
 * {@link PendingToolLedger} supplies the recorded invocation for tools whose result alone
 * is ambiguous.
 *
 * <ul>
 *   <li>{@code src/components/messages/UserToolResultMessage.tsx} — result routing:
 *       rejection, interruption, classifier denial, then the tool's own result
 *       component, then the generic fallback.</li>
 *   <li>{@code src/tools/GrepTool/UI.tsx}, {@code GlobTool/UI.tsx} — {@code Found N files}
 *       summaries per {@code output_mode}.</li>
 *   <li>{@code src/tools/TaskOutputTool/UI.tsx}, {@code TaskStopTool/UI.tsx},
 *       {@code SkillTool/UI.tsx}, {@code AskUserQuestionTool/UI.tsx},
 *       {@code SendMessageTool/UI.tsx}, {@code EnterWorktreeTool/UI.tsx},
 *       {@code ExitWorktreeTool/UI.tsx}, {@code LSPTool/UI.tsx}, {@code WebFetchTool/UI.tsx},
 *       {@code WebSearchTool/UI.tsx}, {@code EnterPlanModeTool/UI.tsx},
 *       {@code ExitPlanModeTool/UI.tsx}, {@code CronCreateTool/UI.tsx},
 *       {@code CronDeleteTool/UI.tsx}, {@code CronListTool/UI.tsx} — the corresponding
 *       {@code renderToolResultMessage} bodies.</li>
 *   <li>{@code src/tools/MCPTool/UI.tsx} — MCP result blocks, the large-response warning,
 *       the Slack {@code message_link} shortcut and key/value rendering of small JSON
 *       objects.</li>
 *   <li>{@code src/components/messages/ExitPlanModeRejected.tsx} — rejected plan bodies
 *       and the remembered/persisted plan fallback.</li>
 *   <li>{@code src/tools/FileReadTool/UI.tsx} — only the dispatch to it; the row itself is
 *       {@link ReadResultRenderer}.</li>
 * </ul>
 */
final class ToolResultRenderer {

    /** Where a result body goes: the completed card and its replaceable status row (or -1). */
    record Placement(PendingTool pending, int replaceLine) {}

    /** Dispatcher services the result renderers rely on. */
    interface Host {
        boolean verbose();
        boolean transcriptMode();
        String expandHint();
        String expandShortcut();
        /**
         * Completes the shared tool-card state (header colour, pending row) before a body
         * renders and returns where that body should go.
         */
        Placement beginToolResult(ToolResultBlock result, MessagePanel panel);
        /** Transcript view: replays the sub-agent conversation; false when nothing was drawn. */
        boolean renderVerboseAgentTranscript(String toolUseId, JsonNode result, MessagePanel panel);
        /** Plan text remembered on disk for an ExitPlanMode rejection without an inline plan. */
        String persistedPlan();
    }

    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();
    private static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;

    private final Host host;
    private final PendingToolLedger tools;
    private final ToolPresentationSnapshotStore presentationSnapshots;
    private final FileChangeResultRenderer fileChanges;

    ToolResultRenderer(Host host, PendingToolLedger tools,
                       ToolPresentationSnapshotStore presentationSnapshots) {
        this.host = host;
        this.tools = tools;
        this.presentationSnapshots = presentationSnapshots;
        this.fileChanges = new FileChangeResultRenderer(host, tools);
    }

    /**
     * Streamed ({@code tool_result_*}) text body under a header that is already complete;
     * mirrors {@link #renderGeneric} for the rejection/interruption/denial/error branches.
     */
    void renderStreamedText(String text, boolean isError, MessagePanel panel) {
        String prefixedPlan = isError ? planFromRejectionResult(text) : null;
        if (prefixedPlan != null) {
            panel.appendMixed(List.of(new MessagePanel.Segment(
                INDENT_PREFIX + "User rejected Claude's plan:", LanternaTheme.welcomeDim())));
            panel.appendMarkdown(prefixedPlan, MARKDOWN_RENDERER, false);
            return;
        }
        // UserToolResultMessage catches REJECT_MESSAGE before its generic error
        // branch and delegates to the tool's rejection renderer. Bash has no
        // custom renderer, so FallbackToolUseRejectedMessage paints InterruptedByUser.
        if (isError) {
            if (Strings.CS.contains(text, MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE)
                    || Strings.CS.startsWith(text, MessageConstants.REJECT_MESSAGE)) {
                renderInterruptedToolResult(panel);
                return;
            }
            if (MessageConstants.isClassifierDenial(text)) {
                renderClassifierDenial(panel);
                return;
            }
            text = fallbackToolErrorText(text);
        } else if (Strings.CS.equals(text, "(Bash completed with no output)")) {
            // BashToolResultMessage uses structured noOutputExpected to render
            // "Done" while preserving the longer model-facing placeholder.
            text = "Done";
        }

        // Keep the complete formatted value in MessagePanel. Its physical rows are
        // projected from the current terminal width, so a single huge JSONL record
        // cannot bypass folding merely because it contains no newline.
        String formatted = ShellOutputFormatter.tryJsonFormatContent(text);
        boolean showAll = host.verbose() || host.transcriptMode();
        TextColor color = isError ? LanternaTheme.toolError() : LanternaTheme.welcomeDim();
        panel.appendToolOutput(formatted, color, showAll);
    }

    /** Structured Edit/Write results with a diff or full content preview. */
    boolean renderStructuredFileChange(Object payload, ToolResultBlock result, MessagePanel panel) {
        return fileChanges.renderStructured(payload, result, panel);
    }

    /** Structured NotebookEdit results with the new cell source. */
    boolean renderStructuredNotebookEdit(Object payload, ToolResultBlock result, MessagePanel panel) {
        return fileChanges.renderNotebookEdit(payload, result, panel);
    }

    static JsonNode parseJsonObject(String json) {
        try {
            return JsonUtils.getMapper().readTree(json);
        } catch (Exception _) {
            return JsonUtils.getMapper().createObjectNode();
        }
    }


    boolean renderRegistered(Object payload, ToolResultBlock result,
                                               MessagePanel panel) {
        ToolInvocation invocation = tools.invocation(result.toolUseId());
        PendingTool pending = tools.find(result.toolUseId());
        String toolName = invocation != null ? invocation.toolName()
            : pending != null ? pending.toolName() : "";
        ToolVisualContractRegistry.ResultMode mode =
            ToolVisualContractRegistry.resultMode(toolName);
        if (mode == ToolVisualContractRegistry.ResultMode.DEFAULT || result.isError()) return false;

        if (mode == ToolVisualContractRegistry.ResultMode.HIDDEN) {
            host.beginToolResult(result, panel);
            tools.forgetInvocation(result.toolUseId());
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SEARCH) {
            renderSearchResult(invocation, result, panel);
            return true;
        }
        if (payload == null) return false;
        JsonNode node = JsonUtils.getMapper().valueToTree(payload);
        if (mode == ToolVisualContractRegistry.ResultMode.READ) {
            // No structured payload (transcript replay of an older session, or a provider
            // that dropped it) — the plain text body is all there is, so fall through.
            if (!ReadResultRenderer.handles(node)) return false;
            Placement placement = host.beginToolResult(result, panel);
            tools.forgetInvocation(result.toolUseId());
            ReadResultRenderer.render(node, placement.replaceLine(), panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.TASK_OUTPUT
                && node.has("retrieval_status") && node.has("task")) {
            renderTaskOutputResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SKILL
                && (node.path("success").asBoolean(false)
                    || Strings.CS.equals("forked", node.path("status").asText()))) {
            renderSkillResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ASK_USER_QUESTION
                && node.path("answers").isObject()) {
            renderAskUserQuestionResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.TASK_STOP
                && node.has("command")) {
            renderTaskStopResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SEND_MESSAGE
                && node.has("message")) {
            renderSendMessageResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ENTER_WORKTREE
                && node.has("worktreePath")) {
            renderEnterWorktreeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.EXIT_WORKTREE
                && node.has("action") && node.has("originalCwd")) {
            renderExitWorktreeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.LSP
                && node.has("operation") && node.has("result")) {
            renderLspResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.JSON_OUTPUT) {
            renderJsonOutputResult(toolName, node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.MCP) {
            renderMcpResult(node, result, panel, invocation);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.WEB_FETCH
                && node.has("bytes") && node.has("code")) {
            renderWebFetchResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.WEB_SEARCH
                && node.has("results") && node.has("durationSeconds")) {
            renderWebSearchResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ENTER_PLAN_MODE
                && node.has("message")) {
            renderEnterPlanModeResult(result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.EXIT_PLAN_MODE
                && node.has("plan")) {
            renderExitPlanModeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_CREATE
                && node.has("id") && node.has("humanSchedule")) {
            renderCronCreateResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_DELETE && node.has("id")) {
            renderCronDeleteResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_LIST
                && node.path("jobs").isArray()) {
            renderCronListResult(node, result, panel);
            return true;
        }
        return false;
    }

    private void renderSearchResult(ToolInvocation invocation, ToolResultBlock result,
                                    MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        String content = toolResultText(result);
        JsonNode input = invocation == null ? JsonUtils.getMapper().createObjectNode()
            : parseJsonObject(invocation.inputJson());
        String mode = Strings.CS.equals("Glob", invocation == null ? "" : invocation.toolName())
            ? "files_with_matches" : input.path("output_mode").asText("files_with_matches");
        List<String> lines = new ArrayList<>(content.lines().filter(line -> !StringUtils.isBlank(line)
            && !Strings.CS.startsWith(line.strip(), "(Results are truncated")
            && !Strings.CS.startsWith(line.strip(), "[Showing results with pagination"))
            .toList());
        if (Strings.CS.equalsAny(content.strip(), "No files found", "No matches found")) {
            lines.clear();
        } else if (Strings.CS.equals("files_with_matches", mode) && !lines.isEmpty()
                && Strings.CS.startsWith(lines.getFirst(), "Found ")) {
            lines.removeFirst();
        } else if (Strings.CS.equals("count", mode)) {
            lines.removeIf(line -> Strings.CS.startsWith(line, "Found ")
                && Strings.CS.contains(line, " across "));
        }
        int primary = lines.size();
        int secondary = 0;
        String primaryLabel = Strings.CS.equals("content", mode) ? "lines" : "files";
        if (Strings.CS.equals("count", mode)) {
            primary = 0;
            secondary = lines.size();
            primaryLabel = "matches";
            for (String line : lines) {
                int colon = line.lastIndexOf(':');
                if (colon >= 0) {
                    try {
                        primary += Integer.parseInt(line.substring(colon + 1).strip());
                    } catch (NumberFormatException _) {
                        // Keep counting the files even if one adapter returned a non-numeric suffix.
                    }
                }
            }
        }
        List<MessagePanel.Segment> summary = new ArrayList<>();
        summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Found ", TextColor.ANSI.DEFAULT));
        summary.add(new MessagePanel.Segment(Integer.toString(primary), TextColor.ANSI.DEFAULT,
            null, null, Set.of(SGR.BOLD)));
        summary.add(new MessagePanel.Segment(" " + singular(primary, primaryLabel),
            TextColor.ANSI.DEFAULT));
        if (Strings.CS.equals("count", mode)) {
            summary.add(new MessagePanel.Segment(" across ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(secondary), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" " + singular(secondary, "files"),
                TextColor.ANSI.DEFAULT));
        }
        if (!host.verbose() && primary > 0) {
            summary.add(new MessagePanel.Segment(" " + host.expandHint(),
                LanternaTheme.welcomeDim()));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), summary);
        if (host.verbose() && !lines.isEmpty()) {
            for (String line : lines) {
                panel.appendLine(INDENT_CONT + line, TextColor.ANSI.DEFAULT);
            }
        }
    }

    private void renderTaskOutputResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        JsonNode task = output.path("task");
        if (task.isMissingNode() || task.isNull()) {
            appendDimResult(panel, placement.replaceLine(), "No task output available");
            return;
        }
        String type = task.path("task_type").asText("");
        String retrieval = output.path("retrieval_status").asText("");
        String taskOutput = task.path("output").asText("");
        if (Strings.CS.equals("local_bash", type)) {
            String body = StringUtils.isBlank(taskOutput) ? task.path("error").asText("Done") : taskOutput;
            panel.updateToolOutputOrAppend(placement.replaceLine(), body,
                LanternaTheme.welcomeDim(), host.verbose() || host.transcriptMode());
            return;
        }
        if (Strings.CS.equals("local_agent", type)) {
            if (Strings.CS.equals("success", retrieval)) {
                if (!host.verbose() && !host.transcriptMode()) {
                    appendDimResult(panel, placement.replaceLine(),
                        "Read output " + host.expandHint());
                    return;
                }
                String response = task.path("result").asText(taskOutput);
                int lines = StringUtils.isBlank(response) ? 0 : response.split("\\n", -1).length;
                appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                    new MessagePanel.Segment(INDENT_PREFIX + task.path("description").asText("")
                        + " (" + lines + " lines)", TextColor.ANSI.DEFAULT)));
                String prompt = task.path("prompt").asText("");
                if (!StringUtils.isBlank(prompt)) panel.appendMarkdown(prompt, MARKDOWN_RENDERER, false);
                if (!StringUtils.isBlank(response)) panel.appendMarkdown(response, MARKDOWN_RENDERER, false);
                String error = task.path("error").asText("");
                if (!StringUtils.isBlank(error)) panel.appendLine("Error: " + error, LanternaTheme.toolError());
                return;
            }
            if (Strings.CS.equalsAny(retrieval, "timeout", "not_ready")
                    || Strings.CS.equals("running", task.path("status").asText())) {
                appendDimResult(panel, placement.replaceLine(), "Task is still running…");
            } else {
                appendDimResult(panel, placement.replaceLine(), "Task not ready");
            }
            return;
        }
        String description = task.path("description").asText("");
        String status = task.path("status").asText("");
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + description + " [" + status + "]",
                TextColor.ANSI.DEFAULT)));
        if (Strings.CS.equals("remote_agent", type)) {
            if (host.verbose() && !StringUtils.isBlank(taskOutput)) {
                panel.appendLine(INDENT_CONT + taskOutput, TextColor.ANSI.DEFAULT);
            } else if (!StringUtils.isBlank(taskOutput)) {
                panel.appendLine(INDENT_CONT + host.expandHint(), LanternaTheme.welcomeDim());
            }
        } else if (!StringUtils.isBlank(taskOutput)) {
            panel.appendLine(INDENT_CONT + FormatUtils.truncate(taskOutput, 500),
                TextColor.ANSI.DEFAULT);
        }
    }

    private void renderSkillResult(JsonNode output, ToolResultBlock result, MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        if (Strings.CS.equals("forked", output.path("status").asText())) {
            appendDimResult(panel, placement.replaceLine(), "Done");
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add("Successfully loaded skill");
        JsonNode allowed = output.path("allowedTools");
        if (allowed.isArray() && !allowed.isEmpty()) {
            parts.add(allowed.size() + " " + singular(allowed.size(), "tools") + " allowed");
        }
        String model = output.path("model").asText("");
        if (!StringUtils.isBlank(model)) parts.add(model);
        appendDimResult(panel, placement.replaceLine(), String.join(" · ", parts));
    }

    private void renderAskUserQuestionResult(JsonNode output, ToolResultBlock result,
                                             MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        int replaceLine = placement.replaceLine();
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.permission()),
            new MessagePanel.Segment("User answered Claude's questions:",
                TextColor.ANSI.DEFAULT)));
        output.path("answers").fields().forEachRemaining(entry -> panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "· " + entry.getKey() + " → "
                + entry.getValue().asText(), LanternaTheme.welcomeDim()))));
    }

    private void renderTaskStopResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        String raw = output.path("command").asText("");
        String command = raw;
        if (!host.verbose() && !host.transcriptMode()) {
            String[] lines = raw.split("\\n", -1);
            if (lines.length > 2) command = String.join("\n", Arrays.copyOf(lines, 2));
            if (command.length() > 160) command = command.substring(0, 160);
            command = command.strip();
        }
        String suffix = Strings.CS.equals(command, raw) ? " · stopped" : "… · stopped";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + command + suffix, TextColor.ANSI.DEFAULT)));
    }

    private void renderSendMessageResult(JsonNode output, ToolResultBlock result,
                                         MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        if ((output.has("routing") && !output.path("routing").isNull()
                && (!output.path("routing").isBoolean() || output.path("routing").asBoolean()))
                || (output.has("request_id") && output.has("target"))) {
            return;
        }
        appendDimResult(panel, placement.replaceLine(), output.path("message").asText(""));
    }

    private void renderEnterWorktreeResult(JsonNode output, ToolResultBlock result,
                                           MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX + "Switched to worktree on branch ",
            TextColor.ANSI.DEFAULT));
        line.add(new MessagePanel.Segment(output.path("worktreeBranch").asText(""),
            TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        panel.appendLine(INDENT_CONT + output.path("worktreePath").asText(""),
            LanternaTheme.welcomeDim());
    }

    private void renderExitWorktreeResult(JsonNode output, ToolResultBlock result,
                                          MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        String action = output.path("action").asText("");
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX
            + (Strings.CS.equals("keep", action) ? "Kept worktree" : "Removed worktree"),
            TextColor.ANSI.DEFAULT));
        String branch = output.path("worktreeBranch").asText("");
        if (!StringUtils.isBlank(branch)) {
            line.add(new MessagePanel.Segment(" (branch ", TextColor.ANSI.DEFAULT));
            line.add(new MessagePanel.Segment(branch, TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            line.add(new MessagePanel.Segment(")", TextColor.ANSI.DEFAULT));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        panel.appendLine(INDENT_CONT + "Returned to " + output.path("originalCwd").asText(""),
            LanternaTheme.welcomeDim());
    }

    private void renderLspResult(JsonNode output, ToolResultBlock result,
                                 MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        if (!output.has("resultCount") || !output.has("fileCount")) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + output.path("result").asText(""),
                    TextColor.ANSI.DEFAULT)));
            return;
        }
        int count = output.path("resultCount").asInt();
        int files = output.path("fileCount").asInt();
        String operation = output.path("operation").asText("");
        String singular = switch (operation) {
            case "goToDefinition" -> "definition";
            case "findReferences" -> "reference";
            case "documentSymbol", "workspaceSymbol" -> "symbol";
            case "hover" -> "hover info";
            case "goToImplementation" -> "implementation";
            case "prepareCallHierarchy" -> "call item";
            case "incomingCalls" -> "caller";
            case "outgoingCalls" -> "callee";
            default -> "result";
        };
        String plural = switch (singular) {
            case "hover info" -> singular;
            case "call item" -> "call items";
            default -> singular + "s";
        };
        List<MessagePanel.Segment> summary = new ArrayList<>();
        if (Strings.CS.equals("hover", operation) && count > 0) {
            summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Hover info available",
                TextColor.ANSI.DEFAULT));
        } else {
            summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Found ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(count), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" " + (count == 1 ? singular : plural),
                TextColor.ANSI.DEFAULT));
        }
        if (files > 1) {
            summary.add(new MessagePanel.Segment(" across ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(files), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" files", TextColor.ANSI.DEFAULT));
        }
        if (!host.verbose() && count > 0) {
            summary.add(new MessagePanel.Segment(" " + host.expandHint(),
                LanternaTheme.welcomeDim()));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), summary);
        if (host.verbose()) {
            String content = output.path("result").asText("");
            if (!StringUtils.isBlank(content)) panel.appendLine(INDENT_CONT + content, TextColor.ANSI.DEFAULT);
        }
    }

    private void renderJsonOutputResult(String toolName, JsonNode output,
                                        ToolResultBlock result, MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        if (Strings.CS.equals("ListMcpResourcesTool", toolName)
                && (!output.isArray() || output.isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No resources found)");
            return;
        }
        if (Strings.CS.equals("ReadMcpResourceTool", toolName)
                && (!output.path("contents").isArray() || output.path("contents").isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        panel.updateToolOutputOrAppend(placement.replaceLine(), output.toPrettyString(),
            TextColor.ANSI.DEFAULT, host.verbose() || host.transcriptMode());
    }

    private void renderMcpResult(JsonNode output, ToolResultBlock result,
                                 MessagePanel panel, ToolInvocation invocation) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        SlackSend slack = !host.verbose() ? slackSend(output, invocation) : null;
        if (slack != null) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "Sent a message to ",
                    TextColor.ANSI.DEFAULT),
                MessagePanel.Segment.hyperlink(slack.channel(), TextColor.ANSI.DEFAULT,
                    slack.url())));
            return;
        }
        long estimate = estimateMcpTokens(output);
        if (estimate > 10_000) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "⚠ Large MCP response (~"
                    + FormatUtils.formatNumber(estimate)
                    + " tokens), this can fill up context quickly", LanternaTheme.statusCost())));
            placement = new Placement(placement.pending(), -1);
        }
        if (output == null || output.isNull() || (output.isTextual() && output.asText().isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        if (output.isArray()) {
            boolean first = true;
            for (JsonNode block : output) {
                int replace = first ? placement.replaceLine() : -1;
                if (Strings.CS.equals("image", block.path("type").asText())) {
                    appendOrReplaceToolResultLine(panel, replace, List.of(
                        new MessagePanel.Segment(INDENT_PREFIX + "[Image]", TextColor.ANSI.DEFAULT)));
                } else {
                    String text = block.path("text").asText("");
                    panel.updateToolOutputOrAppend(replace, text, TextColor.ANSI.DEFAULT,
                        host.verbose() || host.transcriptMode());
                }
                first = false;
            }
            if (first) appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        String content = output.isTextual() ? output.asText() : output.toPrettyString();
        if (renderRichMcpText(content, placement.replaceLine(), panel)) return;
        panel.updateToolOutputOrAppend(placement.replaceLine(), content, TextColor.ANSI.DEFAULT,
            host.verbose() || host.transcriptMode());
    }

    private boolean renderRichMcpText(String content, int replaceLine, MessagePanel panel) {
        if (content == null || content.length() > 200_000
            || !Strings.CS.startsWith(content.stripLeading(), "{")) {
            return false;
        }
        JsonNode parsed = JsonUtils.safeParseJson(content);
        if (parsed == null || !parsed.isObject() || parsed.isEmpty()) return false;
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        parsed.fields().forEachRemaining(entries::add);
        if (entries.size() <= 4) {
            Map.Entry<String, JsonNode> dominant = null;
            List<String> extras = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : entries) {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String text = value.asText().stripTrailing();
                    boolean large = text.length() > 200
                        || (text.length() > 50 && Strings.CS.contains(text, "\n"));
                    if (large) {
                        if (dominant != null) return false;
                        dominant = entry;
                    } else if (text.length() <= 150) {
                        extras.add(entry.getKey() + ": " + text.replaceAll("\\s+", " "));
                    } else return false;
                } else if (value.isValueNode()) {
                    extras.add(entry.getKey() + ": " + value.asText());
                } else return false;
            }
            if (dominant != null) {
                if (!extras.isEmpty()) appendDimResult(panel, replaceLine, String.join(" · ", extras));
                panel.updateToolOutputOrAppend(extras.isEmpty() ? replaceLine : -1,
                    dominant.getValue().asText().stripTrailing(), TextColor.ANSI.DEFAULT,
                    host.verbose() || host.transcriptMode());
                return true;
            }
        }
        if (content.length() > 5_000 || entries.size() > 12) return false;
        List<String> rows = new ArrayList<>();
        int maxKey = entries.stream().mapToInt(entry -> entry.getKey().length()).max().orElse(0);
        for (Map.Entry<String, JsonNode> entry : entries) {
            JsonNode value = entry.getValue();
            String display = value.isTextual() ? value.asText()
                : value.isValueNode() ? value.asText() : value.toString();
            if (!value.isValueNode() && display.length() > 120) return false;
            rows.add(entry.getKey() + " ".repeat(maxKey - entry.getKey().length()) + ": " + display);
        }
        panel.updateToolOutputOrAppend(replaceLine, String.join("\n", rows),
            TextColor.ANSI.DEFAULT, true);
        return true;
    }

    private static SlackSend slackSend(JsonNode output, ToolInvocation invocation) {
        String text = null;
        if (output != null && output.isTextual()) text = output.asText();
        if (output != null && output.isArray()) {
            for (JsonNode block : output) {
                if (Strings.CS.equals("text", block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
        }
        if (text == null || !Strings.CS.contains(text, "\"message_link\"")) return null;
        JsonNode parsed = JsonUtils.safeParseJson(text);
        String url = parsed == null ? "" : parsed.path("message_link").asText("");
        Matcher matcher = Pattern.compile(
            "^https://[a-z0-9-]+\\.slack\\.com/archives/([A-Z0-9]+)/p\\d+$").matcher(url);
        if (!matcher.matches()) return null;
        JsonNode input = invocation == null ? null : parseJsonObject(invocation.inputJson());
        String channel = input == null ? "" : input.path("channel_id").asText("");
        if (StringUtils.isBlank(channel) && input != null) channel = input.path("channel").asText("");
        if (StringUtils.isBlank(channel)) channel = matcher.group(1);
        if (!Strings.CS.startsWith(channel, "#")) channel = "#" + channel;
        return new SlackSend(channel, url);
    }

    private record SlackSend(String channel, String url) {}

    private static long estimateMcpTokens(JsonNode output) {
        if (output == null || output.isNull()) return 0L;
        if (output.isArray()) {
            long total = 0L;
            for (JsonNode block : output) {
                total += Strings.CS.equals("image", block.path("type").asText())
                    ? 1600L : Math.max(1L, block.path("text").asText("").length() / 4L);
            }
            return total;
        }
        String text = output.isTextual() ? output.asText() : output.toString();
        return Math.max(1L, text.length() / 4L);
    }

    private void renderWebFetchResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX + "Received ", TextColor.ANSI.DEFAULT));
        line.add(new MessagePanel.Segment(FormatUtils.formatFileSize(output.path("bytes").asLong()),
            TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        line.add(new MessagePanel.Segment(" (" + output.path("code").asInt() + " "
            + output.path("codeText").asText("") + ")", TextColor.ANSI.DEFAULT));
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        if (host.verbose()) {
            String body = output.path("result").asText("");
            if (!StringUtils.isBlank(body)) panel.appendLine(body, TextColor.ANSI.DEFAULT);
        }
    }

    private void renderWebSearchResult(JsonNode output, ToolResultBlock result,
                                       MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        int searches = 0;
        for (JsonNode item : output.path("results")) {
            if (item != null && item.isObject()) searches++;
        }
        double seconds = output.path("durationSeconds").asDouble();
        String duration = seconds >= 1
            ? Math.round(seconds) + "s" : Math.round(seconds * 1000) + "ms";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Did " + searches + " search"
                + (searches == 1 ? "" : "es") + " in " + duration,
                TextColor.ANSI.DEFAULT)));
    }

    private void renderEnterPlanModeResult(ToolResultBlock result, MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.modePlan()),
            new MessagePanel.Segment("Entered plan mode", TextColor.ANSI.DEFAULT)));
        panel.appendLine("  Claude is now exploring and designing an implementation approach.",
            LanternaTheme.welcomeDim());
    }

    private void renderExitPlanModeResult(JsonNode output, ToolResultBlock result,
                                          MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        String plan = output.path("plan").isNull() ? null : output.path("plan").asText(null);
        boolean empty = StringUtils.isBlank(plan);
        boolean awaiting = output.path("awaitingLeaderApproval").asBoolean(false);
        String filePath = output.path("filePath").asText("");
        String title = empty ? "Exited plan mode"
            : awaiting ? "Plan submitted for team lead approval"
            : "User approved Claude's plan";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.modePlan()),
            new MessagePanel.Segment(title, TextColor.ANSI.DEFAULT)));
        if (empty) return;
        String shownPath = displayPath(filePath);
        if (awaiting) {
            if (StringUtils.isNotBlank(filePath)) {
                panel.appendLine(INDENT_CONT + "Plan file: " + shownPath,
                    LanternaTheme.welcomeDim());
            }
            panel.appendLine(INDENT_CONT + "Waiting for team lead to review and approve...",
                LanternaTheme.welcomeDim());
            return;
        }
        if (StringUtils.isNotBlank(filePath)) {
            panel.appendLine(INDENT_CONT + "Plan saved to: " + shownPath + " · /plan to edit",
                LanternaTheme.welcomeDim());
        }
        panel.appendMarkdown(plan, MARKDOWN_RENDERER, false);
    }

    private void renderCronCreateResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Scheduled ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(output.path("id").asText(""), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(" (" + output.path("humanSchedule").asText("") + ")",
                LanternaTheme.welcomeDim())));
    }

    private void renderCronDeleteResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Cancelled ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(output.path("id").asText(""), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))));
    }

    private void renderCronListResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        Placement placement = host.beginToolResult(result, panel);
        tools.forgetInvocation(result.toolUseId());
        JsonNode jobs = output.path("jobs");
        if (jobs.isEmpty()) {
            appendDimResult(panel, placement.replaceLine(), "No scheduled jobs");
            return;
        }
        boolean first = true;
        for (JsonNode job : jobs) {
            List<MessagePanel.Segment> line = List.of(
                new MessagePanel.Segment(INDENT_PREFIX, TextColor.ANSI.DEFAULT),
                new MessagePanel.Segment(job.path("id").asText(""), TextColor.ANSI.DEFAULT,
                    null, null, Set.of(SGR.BOLD)),
                new MessagePanel.Segment(" " + job.path("humanSchedule").asText(""),
                    LanternaTheme.welcomeDim()));
            if (first) {
                appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
                first = false;
            } else {
                panel.appendMixed(line);
            }
        }
    }

    void renderGeneric(ToolResultBlock result, MessagePanel panel) {
        String toolUseId = result.toolUseId();
        ToolPresentationSnapshotStore.Snapshot presentation =
            presentationSnapshots.consume(toolUseId);
        String rememberedPlan = presentation.plan();
        RejectedFileChangePreview rejectedFilePreview = presentation.filePreview();
        ToolInvocation resultInvocation = tools.invocation(result.toolUseId());
        PendingTool pendingResult = tools.find(result.toolUseId());
        String resultToolName = resultInvocation != null ? resultInvocation.toolName()
            : pendingResult != null ? pendingResult.toolName() : "";
        if (!result.isError() && ToolVisualContractRegistry.resultMode(resultToolName)
                == ToolVisualContractRegistry.ResultMode.HIDDEN) {
            host.beginToolResult(result, panel);
            tools.forgetInvocation(result.toolUseId());
            return;
        }
        Placement placement = host.beginToolResult(result, panel);
        PendingTool pending = placement.pending();
        int replaceLine = placement.replaceLine();
        if (host.transcriptMode() && Strings.CS.equals("Agent", resultToolName)) {
            host.renderVerboseAgentTranscript(result.toolUseId(), null, panel);
        }
        // Extract textual content (most tools produce text blocks)
        StringBuilder body = new StringBuilder();
        if (result.content() != null) {
            for (ContentBlock cb : result.content()) {
                if (cb instanceof TextBlock(String text)) body.append(text);
            }
        }
        if (body.isEmpty()) {
            String marker = result.isError() ? "(error)" : "(no output)";
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(INDENT_PREFIX,  LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(marker,    LanternaTheme.welcomeDim())
            ));
            return;
        }

        String bodyText = body.toString();
        String prefixedPlan = planFromRejectionResult(bodyText);
        if (result.isError() && prefixedPlan != null) {
            renderRejectedPlan(panel, replaceLine, prefixedPlan);
            return;
        }
        if (result.isError() && Strings.CS.equals("EnterPlanMode", resultToolName)
                && Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(BLACK_CIRCLE,
                    LanternaTheme.colorFor(PermissionMode.DEFAULT)),
                new MessagePanel.Segment("User declined to enter plan mode",
                    TextColor.ANSI.DEFAULT)));
            return;
        }
        boolean exitPlanRejection = result.isError()
            && Strings.CS.equals("ExitPlanMode", resultToolName)
            && (rememberedPlan != null
                || Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)
                || Strings.CS.startsWith(
                    bodyText, MessageConstants.REJECT_MESSAGE_WITH_REASON_PREFIX));
        if (exitPlanRejection) {
            String plan = rememberedPlan != null ? rememberedPlan : host.persistedPlan();
            renderRejectedPlan(panel, replaceLine, plan != null ? plan : "No plan found");
            return;
        }
        if (result.isError()
                && (Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)
                    || Strings.CS.contains(bodyText,
                        MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE))) {
            ToolInvocation invocation = result.toolUseId() == null ? null
                : tools.forgetInvocation(result.toolUseId());
            if (invocation == null && pending != null && pending.inputJson() != null
                    && !StringUtils.isBlank(pending.inputJson())) {
                invocation = new ToolInvocation(pending.toolName(), pending.inputJson());
            }
            if (fileChanges.renderRejected(
                    rejectedFilePreview, invocation, panel, replaceLine)) return;
            renderInterruptedToolResult(panel, replaceLine);
            return;
        }
        if (result.isError() && MessageConstants.isClassifierDenial(bodyText)) {
            renderClassifierDenial(panel, replaceLine);
            return;
        }
        if (result.isError()) {
            bodyText = fallbackToolErrorText(bodyText);
        } else if (Strings.CS.equals(bodyText, "(Bash completed with no output)")) {
            bodyText = "Done";
        }

        String formatted = ShellOutputFormatter.tryJsonFormatContent(bodyText);
        TextColor color = result.isError() ? LanternaTheme.toolError() : LanternaTheme.welcomeDim();
        panel.updateToolOutputOrAppend(replaceLine, formatted, color, host.verbose() || host.transcriptMode());
    }
}
