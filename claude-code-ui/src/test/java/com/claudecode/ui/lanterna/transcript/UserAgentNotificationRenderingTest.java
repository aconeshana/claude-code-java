package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression coverage for the compact background-task notification projection. */
class UserAgentNotificationRenderingTest {

    @Test
    void completedNotificationRendersOnlyItsSummary() {
        StubPanel panel = new StubPanel();
        String notification = """
            <task-notification>
            <task-id>a771993e20a2e1350</task-id>
            <tool-use-id>call_secret</tool-use-id>
            <output-file>/private/tmp/claude/tasks/secret.output</output-file>
            <status>completed</status>
            <summary>Agent \"(resumed)\" finished</summary>
            <note>Internal protocol details</note>
            <result>Very long background result that Claude will synthesize.</result>
            </task-notification>""";

        new LanternaMessageDispatcher().dispatch(
            new SDKMessage.User(MessageFactory.createUserMessage(notification)), panel);

        assertEquals(List.of("", "⏺ Agent \"(resumed)\" finished"), panel.textLines());
        assertEquals(LanternaTheme.toolSuccess(), panel.lines.get(1).getFirst().color());
        assertFalse(Strings.CS.contains(panel.allText(), "task-id"));
        assertFalse(Strings.CS.contains(panel.allText(), "tool-use-id"));
        assertFalse(Strings.CS.contains(panel.allText(), "output-file"));
        assertFalse(Strings.CS.contains(panel.allText(), "Very long background result"));
    }

    @Test
    void failedAndKilledNotificationsUseReleasedStatusColors() {
        StubPanel failed = render("failed", "Agent failed");
        StubPanel killed = render("killed", "Agent stopped");

        assertEquals(LanternaTheme.toolError(), failed.lines.get(1).getFirst().color());
        assertEquals(LanternaTheme.toolWarning(), killed.lines.get(1).getFirst().color());
    }

    @Test
    void notificationWithoutSummaryStaysModelOnly() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(
            new SDKMessage.User(MessageFactory.createUserMessage("""
                <task-notification>
                <status>completed</status>
                <result>model-only result</result>
                </task-notification>""")), panel);

        assertEquals(List.of(), panel.textLines());
    }

    private static StubPanel render(String status, String summary) {
        StubPanel panel = new StubPanel();
        String notification = "<task-notification><status>" + status
            + "</status><summary>" + summary + "</summary></task-notification>";
        new LanternaMessageDispatcher().dispatch(
            new SDKMessage.User(MessageFactory.createUserMessage(notification)), panel);
        return panel;
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

        private String allText() {
            return String.join("\n", textLines());
        }
    }
}
