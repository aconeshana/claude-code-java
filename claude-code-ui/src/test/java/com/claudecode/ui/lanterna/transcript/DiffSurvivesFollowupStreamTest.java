package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the reported symptom: an Edit tool result renders a full structured
 * diff while streaming, but when NEW content arrives afterwards (a follow-up
 * assistant stream resets the streaming window), the earlier diff is folded /
 * disappears. 2.1.197 (renderToolResultMessage jkl / b7n) renders the Edit result
 * as full diffs and never folds them, regardless of what lands later.
 */
class DiffSurvivesFollowupStreamTest {

    private static FileChangeResult editResult() {
        return FileChangeResult.edited("src/Example.java",
            List.of(new StructuredPatchHunk(1, 4, 1, 4, List.of(
                " class Example {",
                "-  int oldValue;",
                "   int keep;",
                "+  int newValue;",
                " }"))));
    }

    private static List<String> rowsOf(MessagePanel panel) {
        return panel.displayRowsForTest(160).stream()
            .map(MessagePanel.StyledLine::text)
            .toList();
    }

    private static void assertDiffPresent(List<String> rows, String label) {
        assertTrue(rows.stream().anyMatch(r -> r.contains("int newValue;")),
            label + ": added diff line must survive (found=" + rows + ")");
        assertTrue(rows.stream().anyMatch(r -> r.contains("int oldValue;")),
            label + ": removed diff line must survive (found=" + rows + ")");
    }

    @Test
    void editDiffSurvivesAFollowupStreamingAssistantMessage() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        // 1. Edit result dispatches as a structured FileChangeResult — renders full diff.
        String toolUseId = "toolu_edit";
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("u1",
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock("class Example {\n  int oldValue;\n}\n")), false),
            false, false, editResult(), MessageOrigin.USER, null,
            Instant.now(), null, null)), panel);
        assertDiffPresent(rowsOf(panel), "immediately after Edit result");

        // 2. A follow-up assistant message streams NEW textual content after the diff.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Now I will explain what changed."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " The added line is just a placeholder."), panel);

        // 3. The final assistant envelope commits the streamed text.
        dispatcher.dispatch(new SDKMessage.Assistant(new AssistantMessage("asst1",
            AssistantContent.of("msg1", List.of(
                new TextBlock("Now I will explain what changed. The added line is just a placeholder.")))),
            Usage.EMPTY), panel);

        // The earlier Edit diff must NOT have been folded by the follow-up stream.
        assertDiffPresent(rowsOf(panel), "after follow-up streaming assistant text");
    }

    /** Multi-step loop: diff, then a tool-use-only assistant, then a text assistant — like a real turn. */
    @Test
    void editDiffSurvivesInterleavedToolCallAndTextStream() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        // Diff (tool result).
        String editUseId = "toolu_edit";
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("u1",
            MessageContent.ofToolResult(editUseId,
                List.of(new TextBlock("class Example {\n  int oldValue;\n}\n")), false),
            false, false, editResult(), MessageOrigin.USER, null,
            Instant.now(), null, null)), panel);
        assertDiffPresent(rowsOf(panel), "after edit result");

        // A second tool-use assistant message streams its header.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Read|toolu_read"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|toolu_read|{\"file_path\":\"x\"}"), panel);

        // Then streaming text from the model.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "Checking the other file..."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " done."), panel);
        dispatcher.dispatch(new SDKMessage.Assistant(new AssistantMessage("asst2",
            AssistantContent.of("msg2", List.of(
                new TextBlock("Checking the other file... done.")))),
            Usage.EMPTY), panel);

        assertDiffPresent(rowsOf(panel), "after interleaved tool + text stream");
    }
}