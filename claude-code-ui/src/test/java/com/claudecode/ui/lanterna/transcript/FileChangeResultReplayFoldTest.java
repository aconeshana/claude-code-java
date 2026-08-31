package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the reported regression: after a message-area refresh/replay, a
 * Write/Edit tool result that rendered as a full diff while streaming gets
 * folded by the generic {@code renderToolResult} fallback. Gold standard is
 * 2.1.197's {@code isResultTruncated}: only {@code create} (Write) truncates
 * to 10 lines; {@code update} (Edit) renders the full patch, never folds.
 *
 * There are two ways the structured payload can reach the dispatcher:
 * (a) live streaming carries the in-memory {@link FileChangeResult}, and
 * (b) replay carries whatever deserialized out of the JSONL transcript row.
 * Both must render the full diff — a fold is a regression against 197.
 */
class FileChangeResultReplayFoldTest {

    private static final String EDIT_BODY =
        "class Example {\n  int oldValue;\n  int keep;\n  int newValue;\n}\n";

    private static FileChangeResult editResult() {
        return FileChangeResult.edited("src/Example.java",
            List.of(new StructuredPatchHunk(1, 4, 1, 4, List.of(
                " class Example {",
                "-  int oldValue;",
                "   int keep;",
                "+  int newValue;",
                " }"))));
    }

