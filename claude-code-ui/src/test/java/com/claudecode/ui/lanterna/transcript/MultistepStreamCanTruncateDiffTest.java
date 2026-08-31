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
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the reported regression: after an Edit tool result renders a full
 * structured diff (while streaming), NEW streamed content from the model in a
 * multistep agentic loop must NOT fold / erase that diff.
 *
 * <p>Suspected mechanism: the dispatcher's {@code streamedThisTurn} flag stays
 * {@code true} across the tool-result user message, and {@code streamStartSnapshot}
 * still points BELOW the diff (taken at the first text delta, which preceded the
 * tool). When a follow-up {@code content_block_delta} then arrives with text whose
 * stable prefix no longer matches, {@code renderStreamingMarkdown} calls
 * {@code panel.truncateLinesTo(streamStartSnapshot)}, rolling the transcript back
 * to before the diff — erasing it on screen.
 *
 * <p>2.1.197 never folds Edit diffs (see {@code renderToolResultMessage:jkl}/{@code b7n}),
 * and its text streaming rolls back only the current text block, never a previously
 * committed tool result.
 */
class MultistepStreamCanTruncateDiffTest {

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
    void editDiffSurvivesANewStreamedTextBlockInTheSameAgenticLoop() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        // ── Model's first text block streams in; the streaming window opens. ──
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "I will update the field now."), panel);

        // ── Model prefaces with a tool call (Edit). ──
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Edit|toolu_edit"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Edit|toolu_edit|{\"file_path\":\"src/Example.java\"}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Edit|toolu_edit|{\"file_path\":\"src/Example.java\"}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Edit|The file was updated successfully."), panel);

        // ── The tool result arrives as a USER message carrying the structured diff. ──
        // NOTE: we deliberately do NOT send an Assistant envelope between the text
        // stream and this diff — mirroring a multistep agentic loop where the tool
        // result lands while streamedThisTurn is still true.
        String toolUseId = "toolu_edit";
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("u1",
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock("class Example {\n  int oldValue;\n}\n")), false),
            false, false, editResult(), MessageOrigin.USER, null,
            Instant.now(), null, null)), panel);
        assertDiffPresent(rowsOf(panel), "after Edit result diff while streaming window open");

        // ── NEW content refreshes the output: model keeps generating after the diff. ──
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " Now I will explain what changed."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " The added line is just a placeholder."), panel);

        // The diff from the earlier tool result must NOT have been rolled back by
        // the new streamed text.
        assertDiffPresent(rowsOf(panel), "after NEW streamed text refreshes output");
    }

    /** Same loop, but with an explicit Assistant envelope committing the first text
     * block before the tool — the streaming window closes, so this must pass trivially. */
    @Test
    void committedTextBlockThenDiffThenMoreStreamingKeepsDiff() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        // Text streams...
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "I will update the field now."), panel);
        // ...then the Assistant envelope commits the text block and closes the window.
        dispatcher.dispatch(new SDKMessage.Assistant(new AssistantMessage("asst1",
            AssistantContent.of("msg1", List.of(
                new TextBlock("I will update the field now.")))),
            Usage.EMPTY), panel);

        // Tool runs; result renders a structured diff (streaming window is closed).
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Edit|toolu_edit"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Edit|toolu_edit|{\"file_path\":\"src/Example.java\"}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Edit|The file was updated successfully."), panel);
        String toolUseId = "toolu_edit";
        dispatcher.dispatch(new SDKMessage.User(new UserMessage("u1",
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock("class Example {\n  int oldValue;\n}\n")), false),
            false, false, editResult(), MessageOrigin.USER, null,
            Instant.now(), null, null)), panel);
        assertDiffPresent(rowsOf(panel), "after Edit diff (window closed)");

        // New text streams in after the diff.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " Now explaining the change."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " Finished."), panel);

        assertDiffPresent(rowsOf(panel), "after new streamed text (window was closed)");
    }

    /** The tool result arrives as a {@code tool_result_success} StreamEvent alone (no User
     * envelope): its rendered rows must still survive a later streamed text block. */
    @Test
    void streamCommittedToolResultThenNewTextKeepsResultRows() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        // Model streams the first text block, then issues an Edit call.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", "I will update the field now."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Edit|toolu_edit"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Edit|toolu_edit|{\"file_path\":\"src/Example.java\"}"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_call_start", "Edit|toolu_edit|{\"file_path\":\"src/Example.java\"}"), panel);

        // The tool resolves; its textual result renders via the stream event path.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_result_success", "Edit|The file was updated successfully."), panel);
        List<String> beforeNewText = rowsOf(panel);
        assertTrue(beforeNewText.stream().anyMatch(r -> r.contains("was updated successfully.")),
            "tool result text must render (found=" + beforeNewText + ")");

        // NEW content streams in after the committed result — must not roll it back.
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " Now I will explain what changed."), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "content_block_delta", " The change is minimal."), panel);

        List<String> after = rowsOf(panel);
        assertTrue(after.stream().anyMatch(r -> r.contains("was updated successfully.")),
            "committed tool result text must survive the new stream (found=" + after + ")");
    }
}