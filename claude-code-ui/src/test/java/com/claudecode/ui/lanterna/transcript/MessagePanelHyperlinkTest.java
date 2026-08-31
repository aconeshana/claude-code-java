package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.constants.Figures;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the OSC 8 hyperlink parser in {@link MessagePanel}'s drawLine
 * correctly extracts URLs from embedded escape codes and attaches them
 * to TextCharacter via withHyperlink.
 */
public class MessagePanelHyperlinkTest {

    @Test
    public void hyperlinkFactoryCreatesSegmentWithUrl() {
        MessagePanel.Segment seg = MessagePanel.Segment.hyperlink(
            "click here", TextColor.ANSI.CYAN, "https://example.com");
        assertEquals("click here", seg.text());
        assertEquals("https://example.com", seg.hyperlinkUrl());
    }

    @Test
    public void regularSegmentHasNullHyperlink() {
        MessagePanel.Segment seg = new MessagePanel.Segment("plain", TextColor.ANSI.WHITE);
        assertNull(seg.hyperlinkUrl());
    }

    @Test
    public void segmentWithBgColorAndHyperlink() {
        MessagePanel.Segment seg = new MessagePanel.Segment(
            "text", TextColor.ANSI.WHITE, TextColor.ANSI.BLUE, "https://link.com");
        assertEquals("text", seg.text());
        assertEquals(TextColor.ANSI.BLUE, seg.bgColor());
        assertEquals("https://link.com", seg.hyperlinkUrl());
    }

    @Test
    public void mixedStyleOverflowKeepsPrefixAndBodyOnTheSameFirstRow() {
        List<MessagePanel.StyledLine> rows = MessagePanel.wrapStyledSegments(List.of(
            new MessagePanel.Segment(Figures.RESULT_PREFIX, TextColor.ANSI.WHITE),
            new MessagePanel.Segment("Error: Permission denied", TextColor.ANSI.RED)), 16);

        assertEquals(Figures.RESULT_PREFIX + "Error:", rows.getFirst().text());
        assertEquals("     Permission", rows.get(1).text());
        assertEquals("     denied", rows.get(2).text());
        assertEquals(2, rows.getFirst().segments().size(),
            "the first wrapped row must preserve separate prefix/error colors");
        assertEquals("     ", rows.get(1).segments().getFirst().text(),
            "tool-result continuations retain the released five-column hanging indent");
        assertEquals(TextColor.ANSI.WHITE, rows.getFirst().segments().getFirst().color());
        assertEquals(TextColor.ANSI.RED, rows.getFirst().segments().get(1).color());
    }

    @Test
    public void informationalDotUsesWordBoundariesAndTwoColumnHangingIndent() {
        String content = "Auto mode lets Claude handle permission prompts automatically — Claude "
            + "checks each tool call for risky actions and prompt injection before executing.";

        List<MessagePanel.StyledLine> rows = MessagePanel.wrapStyledSegments(List.of(
            new MessagePanel.Segment("⏺ ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(content, TextColor.ANSI.DEFAULT)), 80, 10);

        assertEquals(3, rows.size());
        assertEquals("⏺ Auto mode lets Claude handle permission prompts automatically — Claude",
            rows.getFirst().text());
        assertEquals("  checks each tool call for risky actions and prompt injection before",
            rows.get(1).text());
        assertEquals("  executing.", rows.get(2).text());
        assertEquals("  ", rows.get(1).segments().getFirst().text(),
            "released informational rows retain the BLACK_CIRCLE gutter");
    }

    @Test
    public void wrappingPreservesMarkdownModifiersOnEverySplitSegment() {
        MessagePanel.Segment bold = new MessagePanel.Segment(
            "abcdefgh", TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD));

        List<MessagePanel.StyledLine> rows =
            MessagePanel.wrapStyledSegments(List.of(bold), 4);

        assertEquals(List.of("abcd", "efgh"), rows.stream()
            .map(MessagePanel.StyledLine::text).toList());
        assertTrue(rows.stream().flatMap(row -> row.segments().stream())
            .allMatch(segment -> segment.modifiers().contains(SGR.BOLD)));
    }
}
