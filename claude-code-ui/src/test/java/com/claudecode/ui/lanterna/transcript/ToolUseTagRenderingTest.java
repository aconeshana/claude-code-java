package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.tools.ToolUseTag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class ToolUseTagRenderingTest {

    @Test
    void progressContextRecomputesThePendingToolHeaderTag() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setToolTagLookup(request -> {
            String resolved = request.progressMessages().stream()
                .map(ProgressMessage::data)
                .filter(data -> data != null && data.resolvedModel() != null)
                .map(ProgressMessage.ProgressData::resolvedModel)
                .reduce((_, value) -> value)
                .orElse("queued");
            return Optional.of(ToolUseTag.dim(resolved));
        });
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Agent|toolu_1"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Agent|toolu_1|{\"model\":\"haiku\","
                + "\"description\":\"inspect\"}"), panel);
        assertTrue(Strings.CS.contains(panelText(panel), "queued"));

        dispatcher.dispatch(new SDKMessage.Progress(new ProgressMessage(
            "progress", "", null, Instant.now(), "toolu_1", null,
            new ProgressMessage.ProgressData(
                "agent_progress", null, null, null, null, null, null, null,
                true, null, null, "agent", null, null, null, null, null,
                "claude-opus-4-6"))), panel);

        assertTrue(Strings.CS.contains(panelText(panel), "claude-opus-4-6"));
    }

    @Test
    void structuredResultIsAvailableWhenTheCompletedHeaderTagIsRendered() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setToolTagLookup(request -> request.toolUseResult() instanceof Map<?, ?> result
            ? Optional.of(ToolUseTag.dim(String.valueOf(result.get("tag"))))
            : Optional.empty());
        MessagePanel panel = new MessagePanel();
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_start", "Custom|toolu_result"), panel);
        dispatcher.dispatch(new SDKMessage.StreamEvent(
            "tool_streaming_done", "Custom|toolu_result|{\"value\":1}"), panel);

        UserMessage result = new UserMessage("result",
            MessageContent.ofToolResult(
                "toolu_result", List.of(new TextBlock("done")), false),
            false, false, Map.of("tag", "resolved-result"), MessageOrigin.USER,
            null, Instant.now(), null, null);
        dispatcher.dispatch(new SDKMessage.User(result), panel);

        assertTrue(Strings.CS.contains(panelText(panel), "resolved-result"));
    }

    private static String panelText(MessagePanel panel) {
        return panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
    }
}
