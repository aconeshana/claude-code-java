package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.runtime.session.MessagesDeserializer;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionStorage;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.SGR;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.tools.plan.PlanFiles;
import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end replay pipeline for {@code /resume}: JSONL → SessionStorage →
 * MessagesDeserializer → SessionController.replayLoadedMessages → panel rows.
 *
 * <p>Regression for the "N messages loaded but transcript stays blank" bug:
 * text-shaped user rows ({@code {"message":{"text":...}}} — the shape every
 * plain prompt is persisted with) were dropped by renderUser's blocks-only
 * path, meta rows were never filtered, and the trailing
 * "[Request interrupted by user]" row rendered nothing.
 */
class ResumeReplayRenderingTest {

    private static final String BLACK_CIRCLE =
        Strings.CI.contains(System.getProperty("os.name", ""), "mac") ? "⏺ " : "● ";

    /** Captures every rendered row as plain text. */
    static class StubPanel extends MessagePanel {
        final List<String> lines = new ArrayList<>();
        final List<List<MessagePanel.Segment>> segmentLines = new ArrayList<>();

        @Override
        public void appendMixed(List<MessagePanel.Segment> segments) {
            segmentLines.add(List.copyOf(segments));
            StringBuilder sb = new StringBuilder();
            for (var s : segments) sb.append(s.text());
            lines.add(sb.toString());
        }

        @Override
        public void appendLine(String text, TextColor color) {
            lines.add(text);
        }

        @Override
        public void appendMarkdown(String markdown, MarkdownRenderer renderer, boolean showBullet) {
            var rendered = renderer.render(markdown, 78);
            var parsed = AnsiToSegments.ansiToLines(rendered, TextColor.ANSI.DEFAULT);
            for (int i = 0; i < parsed.size(); i++) {
                StringBuilder line = new StringBuilder(i == 0
                    ? showBullet ? BLACK_CIRCLE : "  "
                    : "  ");
                for (var segment : parsed.get(i)) line.append(segment.text());
                lines.add(line.toString());
            }
        }

        @Override
        public int snapshotLineCount() {
            return lines.size();
        }

        @Override
        public void updateLine(int index, List<MessagePanel.Segment> segments) {
            if (index < 0 || index >= lines.size()) return;
            StringBuilder sb = new StringBuilder();
            for (var segment : segments) sb.append(segment.text());
            lines.set(index, sb.toString());
        }

        @Override
        public void appendToolOutput(String content, TextColor color, boolean showAll) {
            appendMixed(List.of(
                new MessagePanel.Segment(Figures.RESULT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(content, color)));
        }

        @Override
        public void updateToolOutputOrAppend(int index, String content, TextColor color,
                                             boolean showAll) {
            List<MessagePanel.Segment> segments = List.of(
                new MessagePanel.Segment(Figures.RESULT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(content, color));
            if (index >= 0 && index < lines.size()) updateLine(index, segments);
            else appendMixed(segments);
        }
    }

    /** Real newsnow session shape: a plain text-shaped prompt + an interrupt row. */
    private static final String LINE_PROMPT = """
        {"type":"user","uuid":"8acc56e0-e174-4835-9b18-cc3e47e8b0d0","message":{"text":"## Context\\n\\nBased on the above changes, create a single git commit."},"isMeta":false,"isCompactSummary":false,"origin":"USER","timestamp":"2026-07-10T08:45:17.865570Z","permissionMode":"default","sessionId":"d71c64a8","cwd":"/tmp","userType":"external","entrypoint":"cli","version":"1.0.0","isSidechain":false}""";
    private static final String LINE_INTERRUPT = """
        {"type":"user","uuid":"38358868-1f5b-4c28-9f01-1382fa270da5","message":{"blocks":[{"type":"text","text":"[Request interrupted by user]"}]},"isMeta":false,"isCompactSummary":false,"origin":"USER","timestamp":"2026-07-10T08:45:20.774321Z","sessionId":"d71c64a8","cwd":"/tmp","userType":"external","entrypoint":"cli","version":"1.0.0","isSidechain":false}""";

    private static SessionController replayController(StubPanel panel) {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessageCollapser collapser = new MessageCollapser(dispatcher, false);
        return new SessionController(
            null, null, null, null, panel,
            new MessageHistory(), collapser, null, null, () -> null, null,
            null, null, null, null);
    }

    @Test
    void replayRendersTextShapedPromptAndInterruptRow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.jsonl");
        Files.writeString(file, LINE_PROMPT + "\n" + LINE_INTERRUPT + "\n");

        List<Message> raw = new SessionStorage().readMessages(file);
        assertEquals(2, raw.size(), "both JSONL rows must deserialize");
        List<Message> msgs = MessagesDeserializer.deserialize(raw);
        assertEquals(3, msgs.size(), "sentinel assistant appended after trailing user");

        StubPanel panel = new StubPanel();
        replayController(panel).replayLoadedMessages(msgs);

        String all = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(all, "## Context"),
            "text-shaped user prompt must render, got:\n" + all);
        assertTrue(Strings.CS.contains(all, "Interrupted"),
            "interrupt row must render as the InterruptedByUser line, got:\n" + all);
        assertFalse(Strings.CS.contains(all, "[Request interrupted by user]"),
            "raw interrupt sentinel text must not leak into the transcript");
        assertFalse(Strings.CS.contains(all, "No response requested."),
            "synthetic sentinel assistant must stay hidden");
    }

