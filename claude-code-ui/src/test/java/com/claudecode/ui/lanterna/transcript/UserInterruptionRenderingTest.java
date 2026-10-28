package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 236's UserTextMessage renders a user {@code [Request interrupted by user]} as InterruptedByUser. */
class UserInterruptionRenderingTest {

    @Test
    void aHumanOriginInterruptionRendersTheInterruptedRow() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(
            MessageFactory.createUserMessage(MessageConstants.INTERRUPT_MESSAGE)), panel);

        assertEquals(List.of(Figures.RESULT_PREFIX + "Interrupted · What should Claude do instead?"),
            panel.textLines());
    }

    @Test
    void theLiveTurnsOriginlessSentinelStaysSwallowed() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.User(
            MessageFactory.createUserInterruptionMessage(false)), panel);

        assertEquals(List.of(), panel.textLines());
    }

    @Test
    void taggedUserTextsHaveTheirOwnLayout() {
        assertTrue(LanternaMessageDispatcher.userTextHasOwnLayout("<bash-input>ls</bash-input>"));
        assertTrue(LanternaMessageDispatcher.userTextHasOwnLayout(
            "<local-command-stdout>done</local-command-stdout>"));
        assertTrue(LanternaMessageDispatcher.userTextHasOwnLayout(
            "<command-name>/clear</command-name>\n<command-message>clear</command-message>"));
        assertTrue(LanternaMessageDispatcher.userTextHasOwnLayout(MessageConstants.INTERRUPT_MESSAGE));
        assertFalse(LanternaMessageDispatcher.userTextHasOwnLayout("fix the <b> tag"));
        assertFalse(LanternaMessageDispatcher.userTextHasOwnLayout("/clear"));
    }

    private static final class StubPanel extends MessagePanel {
        private final List<List<MessagePanel.Segment>> lines = new ArrayList<>();

        @Override public void appendMixed(List<MessagePanel.Segment> segments) {
            lines.add(List.copyOf(segments));
        }

        @Override public void appendMixed(List<MessagePanel.Segment> segments, int wrapWidthInset) {
            appendMixed(segments);
        }

        @Override public void appendLine(String text, TextColor color) {
            appendMixed(List.of(new MessagePanel.Segment(text, color)));
        }

        @Override public void appendLine(String text, TextColor color, int wrapWidthInset) {
            appendLine(text, color);
        }

        private List<String> textLines() {
            return lines.stream()
                .map(line -> line.stream().map(MessagePanel.Segment::text)
                    .reduce("", String::concat))
                .toList();
        }
    }
}
