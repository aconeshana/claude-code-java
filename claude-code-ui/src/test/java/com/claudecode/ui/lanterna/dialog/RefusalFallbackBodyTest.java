package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.message.RefusalErrorMessage;
import com.claudecode.core.message.RefusalFallbackPromptCopy;
import com.claudecode.ui.lanterna.dialog.RefusalFallbackBody.Run;
import com.googlecode.lanterna.SGR;
import java.util.Set;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pause dialog's body: the {@code learn more} split and run-preserving wrapping. */
class RefusalFallbackBodyTest {

    private static final String MODEL = "claude-opus-4-5-20251101";
    private static final String CONTENT = RefusalFallbackPromptCopy.body(MODEL, null);

    private static String plain(List<Run> runs) {
        StringBuilder text = new StringBuilder();
        runs.forEach(run -> text.append(run.text()));
        return text.toString();
    }

    private static String plain(List<List<Run>> lines, String separator) {
        return String.join(separator, lines.stream().map(RefusalFallbackBodyTest::plain).toList());
    }

    @Test
    void withoutHyperlinkSupportTheWholeBodyStaysOneBoldRun() {
        List<Run> runs = RefusalFallbackBody.runs(CONTENT, false);

        assertEquals(1, runs.size());
        assertEquals(CONTENT, runs.getFirst().text());
        assertNull(runs.getFirst().hyperlinkUrl());
        assertEquals(Set.of(SGR.BOLD), runs.getFirst().modifiers());
        assertTrue(Strings.CS.contains(CONTENT, RefusalErrorMessage.LEARN_MORE_URL),
            "the announcement body always ends with the bare URL");
    }

    @Test
    void withHyperlinkSupportTheMarkerCollapsesIntoAnUnderlinedLink() {
        List<Run> runs = RefusalFallbackBody.runs(CONTENT, true);

        Run link = runs.stream().filter(run -> run.hyperlinkUrl() != null).findFirst()
            .orElseThrow(() -> new AssertionError("no hyperlink run: " + runs));
        assertEquals("learn more", link.text());
        assertEquals(RefusalErrorMessage.LEARN_MORE_URL, link.hyperlinkUrl());
        assertEquals(Set.of(SGR.BOLD, SGR.UNDERLINE), link.modifiers());

        String visible = plain(runs);
        assertFalse(Strings.CS.contains(visible, RefusalErrorMessage.LEARN_MORE_URL),
            "the URL is carried by the link, not shown: " + visible);
        assertEquals(CONTENT.substring(0,
            CONTENT.indexOf("learn more: " + RefusalErrorMessage.LEARN_MORE_URL)) + "learn more",
            visible);
    }

    @Test
    void bodyWithoutTheMarkerStaysOneRunEvenWhenHyperlinksWork() {
        String content = "Safeguards flagged this message.";

        List<Run> runs = RefusalFallbackBody.runs(content, true);

        assertEquals(1, runs.size());
        assertEquals(content, runs.getFirst().text());
        assertNull(runs.getFirst().hyperlinkUrl());
    }

    @Test
    void wrappingKeepsEveryLineWithinTheWidth() {
        List<List<Run>> lines = RefusalFallbackBody.lines(CONTENT, true, 40);

        assertTrue(lines.size() > 1, "the body is longer than 40 columns");
        for (List<Run> line : lines) {
            assertTrue(plain(line).length() <= 40, "line overflows: " + plain(line));
        }
        assertEquals(CONTENT.substring(0,
            CONTENT.indexOf("learn more: " + RefusalErrorMessage.LEARN_MORE_URL)) + "learn more",
            plain(lines, " "));
    }

    @Test
    void aLinkStraddlingAWrapKeepsItsUrlOnBothLines() {
        // 'learn more' is the last text in the body, so a width that lands the wrap
        // between its two words splits the link run itself.
        String head = CONTENT.substring(0,
            CONTENT.indexOf("learn more: " + RefusalErrorMessage.LEARN_MORE_URL));
        int width = head.length() + "learn".length();

        List<List<Run>> lines = RefusalFallbackBody.lines(CONTENT, true, width);

        List<Run> tail = lines.getLast();
        assertEquals("more", plain(tail));
        assertEquals(RefusalErrorMessage.LEARN_MORE_URL, tail.getFirst().hyperlinkUrl());
        Run linkHead = lines.get(lines.size() - 2).getLast();
        assertEquals("learn", linkHead.text());
        assertEquals(RefusalErrorMessage.LEARN_MORE_URL, linkHead.hyperlinkUrl());
    }

    @Test
    void aWordLongerThanTheWidthIsLeftIntact() {
        // Without hyperlink support the bare URL is a single unbreakable word, exactly
        // as DialogText.wrapWords treats over-long words.
        List<List<Run>> lines = RefusalFallbackBody.lines(CONTENT, false, 40);

        assertTrue(lines.stream().anyMatch(
                line -> plain(line).equals(RefusalErrorMessage.LEARN_MORE_URL)),
            "the URL should occupy a line of its own: " + lines);
    }

    @Test
    void adjacentCharactersOfOneStyleCoalesceIntoASingleRun() {
        List<List<Run>> lines = RefusalFallbackBody.lines(CONTENT, true, 200);

        assertEquals(1, lines.size());
        List<Run> line = lines.getFirst();
        assertEquals(2, line.size(), "head plus link, nothing more: " + line);
        assertNull(line.getFirst().hyperlinkUrl());
        assertNotNull(line.get(1).hyperlinkUrl());
    }
}
