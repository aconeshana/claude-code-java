package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TextColor;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The processing placeholder of a long-running command: shown after the
 * transcript like 236's {@code userInputOnProcessing} row, never recorded as a
 * transcript line, and removed once the command's real rows land.
 */
class MessagePanelTransientRowsTest {

    private static String text(MessagePanel.StyledLine line) {
        return line.segments().stream()
            .map(MessagePanel.Segment::text)
            .collect(Collectors.joining());
    }

    private static List<String> displayed(MessagePanel panel) {
        return panel.displayRowsForTest(80).stream().map(MessagePanelTransientRowsTest::text).toList();
    }

    @Test
    void promptRowsMatchTheUserEchoShape() {
        List<List<MessagePanel.Segment>> rows = UserMessageStyle.promptRows("/recap\nmore");

        assertEquals(3, rows.size());
        assertTrue(rows.getFirst().isEmpty(), "a blank margin row precedes the echo");
        assertEquals("❯ /recap", rows.get(1).stream()
            .map(MessagePanel.Segment::text).collect(Collectors.joining()));
        assertEquals("  more", rows.get(2).stream()
            .map(MessagePanel.Segment::text).collect(Collectors.joining()));
        assertTrue(UserMessageStyle.promptRows("   ").isEmpty());
    }

    @Test
    void transientRowsRenderAfterTheTranscriptWithoutBecomingHistory() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("earlier output", TextColor.ANSI.DEFAULT);
        int lines = panel.snapshotLineCount();

        panel.showTransientRows(UserMessageStyle.promptRows("/recap"));

        assertEquals(lines, panel.snapshotLineCount(), "the placeholder is not a transcript line");
        assertEquals("❯ /recap", displayed(panel).getLast());

        panel.clearTransientTail();
        assertEquals("earlier output", displayed(panel).getLast());
    }
}