    @Test
    void replaySkipsMetaUserMessages(@TempDir Path dir) throws Exception {
        String metaLine = LINE_PROMPT.replace("\"isMeta\":false", "\"isMeta\":true");
        Path file = dir.resolve("session.jsonl");
        Files.writeString(file, metaLine + "\n");

        List<Message> msgs = MessagesDeserializer.deserialize(
            new SessionStorage().readMessages(file));

        StubPanel panel = new StubPanel();
        replayController(panel).replayLoadedMessages(msgs);

        String all = String.join("\n", panel.lines);
        assertFalse(Strings.CS.contains(all, "## Context"),
            "isMeta user messages are model-facing only and must not render");
    }

    @Test
    void resumeReplayLoadsTheAgentSidechainIntoCtrlOHistory() {
        var agentInput = JsonUtils.getMapper().createObjectNode();
        agentInput.put("description", "inspect replay");
        agentInput.put("prompt", "Inspect replay");
        agentInput.put("subagent_type", "Explore");
        AssistantMessage use = new AssistantMessage("agent-use",
            AssistantContent.of(List.of(
                new ToolUseBlock("agent-call", "Agent", agentInput))));
        UserMessage result = new UserMessage("agent-result",
            MessageContent.ofToolResult("agent-call",
                List.of(new TextBlock("finished")), false), false, false,
            Map.of(
                "status", "completed", "prompt", "Inspect replay",
                "agentId", "agent-123", "agentType", "Explore",
                "content", List.of()),
            MessageOrigin.USER, null, Instant.now(), null, null);
        AssistantMessage child = new AssistantMessage("child-assistant",
            AssistantContent.of(List.of(new TextBlock("sidechain detail"))));
        MessageHistory history = new MessageHistory();
        StubPanel panel = new StubPanel();
        InteractiveSessionPort sessions = new InteractiveSessionPort() {
            @Override public Path agentTranscriptPath(
                    String cwd, String sessionId, String agentId) {
                assertEquals("/project", cwd);
                assertEquals("session-1", sessionId);
                assertEquals("agent-123", agentId);
                return Path.of("/project/session-1/subagents/agent-agent-123.jsonl");
            }

            @Override public List<Message> readAgentSidechainMessages(
                    Path transcript, String agentId) {
                return List.of(
                    new UserMessage("child-prompt", MessageContent.ofText("Inspect replay")),
                    child);
            }
        };
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        SessionController controller = new SessionController(
            null, null, null, null, panel, history,
            new MessageCollapser(dispatcher, false), null, null, () -> null, null,
            null, null, null, null, null, null, null, sessions, null);

        controller.replayLoadedMessages(List.of(use, result), "/project", "session-1");

        SDKMessage.Progress progress = assertInstanceOf(SDKMessage.Progress.class,
            history.events().stream().filter(SDKMessage.Progress.class::isInstance)
                .findFirst().orElseThrow());
        assertEquals("agent-call", progress.message().toolUseId());
        assertSame(child, progress.message().data().message());
    }

