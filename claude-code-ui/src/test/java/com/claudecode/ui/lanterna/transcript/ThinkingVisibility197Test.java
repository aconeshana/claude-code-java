package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;


class ThinkingVisibility197Test {

    @Test
    void normalTranscriptHidesEveryCompletedThinkingBlock() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new ThinkingBlock("first private reasoning"),
            new ThinkingBlock("second private reasoning"),
            new TextBlock("visible answer")), panel);

        String rendered = renderedText(panel);
        assertTrue(Strings.CS.contains(rendered, "visible answer"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Thinking"), rendered);
        assertFalse(Strings.CS.contains(rendered, "private reasoning"), rendered);
    }

    @Test
    void detailedTranscriptShowsOnlyTheSelectedLastThinkingBlock() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        dispatcher.showOnlyTranscriptThinkingBlock("assistant-2:0");
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new ThinkingBlock("older reasoning"), new TextBlock("first answer")), panel);
        dispatcher.dispatch(assistant("assistant-2",
            new ThinkingBlock("latest reasoning"), new TextBlock("second answer")), panel);

        String rendered = renderedText(panel);
        assertFalse(Strings.CS.contains(rendered, "older reasoning"), rendered);
        assertTrue(Strings.CS.contains(rendered, "latest reasoning"), rendered);
        assertEquals(1, rendered.lines().filter(line -> Strings.CS.contains(line, "Thinking")).count(), rendered);
    }

    @Test
    void lastThinkingLookupStopsAtTheLatestRealUserPromptButCrossesToolResults() {
        List<SDKMessage> currentTurn = List.of(
            user("prompt-1", MessageContent.ofText("first prompt")),
            assistant("assistant-old", new ThinkingBlock("old reasoning")),
            user("prompt-2", MessageContent.ofText("second prompt")),
            assistant("assistant-1", new ThinkingBlock("first current reasoning")),
            user("tool-result", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("toolu-1", List.of(new TextBlock("done")), false)))),
            assistant("assistant-2", new ThinkingBlock("last current reasoning")));

        assertEquals("assistant-2:0", TranscriptWindow.findLastThinkingBlockId(currentTurn));

        List<SDKMessage> noThinkingInLatestTurn = List.of(
            user("prompt-1", MessageContent.ofText("first prompt")),
            assistant("assistant-old", new ThinkingBlock("old reasoning")),
            user("prompt-2", MessageContent.ofText("second prompt")));

        assertNull(TranscriptWindow.findLastThinkingBlockId(noThinkingInLatestTurn));
    }

    private static SDKMessage.Assistant assistant(String uuid, ContentBlock... blocks) {
        return new SDKMessage.Assistant(
            new AssistantMessage(uuid, AssistantContent.of(List.of(blocks))), Usage.EMPTY);
    }

    private static SDKMessage.User user(String uuid, MessageContent content) {
        return new SDKMessage.User(new UserMessage(uuid, content));
    }

    private static String renderedText(MessagePanel panel) {
        return panel.displayRowsForTest(120).stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (left, right) -> left + "\n" + right);
    }
}
