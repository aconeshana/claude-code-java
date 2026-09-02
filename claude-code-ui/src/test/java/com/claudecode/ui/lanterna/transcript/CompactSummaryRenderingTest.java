package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.UserMessage;
import java.time.Instant;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for compact-summary visibility.
 *
 * <ul>
 *   <li>
 *        hide the
 *       continuation prompt normally while retaining it in Ctrl+O transcript mode.</li>
 * </ul>
 */
class CompactSummaryRenderingTest {

    @Test
    void normalModeDoesNotExposeTheModelFacingContinuationPrompt() {
        MessagePanel panel = new MessagePanel();

        new LanternaMessageDispatcher().dispatch(compactSummary(), panel);

        assertTrue(panel.snapshotStyledLines().isEmpty());
    }

    @Test
    void transcriptModeShowsTheFullCompactSummary() {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);

        dispatcher.dispatch(compactSummary(), panel);

        String rendered = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(Strings.CS.contains(rendered, "This session is being continued"), rendered);
    }

    @Test
    void normalModeShowsThe197PartialCompactSummaryCard() {
        MessagePanel panel = new MessagePanel();

        new LanternaMessageDispatcher().dispatch(partialCompactSummary(), panel);

        String rendered = renderedText(panel);
        assertTrue(Strings.CS.contains(rendered,
            LanternaMessageDispatcher.BLACK_CIRCLE + "Summarized conversation"), rendered);
        assertTrue(Strings.CS.contains(rendered,
            "Summarized 3 messages from this point"), rendered);
        assertTrue(Strings.CS.contains(rendered,
            "Context: “focus on rewind”"), rendered);
        assertTrue(Strings.CS.contains(rendered, "(ctrl+o to expand history)"), rendered);
        assertFalse(Strings.CS.contains(rendered, "This session is being continued"), rendered);
    }

    @Test
    void transcriptModeExpandsThePartialCompactSummaryBody() {
        MessagePanel panel = new MessagePanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setTranscriptMode(true);

        dispatcher.dispatch(partialCompactSummary(), panel);

        String rendered = renderedText(panel);
        assertTrue(Strings.CS.contains(rendered,
            LanternaMessageDispatcher.BLACK_CIRCLE + "Summarized conversation"), rendered);
        assertTrue(Strings.CS.contains(rendered, "This session is being continued"), rendered);
        assertFalse(Strings.CS.contains(rendered, "Summarized 3 messages"), rendered);
    }

    private static SDKMessage.User compactSummary() {
        return new SDKMessage.User(new UserMessage(
            "summary", MessageContent.ofText(
                "This session is being continued from a previous conversation."),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.now(), null, null, null, null, null,
            null, null, true));
    }

    private static SDKMessage.User partialCompactSummary() {
        return new SDKMessage.User(new UserMessage(
            "partial-summary", MessageContent.ofText(
                "This session is being continued from a previous conversation."),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, Instant.now(), null, null, null, null, null,
            null, null, null, null,
            new SummarizeMetadata(3, "focus on rewind", "from")));
    }

    private static String renderedText(MessagePanel panel) {
        return panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .reduce("", (a, b) -> a + "\n" + b);
    }
}
