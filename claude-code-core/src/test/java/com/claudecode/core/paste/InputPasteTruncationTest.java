package com.claudecode.core.paste;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.commons.lang3.Strings;











class InputPasteTruncationTest {

    /** Local stand-in so the round-trip test doesn't drag in core's PastedContent. */
    private record Content(int id, String type, String content) implements PastedRefParser.PastedContentLike {}

    @Test
    void belowThreshold_returnsOriginalUnchanged() {
        String text = "a".repeat(5_000);
        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 1);
        assertEquals(text, t.truncatedText());
        assertTrue(t.placeholderContent().isEmpty(), "no middle lifted out below threshold");
    }

    @Test
    void atThreshold_returnsOriginalUnchanged() {
        // Threshold check is `length <= TRUNCATION_THRESHOLD`, so exactly 10000 stays whole.
        String text = "a".repeat(InputPasteTruncation.TRUNCATION_THRESHOLD);
        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 1);
        assertEquals(text, t.truncatedText());
        assertTrue(t.placeholderContent().isEmpty());
    }

    @Test
    void aboveThreshold_truncates() {
        String text = "a".repeat(InputPasteTruncation.TRUNCATION_THRESHOLD + 1);
        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 7);

        int half = InputPasteTruncation.PREVIEW_LENGTH / 2;
        String expectedPlaceholder = text.substring(half, text.length() - half);
        String expectedMarker = InputPasteTruncation.formatTruncatedTextRef(7,
            PastedRefParser.getPastedTextRefNumLines(expectedPlaceholder));

        assertEquals(expectedPlaceholder, t.placeholderContent());
        assertEquals("a".repeat(half) + expectedMarker + "a".repeat(half), t.truncatedText());
        assertTrue(Strings.CS.contains(t.truncatedText(), "[...Truncated text #7 "),
            "display text carries the truncated reference chip");
    }

    @Test
    void nullInput_returnsNullTextEmptyPlaceholder() {
        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(null, 1);
        assertNull(t.truncatedText());
        assertTrue(t.placeholderContent().isEmpty());
    }

    @Test
    void placeholderPreservesMixedNewlinesAndLineCount() {
        // Middle carries mixed \r, \n and \r\n newlines — must survive the lift-out
        // verbatim and the reported line count must match getPastedTextRefNumLines.
        String prefix = "a".repeat(500);
        String suffix = "z".repeat(500);
        int middleLen = 9_100;
        StringBuilder mb = new StringBuilder();
        String block = "b".repeat(100);
        int blocks = middleLen / (block.length() + 2);
        for (int i = 0; i < blocks; i++) {
            mb.append(block).append(i % 3 == 0 ? "\r\n" : (i % 3 == 1 ? "\n" : "\r"));
        }
        while (mb.length() < middleLen) mb.append('b');
        String middle = mb.substring(0, middleLen);
        String text = prefix + middle + suffix;
        assertEquals(10_100, text.length());

        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 7);
        assertEquals(middle, t.placeholderContent(), "lifted middle is byte-identical");

        int expectedLines = PastedRefParser.getPastedTextRefNumLines(middle);
        String marker = InputPasteTruncation.formatTruncatedTextRef(7, expectedLines);
        assertEquals("a".repeat(500) + marker + "z".repeat(500), t.truncatedText());
    }

    @Test
    void roundTrip_expandRestoresOriginal() {
        String text = "a".repeat(500)
            + ("line one\r\nline two\nline three\rLINE FOUR ".repeat(250))
            + "z".repeat(500);
        assertTrue(text.length() > InputPasteTruncation.TRUNCATION_THRESHOLD);

        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 7);
        Map<Integer, Content> pasted = Map.of(7, new Content(7, "text", t.placeholderContent()));

        String expanded = PastedRefParser.expandPastedTextRefs(t.truncatedText(), pasted);
        assertEquals(text, expanded, "submit-side expansion splices the middle back in");
    }

    @Test
    void producedMarkerParsesBackToSameId() {
        String text = "a".repeat(500) + "X".repeat(9_200) + "z".repeat(500);
        InputPasteTruncation.Truncated t = InputPasteTruncation.maybeTruncateMessageForInput(text, 42);

        List<PastedRefParser.Ref> refs = PastedRefParser.parseReferences(t.truncatedText());
        assertEquals(1, refs.size(), "exactly one reference emitted");
        assertEquals(42, refs.getFirst().id());
        assertTrue(Strings.CS.startsWith(refs.getFirst().match(), "[...Truncated text #42 "));
    }
}
