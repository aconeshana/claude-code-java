package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.googlecode.lanterna.SGR;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;


class ThinkingVisibility197Test {

    /** The 197 thinking gutter: a two-column dim+italic glyph on the first body row. */
    private static final String GUTTER = "∴ ";

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
        assertEquals(1, rendered.lines().filter(line -> Strings.CS.startsWith(line, GUTTER)).count(),
            rendered);
    }

    @Test
    void thinkingBodyUsesAGutterColumnRatherThanALabelRow() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setVerbose(true);
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new ThinkingBlock("\n\nfirst reasoning line\nsecond reasoning line\n\n")), panel);

        List<MessagePanel.StyledLine> rows = bodyRows(panel);
        assertFalse(rows.isEmpty(), "expected thinking body rows");
        assertTrue(rows.stream().map(MessagePanel.StyledLine::text)
            .noneMatch(line -> Strings.CS.contains(line, "Thinking")), renderedText(panel));

        // The glyph shares its row with the text — a blank row here was the 197 mismatch.
        MessagePanel.StyledLine first = rows.getFirst();
        assertEquals(GUTTER, first.segments().getFirst().text());
        assertTrue(first.segments().getFirst().modifiers().contains(SGR.ITALIC));
        assertTrue(Strings.CS.contains(first.text(), "first reasoning line"), first.text());

        MessagePanel.StyledLine second = rows.get(1);
        assertEquals("  ", second.segments().getFirst().text());
        assertFalse(second.segments().getFirst().modifiers().contains(SGR.ITALIC));
        assertTrue(Strings.CS.contains(second.text(), "second reasoning line"), second.text());

        // Trailing blank markdown rows are dropped, so the body is exactly two rows.
        assertEquals(2, rows.size(), renderedText(panel));
    }

    @Test
    void redactedThinkingIsHiddenOutsideVerboseAndTranscript() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new RedactedThinkingBlock("EncRypTeDpAyLoAd"), new TextBlock("visible answer")), panel);

        String rendered = renderedText(panel);
        assertTrue(Strings.CS.contains(rendered, "visible answer"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Thinking"), rendered);
    }

    @Test
    void redactedThinkingRendersOneBodylessRowInVerbose() {
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setVerbose(true);
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new RedactedThinkingBlock("EncRypTeDpAyLoAd")), panel);

        List<String> rows = bodyRows(panel).stream().map(MessagePanel.StyledLine::text).toList();
        assertEquals(1, rows.stream()
            .filter(line -> Strings.CS.contains(line, Figures.TEARDROP_ASTERISK + " Thinking"))
            .count(), rows.toString());
        assertFalse(Strings.CS.contains(String.join("\n", rows), "EncRypTeDpAyLoAd"),
            rows.toString());
    }

    @Test
    void redactedThinkingIgnoresTheTranscriptWhitelist() {
        // findLastThinkingBlockId only ever matches ThinkingBlock, so routing the redacted
        // arm through the whitelist would hide it permanently in ctrl+o.
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);
        dispatcher.showOnlyTranscriptThinkingBlock("some-other-message:0");
        MessagePanel panel = new MessagePanel();

        dispatcher.dispatch(assistant("assistant-1",
            new RedactedThinkingBlock("EncRypTeDpAyLoAd")), panel);

        assertTrue(Strings.CS.contains(renderedText(panel),
            Figures.TEARDROP_ASTERISK + " Thinking"), renderedText(panel));
    }

    @Test
    void virtualThinkingIsSelectableAsTheLastThinkingBlock() {
        // Shape of the message ESC salvage appends (see InterruptedThinkingCache): were it
        // skipped here, the rescued reasoning could never be picked for ctrl+o.
        SDKMessage.Assistant salvaged = new SDKMessage.Assistant(
            new AssistantMessage("salvaged-1",
                AssistantContent.of(List.of(new ThinkingBlock("rescued reasoning", ""))),
                false, null, null, null, null, null, null, null, null,
                Boolean.TRUE, null, null, null),
            Usage.EMPTY);
        List<SDKMessage> currentTurn = List.of(
            user("prompt-1", MessageContent.ofText("a prompt")), salvaged);

        assertEquals("salvaged-1:0", TranscriptWindow.findLastThinkingBlockId(currentTurn));
    }

    /** Rows carrying rendered content, i.e. everything but structural blank rows. */
    private static List<MessagePanel.StyledLine> bodyRows(MessagePanel panel) {
        return panel.displayRowsForTest(120).stream()
            .filter(row -> !StringUtils.isBlank(row.text()))
            .toList();
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
