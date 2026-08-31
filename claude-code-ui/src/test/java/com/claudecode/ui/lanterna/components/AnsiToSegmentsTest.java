package com.claudecode.ui.lanterna.components;


import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Regression coverage for {@link AnsiToSegments}' SGR and OSC 8 handling,
 * including damaged line-leading SGR, the ST ({@code ESC \}) terminator, and
 * nested-SGR link text in status-line output.
 */
class AnsiToSegmentsTest {

    @Test
    void recoversLineLeadingSgrWhenEscapeByteWasLost() {
        List<List<MessagePanel.Segment>> lines = AnsiToSegments.ansiToLines(
            "[1m\u4fee\u590d\u5185\u5bb9\u001B[0m", TextColor.ANSI.DEFAULT);

        assertEquals(1, lines.size());
        assertEquals(1, lines.getFirst().size());
        MessagePanel.Segment segment = lines.getFirst().getFirst();
        assertEquals("\u4fee\u590d\u5185\u5bb9", segment.text());
        assertTrue(segment.modifiers().contains(SGR.BOLD));
    }

    private static final String ESC = "";
    private static final String BEL = "";
    private static final TextColor DIM = LanternaTheme.welcomeDim();

    private static boolean hasControlChar(MessagePanel.Segment s) {
        for (char c : s.text().toCharArray()) if (c < 0x20 && c != '\t') return true;
        return false;
    }

    @Test
    void stTerminatedHyperlinkParsesAndKeepsTail() {
        // ESC]8;;URL ESC\  TEXT  ESC]8;; ESC\   tail
        String in = ESC + "]8;;http://x" + ESC + "\\" + "LINK" + ESC + "]8;;" + ESC + "\\" + " tail";
        List<List<MessagePanel.Segment>> lines = AnsiToSegments.ansiToLines(in, DIM);
        assertEquals(1, lines.size());
        String joined = lines.getFirst().stream().map(MessagePanel.Segment::text)
            .reduce("", String::concat);
        assertTrue(Strings.CS.contains(joined, "LINK"), "hyperlink text kept: " + joined);
        assertTrue(Strings.CS.contains(joined, "tail"), "text after ST-terminated OSC not dropped: " + joined);
    }

    @Test
    void belTerminatedHyperlinkStillWorks() {
        // The legacy BEL terminator path (MarkdownRenderer output) must be intact.
        String in = ESC + "]8;;http://x" + BEL + "LINK" + ESC + "]8;;" + BEL;
        List<List<MessagePanel.Segment>> lines = AnsiToSegments.ansiToLines(in, DIM);
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().stream().anyMatch(s -> Strings.CS.equals("LINK", s.text())
            && Strings.CS.equals("http://x", s.hyperlinkUrl())));
    }

    @Test
    void nestedSgrInLinkTextIsParsedNotLeaked() {
        // Colored link text: ESC]8;;URL ESC\ ESC[33m TEXT ESC[0m ESC]8;; ESC\
        String in = ESC + "]8;;http://x" + ESC + "\\"
            + ESC + "[33m" + "PROJ" + ESC + "[0m"
            + ESC + "]8;;" + ESC + "\\";
        List<List<MessagePanel.Segment>> lines = AnsiToSegments.ansiToLines(in, DIM);
        assertEquals(1, lines.size());
        List<MessagePanel.Segment> segs = lines.getFirst();
// No raw ESC/control bytes compatibility baselineinto any segment text.
        assertTrue(segs.stream().noneMatch(AnsiToSegmentsTest::hasControlChar),
            "no control chars should leak into segments");
        // The link text renders yellow (SGR 33) and keeps the URL.
        assertTrue(segs.stream().anyMatch(s -> Strings.CS.contains(s.text(), "PROJ")
            && TextColor.ANSI.YELLOW.equals(s.color())
            && Strings.CS.equals("http://x", s.hyperlinkUrl())),
            "PROJ should be a yellow hyperlink segment");
    }

    @Test
    void multiLineWithStHyperlinkSplitsIntoTwoLines() {
        String line1 = ESC + "[36m" + "[Model]" + ESC + "[0m" + " " + ESC + "]8;;http://r" + ESC + "\\"
            + ESC + "[33m" + "repo" + ESC + "[0m" + ESC + "]8;;" + ESC + "\\";
        String line2 = ESC + "[32m" + "Context 50%" + ESC + "[0m";
        List<List<MessagePanel.Segment>> lines = AnsiToSegments.ansiToLines(line1 + "\n" + line2, DIM);
        assertEquals(2, lines.size(), "claude-hud-style two-line HUD");
        assertTrue(lines.getFirst().stream().anyMatch(s -> Strings.CS.contains(s.text(), "repo")));
        assertTrue(lines.get(1).stream().anyMatch(s -> Strings.CS.contains(s.text(), "Context 50%")));
    }

    @Test
    void markdownSgrModifiersSurviveAnsiToSegmentConversion() {
        String in = ESC + "[1m" + "bold" + ESC + "[0m"
            + " " + ESC + "[3m" + "italic" + ESC + "[0m"
            + " " + ESC + "[4m" + "underlined" + ESC + "[0m";

        List<MessagePanel.Segment> segments =
            AnsiToSegments.ansiToLines(in, TextColor.ANSI.DEFAULT).getFirst();

        assertTrue(segments.stream().anyMatch(segment -> Strings.CS.equals("bold", segment.text())
            && segment.modifiers().contains(SGR.BOLD)));
        assertTrue(segments.stream().anyMatch(segment -> Strings.CS.equals("italic", segment.text())
            && segment.modifiers().contains(SGR.ITALIC)));
        assertTrue(segments.stream().anyMatch(segment -> Strings.CS.equals("underlined", segment.text())
            && segment.modifiers().contains(SGR.UNDERLINE)));
    }

    @Test
    void activeSgrStyleContinuesAcrossNewlinesUntilExplicitReset() {
        String in = ESC + "[1m" + "first\nsecond" + ESC + "[0m";

        List<List<MessagePanel.Segment>> lines =
            AnsiToSegments.ansiToLines(in, TextColor.ANSI.DEFAULT);

        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().stream().allMatch(segment -> segment.modifiers().contains(SGR.BOLD)));
        assertTrue(lines.get(1).stream().allMatch(segment -> segment.modifiers().contains(SGR.BOLD)));
    }
}