    private static UserMessage editUserMessage(String uuid, Object toolUseResult) {
        String toolUseId = "toolu_" + uuid;
        return new UserMessage(uuid,
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock(EDIT_BODY)), false),
            false, false, toolUseResult,
            MessageOrigin.USER, null, Instant.now(), null, null);
    }

    /**
     * What {@code readMessages} hands to the dispatcher on replay: the payload
     * has gone through JSON, so it is a plain {@code LinkedHashMap} with the
     * same field names, not a {@link FileChangeResult} instance.
     */
    private static Object asRoundTrippedJson(Object toolUseResult) {
        return JsonUtils.getMapper().convertValue(toolUseResult,
            Object.class);
    }

    private List<String> render(String uuid, Object toolUseResult) {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.dispatch(new SDKMessage.User(editUserMessage(uuid, toolUseResult)), panel);
        return panel.displayRowsForTest(160).stream()
            .map(MessagePanel.StyledLine::text)
            .toList();
    }

    private static void assertEditUnfolded(List<String> rows) {
        assertTrue(rows.stream().anyMatch(r -> r.contains("int newValue;")),
            "added diff line must render unfolded: " + rows);
        assertTrue(rows.stream().anyMatch(r -> r.contains("int oldValue;")),
            "removed diff line must render (context) unfolded: " + rows);
    }

    @Test
    void liveEditCarryingFileChangeResultRendersFullDiff() {
        assertEditUnfolded(render("live", editResult()));
    }

    @Test
    void replayedEditCarryingRoundTrippedPayloadRendersFullDiff() {
        assertEditUnfolded(render("replay", asRoundTrippedJson(editResult())));
    }

    /**
     * The Write-overwrite case: an existing file is rewritten and the diff
     * computes to zero hunks. 197's {@code isResultTruncated} returns false
     * for {@code update} so the full content must render — never the 3-row
     * generic fold.
     */
    static UserMessage writeUpdateEmptyPatchUserMessage(String uuid, Object change) {
        String toolUseId = "toolu_" + uuid;
        return new UserMessage(uuid,
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock("The file src/Example.java has been updated successfully.")),
                false),
            false, false, change, MessageOrigin.USER, null, Instant.now(), null, null);
    }

    private static FileChangeResult writeUpdateEmptyPatchResult() {
        return FileChangeResult.updated("src/Example.java",
            "class Example {\n  int newValue;\n}\n",
            "class Example {\n  int oldValue;\n}\n", List.of());
    }

    @Test
    void writeUpdateWithEmptyPatchIsNotFolded() {
        assertWriteUpdateUnfolded(
            writeUpdateEmptyPatchUserMessage("wu", writeUpdateEmptyPatchResult()));
    }

    @Test
    void replayedWriteUpdateWithEmptyPatchIsNotFolded() {
        assertWriteUpdateUnfolded(writeUpdateEmptyPatchUserMessage(
            "wur", asRoundTrippedJson(writeUpdateEmptyPatchResult())));
    }

    private static void assertWriteUpdateUnfolded(UserMessage msg) {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.dispatch(new SDKMessage.User(msg), panel);
        List<String> rows = panel.displayRowsForTest(160).stream()
            .map(MessagePanel.StyledLine::text)
            .toList();
        // update never folds and renders full content (197 isResultTruncated=false).
        assertTrue(rows.stream().anyMatch(r -> r.contains("int newValue;")),
            "Write-update content must render unfolded (197 renders full content): " + rows);
        assertTrue(rows.stream().anyMatch(r -> r.contains("Updated ")),
            "Write-update header must read 'Updated', not 'Wrote': " + rows);
    }

    /**
     * Agent sub-transcript scenario. A child Edit tool result inside an Agent's
     * transcript carries a structured {@code FileChangeResult}; the Ctrl+O transport
     * re-dispatches children through a verbose+showAll collapser and the Edit
     * structured patch must still render its diff hunks, never a folded title.
     */
    @Test
    void agentChildEditResultRendersFullDiffViaSubTranscript() {
        String agentId = "agent-edit-child";
        var input = JsonUtils.getMapper().createObjectNode();
        input.put("description", "edit files");
        input.put("prompt", "Inspect and update");
        input.put("subagent_type", "general-purpose");

        List<SDKMessage> history = List.of(
            new SDKMessage.Assistant(new AssistantMessage("assistant-agents",
                AssistantContent.of("shared-message", List.of(
                    new ToolUseBlock(agentId, "Agent", input)), Usage.EMPTY)),
                Usage.EMPTY),
            childEditProgress(agentId, "child-edit"),
            childEditResultProgress(agentId, "child-edit"),
            agentCompleted(agentId, "general-purpose"));

        TranscriptRenderModel model = TranscriptRenderModel.from(history);
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        dispatcher.setTranscriptRenderModel(model);
        MessageCollapser collapser = new MessageCollapser(dispatcher, false);
        collapser.setShowAll(true);
        MessagePanel panel = new MessagePanel();

        for (SDKMessage event : model.events()) collapser.dispatch(event, panel);

        List<String> rows = panel.displayRowsForTest(160).stream()
            .map(MessagePanel.StyledLine::text)
            .toList();
        assertTrue(rows.stream().anyMatch(r -> r.contains("int newValue;")),
            "agent child Edit diff must render its added line, not fold: " + rows);
        assertTrue(rows.stream().anyMatch(r -> r.contains("int oldValue;")),
            "agent child Edit diff must render its removed line: " + rows);
    }

    private static SDKMessage.Progress childEditProgress(String agentToolUseId, String childId) {
        var childInput = JsonUtils.getMapper().createObjectNode();
        childInput.put("file_path", "src/Example.java");
        AssistantMessage childUse = new AssistantMessage("assistant-child",
            AssistantContent.of("message-child", List.of(
                new ToolUseBlock(childId, "Edit", childInput)), new Usage(10, 0, 0, 0)));
        return progress(agentToolUseId, "child-edit", childUse);
    }

    private static SDKMessage.Progress childEditResultProgress(String agentToolUseId,
                                                               String childId) {
        UserMessage childResult = new UserMessage("child-result-" + childId,
            MessageContent.ofToolResult(childId, List.of(new TextBlock(EDIT_BODY)),
                false), false, false, editResult(), MessageOrigin.USER, null,
            Instant.now(), null, null);
        return progress(agentToolUseId, "child-result-" + childId, childResult);
    }

    private static SDKMessage.Progress progress(String agentToolUseId, String uuid,
                                                com.claudecode.core.message.Message child) {
        ProgressMessage.ProgressData data = new ProgressMessage.ProgressData(
            "agent_progress", null, null, null, null, null, null, null, false,
            child, "Inspect and update", "agent-id");
        return new SDKMessage.Progress(new ProgressMessage(
            uuid, "", null, Instant.now(), agentToolUseId, null, data));
    }

    private static SDKMessage.User agentCompleted(String toolUseId, String type) {
        Map<String, Object> payload = Map.of(
            "status", "completed",
            "prompt", "Inspect and update",
            "agentId", "a1",
            "agentType", type,
            "content", List.of(Map.of("type", "text", "text", "All done")),
            "totalDurationMs", 1_000L,
            "totalTokens", 900L,
            "totalToolUseCount", 1,
            "usage", Usage.EMPTY);
        UserMessage result = new UserMessage("result-" + toolUseId,
            MessageContent.ofToolResult(toolUseId,
                List.of(new TextBlock("All done")), false),
            false, false, payload, MessageOrigin.USER, null, Instant.now(), null, null);
        return new SDKMessage.User(result);
    }
}