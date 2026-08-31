package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class TranscriptEventReducerTest {

    @Test
    void completedCompactRebuildsVisiblePanelFromReducedHistory() {
        MessagePanel panel = new MessagePanel();
        MessageHistory history = new MessageHistory();
        MessageCollapser collapser = new MessageCollapser(
            new LanternaMessageDispatcher(), false);
        TranscriptEventReducer reducer = new TranscriptEventReducer(
            history, collapser, panel, () -> false);

        reducer.accept(user("u-old", "ancient-visible-row"));
        reducer.accept(boundary("b1"));
        reducer.accept(user("u-new", "recent-visible-row"));
        reducer.accept(boundary("b2"));

        String rendered = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(Strings.CS.contains(rendered, "ancient-visible-row"), rendered);
        assertTrue(Strings.CS.contains(rendered, "recent-visible-row"), rendered);
    }

    @Test
    void preliminaryCompactBoundaryDoesNotPaintAFalseCompletedRow() {
        MessagePanel panel = new MessagePanel();
        TranscriptEventReducer reducer = new TranscriptEventReducer(
            new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false),
            panel, () -> false);

        reducer.accept(new SDKMessage.CompactBoundary(List.of("u1"), Usage.EMPTY));

        assertTrue(panel.snapshotStyledLines().isEmpty());
    }

    @Test
    void compactDropsPendingCollapseStateInsteadOfReplayingPreBoundaryToolRows() {
        MessagePanel panel = new MessagePanel();
        TranscriptEventReducer reducer = new TranscriptEventReducer(
            new MessageHistory(),
            new MessageCollapser(new LanternaMessageDispatcher(), false),
            panel, () -> false);

        reducer.accept(new SDKMessage.StreamEvent(
            "tool_call_start", "Read|{\"file_path\":\"ancient.txt\"}"));
        reducer.accept(boundary("b1"));

        String rendered = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(Strings.CS.contains(rendered, "ancient.txt"), rendered);
    }

    private static SDKMessage.User user(String uuid, String text) {
        return new SDKMessage.User(new UserMessage(uuid, MessageContent.ofText(text)));
    }

    private static SDKMessage.CompactBoundary boundary(String uuid) {
        return new SDKMessage.CompactBoundary(List.of(), Usage.EMPTY,
            new SystemMessage(uuid, "compact_boundary", "info", "Conversation compacted"));
    }
}
