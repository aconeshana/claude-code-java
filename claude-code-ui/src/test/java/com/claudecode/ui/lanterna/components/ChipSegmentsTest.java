package com.claudecode.ui.lanterna.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.googlecode.lanterna.TextColor;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/** Unit tests for the stateless {@link ChipSegments} chip-splitting util. */
class ChipSegmentsTest {

    private static final TextColor BASE = TextColor.ANSI.WHITE;
    private static final TextColor CHIP = TextColor.ANSI.CYAN;
    private static final TextColor BG   = TextColor.ANSI.BLACK;

    @Test
    void plainText_yieldsSingleBaseSegment() {
        List<MessagePanel.Segment> segs = ChipSegments.of(" ❯ hello world", BASE, CHIP, BG);
        assertEquals(1, segs.size());
        assertEquals(" ❯ hello world", segs.getFirst().text());
        assertSame(BASE, segs.getFirst().color());
        assertSame(BG, segs.getFirst().bgColor());
    }

    @Test
    void imageChipInMiddle_splitsIntoThree() {
        List<MessagePanel.Segment> segs =
            ChipSegments.of("see [Image #1] here", BASE, CHIP, BG);
        assertEquals(3, segs.size());
        assertEquals("see ", segs.getFirst().text());
        assertSame(BASE, segs.getFirst().color());
        assertEquals("[Image #1]", segs.get(1).text());
        assertSame(CHIP, segs.get(1).color(), "chip painted in chip color");
        assertEquals(" here", segs.get(2).text());
        assertSame(BASE, segs.get(2).color());
    }

    @Test
    void chipAtStart_yieldsChipThenText() {
        List<MessagePanel.Segment> segs =
            ChipSegments.of("[Image #2] trailing", BASE, CHIP, BG);
        assertEquals(2, segs.size());
        assertEquals("[Image #2]", segs.getFirst().text());
        assertSame(CHIP, segs.getFirst().color());
        assertEquals(" trailing", segs.get(1).text());
    }

    @Test
    void pastedTextChipWithLineCount_isRecognized() {
        List<MessagePanel.Segment> segs =
            ChipSegments.of("[Pasted text #3 +42 lines]", BASE, CHIP, BG);
        assertEquals(1, segs.size());
        assertEquals("[Pasted text #3 +42 lines]", segs.getFirst().text());
        assertSame(CHIP, segs.getFirst().color(), "whole line is one chip");
    }

    @Test
    void everyChipCarriesBackground() {
        List<MessagePanel.Segment> segs =
            ChipSegments.of("a [Image #1] b", BASE, CHIP, BG);
        for (MessagePanel.Segment s : segs) {
            assertSame(BG, s.bgColor(), "background applies to every segment");
        }
    }
}