    @Test
    void rejectedToolResultRendersReleasedInterruptedPromptInsteadOfModelFacingText() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        UserMessage rejected = new UserMessage("reject-user",
            MessageContent.ofToolResult("toolu_reject",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true));

        dispatcher.dispatch(new SDKMessage.User(rejected), panel);

        assertEquals(List.of(Figures.RESULT_PREFIX
            + "Interrupted · What should Claude do instead?"), panel.lines);
        assertFalse(Strings.CS.contains(String.join("\n", panel.lines), MessageConstants.REJECT_MESSAGE),
            "the model-facing rejection text must stay in JSONL but not leak into the TTY");
    }

    @Test
    void rejectedEditRendersToolSpecificDiffDuringReplay(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.yml");
        Files.writeString(file, "enabled: false\n");
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        ToolUseBlock use = new ToolUseBlock("toolu_reject_edit", "Edit",
            JsonUtils.getMapper().readTree("{\"file_path\":\"" + file
                + "\",\"old_string\":\"enabled: false\",\"new_string\":\"enabled: true\"}"));
        dispatcher.dispatch(new SDKMessage.Assistant(
            new AssistantMessage("assistant-edit", AssistantContent.of(List.of(use))), null), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("reject-edit",
            MessageContent.ofToolResult("toolu_reject_edit",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "User rejected update to "), rendered);
        assertTrue(Strings.CS.contains(rendered, "settings.yml"), rendered);
        assertTrue(Strings.CS.contains(rendered, "- enabled: false"), rendered);
        assertTrue(Strings.CS.contains(rendered, "+ enabled: true"), rendered);
        assertFalse(Strings.CS.contains(rendered, "What should Claude do instead?"), rendered);
    }

    @Test
    void rejectedEditDiffUsesDimmedPalette(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dimmed.yml");
        Files.writeString(file, "enabled: false\n");
        StructuredPatchHunk hunk = new StructuredPatchHunk(
            1, 1, 1, 1, List.of("-enabled: false", "+enabled: true"));

        StubPanel successPanel = new StubPanel();
        UserMessage success = new UserMessage(
            "success-dim-user",
            MessageContent.ofToolResult("toolu_success_dim", List.of(new TextBlock("ok")), false),
            false, false,
            FileChangeResult.edited(file.toString(), "enabled: false", "enabled: true",
                "enabled: false\n", List.of(hunk), false, false),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-success-dim");
        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(success), successPanel);

        LanternaMessageDispatcher rejectedDispatcher = new LanternaMessageDispatcher();
        StubPanel rejectedPanel = new StubPanel();
        ToolUseBlock use = new ToolUseBlock("toolu_reject_dim", "Edit",
            JsonUtils.getMapper().createObjectNode()
                .put("file_path", file.toString())
                .put("old_string", "enabled: false")
                .put("new_string", "enabled: true"));
        rejectedDispatcher.dispatch(new SDKMessage.Assistant(
            new AssistantMessage("assistant-reject-dim", AssistantContent.of(List.of(use))), null),
            rejectedPanel);
        rejectedDispatcher.dispatch(new SDKMessage.User(new UserMessage("reject-dim-user",
            MessageContent.ofToolResult("toolu_reject_dim",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), rejectedPanel);

        assertNotEquals(diffBackground(successPanel, "enabled: false"),
            diffBackground(rejectedPanel, "enabled: false"),
            "released rejection rendering passes dim=true to StructuredDiffList");
    }

    @Test
    void rejectedWriteUsesLiveToolInputAndShowsCreatePreview(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("new-config.yml");
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        String input = "{\"file_path\":\"" + file
            + "\",\"content\":\"enabled: true\\nmode: fast\\n\"}";
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Write|toolu_reject_write"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Write|toolu_reject_write|" + input), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("reject-write",
            MessageContent.ofToolResult("toolu_reject_write",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "User rejected write to "), rendered);
        assertTrue(Strings.CS.contains(rendered, "new-config.yml"), rendered);
        assertTrue(Strings.CS.contains(rendered, "enabled: true"), rendered);
        assertTrue(Strings.CS.contains(rendered, "mode: fast"), rendered);
        assertFalse(Strings.CS.contains(rendered, "What should Claude do instead?"), rendered);
    }

    @Test
    void rejectedNotebookEditShowsCellSourceInsteadOfGenericInterrupted(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("analysis.ipynb");
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        ToolUseBlock use = new ToolUseBlock("toolu_reject_notebook", "NotebookEdit",
            JsonUtils.getMapper().createObjectNode()
                .put("notebook_path", file.toString())
                .put("cell_id", "cell-0")
                .put("new_source", "print(2)")
                .put("cell_type", "code")
                .put("edit_mode", "replace"));
        dispatcher.dispatch(new SDKMessage.Assistant(
            new AssistantMessage("assistant-notebook", AssistantContent.of(List.of(use))), null), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("reject-notebook",
            MessageContent.ofToolResult("toolu_reject_notebook",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true))), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "User rejected replace cell in "), rendered);
        assertTrue(Strings.CS.contains(rendered, "analysis.ipynb at cell cell-0"), rendered);
        assertTrue(Strings.CS.contains(rendered, "print(2)"), rendered);
        assertFalse(Strings.CS.contains(rendered, "What should Claude do instead?"), rendered);
    }

    @Test
    void notebookStructuredSuccessShowsUpdatedCellAndSource() {
        Map<String, Object> output = Map.of(
            "new_source", "print(2)\n",
            "cell_id", "cell-0",
            "cell_type", "code",
            "language", "python",
            "edit_mode", "replace",
            "error", "",
            "notebook_path", "/tmp/analysis.ipynb",
            "original_file", "{}",
            "updated_file", "{}");
        UserMessage result = new UserMessage(
            "notebook-user",
            MessageContent.ofToolResult("toolu_notebook",
                List.of(new TextBlock("Updated cell cell-0 with print(2)")), false),
            false, false, output,
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-notebook");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Updated cell cell-0:"), rendered);
        assertTrue(Strings.CS.contains(rendered, "print(2)"), rendered);
        assertFalse(Strings.CS.contains(rendered, " with print(2)"), rendered);
    }

    @Test
    void genericErrorToolResultGetsReleasedFallbackErrorPrefixOnReplayAndLiveStream() {
        String denial = MessageConstants.dontAskRejectMessage("Bash");

        LanternaMessageDispatcher replayDispatcher = new LanternaMessageDispatcher();
        StubPanel replayPanel = new StubPanel();
        UserMessage rejected = new UserMessage("deny-user",
            MessageContent.ofToolResult("toolu_deny",
                List.of(new TextBlock(denial)), true));
        replayDispatcher.dispatch(new SDKMessage.User(rejected), replayPanel);

        assertTrue(Strings.CS.startsWith(replayPanel.lines.getFirst(),
            Figures.RESULT_PREFIX + "Error: "));

        LanternaMessageDispatcher liveDispatcher = new LanternaMessageDispatcher();
        StubPanel livePanel = new StubPanel();
        liveDispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_error", "Bash|" + denial), livePanel);

        assertTrue(livePanel.lines.stream().anyMatch(
            line -> Strings.CS.startsWith(line, Figures.RESULT_PREFIX + "Error: ")),
            livePanel.lines.toString());
    }

    @Test
    void bashSilentSuccessRendersDoneWithoutLeakingModelPlaceholder() {
        String placeholder = "(Bash completed with no output)";
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Bash|" + placeholder), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, Figures.RESULT_PREFIX + "Done"), rendered);
        assertFalse(Strings.CS.contains(rendered, placeholder), rendered);
    }

    @Test
    void permissionWaitingRowIsReplacedByReleasedDoneResult() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        String input = "{\"command\":\"touch /private/tmp/cc197-default-perm-marker\"}";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|toolu_197_bash_probe"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Bash|toolu_197_bash_probe|" + input), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "permission_waiting", "Bash"), panel);

        assertEquals(List.of(
            BLACK_CIRCLE
                + "Bash(touch /private/tmp/cc197-default-perm-marker)",
            Figures.RESULT_PREFIX + "Waiting…"), panel.lines);

        UserMessage result = new UserMessage("result-user",
            MessageContent.ofToolResult("toolu_197_bash_probe",
                List.of(new TextBlock("(Bash completed with no output)")), false));
        dispatcher.dispatch(new SDKMessage.User(result), panel);

        assertEquals(List.of(
            BLACK_CIRCLE
                + "Bash(touch /private/tmp/cc197-default-perm-marker)",
            Figures.RESULT_PREFIX + "Done"), panel.lines,
            "the transient waiting row must become the result instead of leaving a duplicate row");
    }

    @Test
    void permissionWaitingRowIsReplacedByReleasedInterruptedResult() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        String input = "{\"command\":\"touch /private/tmp/cc197-hot-reload-marker\"}";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|toolu_197_hot_reload"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Bash|toolu_197_hot_reload|" + input), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "permission_waiting", "Bash"), panel);

        UserMessage rejected = new UserMessage("reject-user",
            MessageContent.ofToolResult("toolu_197_hot_reload",
                List.of(new TextBlock(MessageConstants.REJECT_MESSAGE)), true));
        dispatcher.dispatch(new SDKMessage.User(rejected), panel);

        assertEquals(List.of(
            BLACK_CIRCLE
                + "Bash(touch /private/tmp/cc197-hot-reload-marker)",
            Figures.RESULT_PREFIX + "Interrupted · What should Claude do instead?"), panel.lines,
            "the transient waiting row must be replaced for rejection just as it is for success");
    }

    @Test
    void assistantTextAfterToolResultKeepsReleasedBlankSeparator() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Bash|toolu_197_bash_probe"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done",
            "Bash|toolu_197_bash_probe|{\"command\":\"true\"}"), panel);
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("result-user",
            MessageContent.ofToolResult("toolu_197_bash_probe",
                List.of(new TextBlock("(Bash completed with no output)")), false))), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("content_block_delta", "OK"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("content_block_stop", ""), panel);

        assertEquals("", panel.lines.get(2), panel.lines.toString());
        assertEquals(BLACK_CIRCLE + "OK", panel.lines.get(3));
    }

    @Test
    void writeToolHeaderUsesReleasedDisplayPath() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        Path file = Path.of(System.getProperty("user.dir"), "tty-accept-edits-marker.txt");
        String input = "{\"file_path\":\"" + file + "\",\"content\":\"accept-edits-ok\\n\"}";

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Write|toolu_write"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Write|toolu_write|" + input), panel);

        assertEquals("Write(tty-accept-edits-marker.txt)",
            panel.lines.getFirst().replace(BLACK_CIRCLE, ""));
    }

    @Test
    void liveWriteCreateRendersStructuredResultInsteadOfModelFacingText() {
        Path file = Path.of(System.getProperty("user.dir"), "tty-accept-edits-marker.yml");
        String modelFacing = "File created successfully at: " + file
            + " (file state is current in your context — no need to Read it back)";
        UserMessage result = new UserMessage(
            "write-user",
            MessageContent.ofToolResult("toolu_write", List.of(new TextBlock(modelFacing)), false),
            false, false, FileChangeResult.created(file.toString(), "enabled: true\n"),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-write");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered,
            "Wrote 1 lines to tty-accept-edits-marker.yml"), rendered);
        assertTrue(Strings.CS.contains(rendered, "1 enabled: true"), rendered);
        assertFalse(Strings.CS.contains(rendered, modelFacing), rendered);
        List<MessagePanel.Segment> header = panel.segmentLines.getFirst();
        assertTrue(header.stream().anyMatch(segment -> Strings.CS.equals("1", segment.text())
                && segment.modifiers().contains(SGR.BOLD)),
            "released Write header bolds the line count");
        assertTrue(header.stream().anyMatch(segment -> Strings.CS.equals(
                "tty-accept-edits-marker.yml", segment.text())
                && segment.modifiers().contains(SGR.BOLD)),
            "released Write header bolds the path");
        assertTrue(panel.segmentLines.stream().skip(1).flatMap(List::stream)
                .anyMatch(segment -> Strings.CS.contains(segment.text(), "enabled")
                    && !TextColor.ANSI.DEFAULT.equals(segment.color())),
            "released HighlightedCode path applies syntax coloring to the source preview");
    }

    @Test
    void structuredWriteResultCompletesHeaderAndReplacesPermissionWaitingRow() {
        Path file = Path.of(System.getProperty("user.dir"), "structured-waiting.txt");
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        StubPanel panel = new StubPanel();
        String input = "{\"file_path\":\"" + file + "\",\"content\":\"done\\n\"}";
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Write|toolu_structured_wait"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Write|toolu_structured_wait|" + input), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent("permission_waiting", "Write"), panel);

        UserMessage result = new UserMessage(
            "structured-wait-user",
            MessageContent.ofToolResult("toolu_structured_wait",
                List.of(new TextBlock("File created successfully")), false),
            false, false, FileChangeResult.created(file.toString(), "done\n"),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-structured-wait");
        dispatcher.dispatch(new SDKMessage.User(result), panel);

        assertFalse(Strings.CS.contains(String.join("\n", panel.lines), "Waiting…"), panel.lines.toString());
        assertTrue(Strings.CS.contains(panel.lines.get(1), "Wrote 1 lines"), panel.lines.toString());
    }

    @Test
    void structuredEditSeparatesMultipleHunksWithReleasedEllipsis() {
        Path file = Path.of(System.getProperty("user.dir"), "multi-hunk.yml");
        List<StructuredPatchHunk> hunks = List.of(
            new StructuredPatchHunk(1, 1, 1, 1, List.of("-first: old", "+first: new")),
            new StructuredPatchHunk(20, 1, 20, 1, List.of("-last: old", "+last: new")));
        UserMessage result = new UserMessage(
            "multi-hunk-user",
            MessageContent.ofToolResult("toolu_multi_hunk",
                List.of(new TextBlock("updated")), false),
            false, false,
            FileChangeResult.edited(file.toString(), "old", "new", "", hunks, false, false),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-multi-hunk");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        assertTrue(panel.lines.stream().anyMatch(line -> Strings.CS.endsWith(line, "...")),
            panel.lines.toString());
    }

    @Test
    void planFileStructuredResultUsesReleasedPlanPreviewHint(@TempDir Path dir) {
        Path plans = dir.resolve("plans").toAbsolutePath();
        PlanFiles.configurePlansDirectory(plans);
        try {
            Path file = plans.resolve("quiet-river-plan.md");
            UserMessage result = new UserMessage(
                "plan-write-user",
                MessageContent.ofToolResult("toolu_plan_write",
                    List.of(new TextBlock("created")), false),
                false, false, FileChangeResult.created(file.toString(), "# Plan\n"),
                MessageOrigin.USER, null, Instant.now(),
                null, null, null, "assistant-plan-write");
            StubPanel panel = new StubPanel();

            new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

            assertEquals(List.of(Figures.RESULT_PREFIX + "/plan to preview"), panel.lines);
        } finally {
            PlanFiles.resetPlansDirectory();
        }
    }

    @Test
    void liveEditRendersStructuredDiffInsteadOfModelFacingSuccessText() {
        Path file = Path.of(System.getProperty("user.dir"), "edited-example.java");
        String modelFacing = "The file " + file + " has been updated successfully.";
        StructuredPatchHunk hunk = new StructuredPatchHunk(
            1, 3, 1, 3, List.of(
                " class Example {",
                "-    int value = 1;",
                "+    int value = 2;",
                " }"));
        UserMessage result = new UserMessage(
            "edit-user",
            MessageContent.ofToolResult("toolu_edit", List.of(new TextBlock(modelFacing)), false),
            false, false,
            FileChangeResult.edited(file.toString(), "int value = 1;", "int value = 2;",
                "class Example {\n    int value = 1;\n}\n", List.of(hunk), false, false),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-edit");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Added 1 line, removed 1 line"), rendered);
        assertTrue(Strings.CS.contains(rendered, "-     int value = 1;"), rendered);
        assertTrue(Strings.CS.contains(rendered, "+     int value = 2;"), rendered);
        assertTrue(hasColoredDiffLine(panel, "int value = 1;"),
            "removed diff must retain a colored background");
        assertTrue(hasColoredDiffLine(panel, "int value = 2;"),
            "added diff must retain a colored background");
        assertFalse(Strings.CS.contains(rendered, modelFacing), rendered);
    }

    private static boolean hasColoredDiffLine(StubPanel panel, String text) {
        return panel.segmentLines.stream().anyMatch(segments -> {
            String rendered = segments.stream().map(MessagePanel.Segment::text)
                .reduce("", String::concat);
            return Strings.CS.contains(rendered, text)
                && segments.stream().anyMatch(segment -> segment.bgColor() != null);
        });
    }

    private static TextColor diffBackground(StubPanel panel, String text) {
        return panel.segmentLines.stream()
            .filter(segments -> Strings.CS.contains(
                segments.stream().map(MessagePanel.Segment::text).reduce("", String::concat), text))
            .flatMap(List::stream)
            .map(MessagePanel.Segment::bgColor)
            .filter(Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing diff background for " + text));
    }

    @Test
    void replayWriteCreateRendersMapShapedStructuredResult() {
        Path file = Path.of(System.getProperty("user.dir"), "replayed-write.txt");
        Map<String, Object> toolUseResult = Map.of(
            "filePath", file.toString(),
            "structuredPatch", List.of(),
            "type", "create",
            "content", "first\nsecond\n",
            "userModified", false);
        UserMessage result = new UserMessage(
            "replay-write-user",
            MessageContent.ofToolResult("toolu_replay_write",
                List.of(new TextBlock("File created successfully at: " + file)), false),
            false, false, toolUseResult,
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-replay-write");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Wrote 2 lines to replayed-write.txt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "1 first"), rendered);
        assertTrue(Strings.CS.contains(rendered, "2 second"), rendered);
    }

    @Test
    void emptyWriteCreateMatchesReleasedOneVisibleFallbackLine() {
        Path file = Path.of(System.getProperty("user.dir"), "empty-created.txt");
        UserMessage result = new UserMessage(
            "empty-write-user",
            MessageContent.ofToolResult("toolu_empty_write",
                List.of(new TextBlock("created")), false),
            false, false, FileChangeResult.created(file.toString(), ""),
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-empty-write");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Wrote 1 lines to empty-created.txt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "1 (No content)"), rendered);
    }

    @Test
    void replayEditRendersMapShapedStructuredDiff() {
        Path file = Path.of(System.getProperty("user.dir"), "replayed-edit.yml");
        String modelFacing = "The file " + file + " has been updated successfully.";
        Map<String, Object> toolUseResult = Map.of(
            "filePath", file.toString(),
            "structuredPatch", List.of(Map.of(
                "oldStart", 1,
                "oldLines", 1,
                "newStart", 1,
                "newLines", 1,
                "lines", List.of("-enabled: false", "+enabled: true"))),
            "originalFile", "enabled: false\n",
            "oldString", "enabled: false",
            "newString", "enabled: true",
            "userModified", false,
            "replaceAll", false);
        UserMessage result = new UserMessage(
            "replay-edit-user",
            MessageContent.ofToolResult("toolu_replay_edit",
                List.of(new TextBlock(modelFacing)), false),
            false, false, toolUseResult,
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-replay-edit");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Added 1 line, removed 1 line"), rendered);
        assertTrue(Strings.CS.contains(rendered, "- enabled: false"), rendered);
        assertTrue(Strings.CS.contains(rendered, "+ enabled: true"), rendered);
        assertFalse(Strings.CS.contains(rendered, modelFacing), rendered);
    }

    @Test
    void updateWithContentAndEmptyPatchRendersFullUpdatedLinesNotBareHeader() {
        // 197's isResultTruncated never folds "update": an identical rewrite (empty
        // structuredPatch) whose Write result still carries full content must render the
        // full "Updated N lines" body, not fall through to the generic bare ⏺ header.
        Path file = Path.of(System.getProperty("user.dir"), "replayed-update.txt");
        Map<String, Object> toolUseResult = Map.of(
            "filePath", file.toString(),
            "structuredPatch", List.of(),
            "type", "update",
            "content", "line1\nline2\n",
            "userModified", false);
        UserMessage result = new UserMessage(
            "replay-update-user",
            MessageContent.ofToolResult("toolu_replay_update",
                List.of(new TextBlock("File updated")), false),
            false, false, toolUseResult,
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-replay-update");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        assertTrue(Strings.CS.contains(rendered, "Updated 2 lines to replayed-update.txt"), rendered);
        assertTrue(Strings.CS.contains(rendered, "1 line1"), rendered);
        assertTrue(Strings.CS.contains(rendered, "2 line2"), rendered);
        // Must not degrade to a bare empty tool header.
        assertFalse(Strings.CS.contains(rendered, "Update\n"), rendered);
    }

    @Test
    void editResultSurvivesJsonRoundTripAndStillRendersDiff() throws Exception {
        // "diff 正常输出，刷新新内容时变空": serialize an Edit FileChangeResult (with hunks)
        // to JSON, parse it back exactly as a session reload would, then dispatch. The hunks
        // must survive so the diff still renders instead of degrading to a bare header.
        Path file = Path.of(System.getProperty("user.dir"), "roundtrip-edit.yml");
        FileChangeResult change = FileChangeResult.edited(
            file.toString(),
            List.of(new StructuredPatchHunk(1, 1, 1, 1, List.of("-a: 1", "+a: 2"))));
        String json = JsonUtils.getMapper().writeValueAsString(change);
        // Re-parse via Jackson: UserMessage.toolUseResult is a polymorphic Object, so the
        // session-reload path reconstructs from this JSON the same way.
        // (map via readTree -> Map so the type matches the raw JSON-backed toolUseResult.)
        Map<String, Object> reconstructed =
            JsonUtils.getMapper().convertValue(JsonUtils.getMapper().readTree(json),
                JsonUtils.getMapper().getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));

        UserMessage result = new UserMessage(
            "roundtrip-edit-user",
            MessageContent.ofToolResult("toolu_roundtrip_edit",
                List.of(new TextBlock("The file was updated successfully.")), false),
            false, false, reconstructed,
            MessageOrigin.USER, null, Instant.now(),
            null, null, null, "assistant-roundtrip-edit");
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(result), panel);

        String rendered = String.join("\n", panel.lines);
        // The hunks survived the round trip — the diff must render.
        assertTrue(Strings.CS.contains(rendered, "Added 1 line, removed 1 line"), rendered);
        assertTrue(Strings.CS.contains(rendered, "- a: 1"), rendered);
        assertTrue(Strings.CS.contains(rendered, "+ a: 2"), rendered);
    }
}
