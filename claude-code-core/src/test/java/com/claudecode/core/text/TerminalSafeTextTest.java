package com.claudecode.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSafeTextTest {

    private static final String ESC = String.valueOf((char) 0x1B);
    private static final String BEL = String.valueOf((char) 0x07);
    private static final String SOH = String.valueOf((char) 0x01);
    private static final String DEL = String.valueOf((char) 0x7F);
    private static final String C1 = String.valueOf((char) 0x9B);

    @Test
    void stripsSgrAndCursorSequences() {
        assertEquals("red text", TerminalSafeText.sanitize(ESC + "[31mred" + ESC + "[0m text"));
        assertEquals("home", TerminalSafeText.sanitize(ESC + "[2J" + ESC + "[Hhome"));
    }

    @Test
    void stripsOscHyperlinksTerminatedByBelOrSt() {
        assertEquals("label", TerminalSafeText.sanitize(
            ESC + "]8;;https://example.com" + BEL + "label" + ESC + "]8;;" + BEL));
        assertEquals("title", TerminalSafeText.sanitize(ESC + "]0;window" + ESC + "\\title"));
    }

    @Test
    void dropsUnterminatedSequenceTail() {
        assertEquals("keep", TerminalSafeText.sanitize("keep" + ESC + "[31"));
        assertEquals("keep", TerminalSafeText.sanitize("keep" + ESC + "]8;;https://example.com"));
    }

    @Test
    void removesRemainingControlCharactersButKeepsNewlines() {
        assertEquals("ab", TerminalSafeText.sanitize("a" + SOH + "b"));
        assertEquals("a\nb", TerminalSafeText.sanitize("a\r\nb"));
        assertEquals("a\nb", TerminalSafeText.sanitize("a\rb"));
        assertEquals("ab", TerminalSafeText.sanitize("a" + DEL + "b"));
        assertEquals("ab", TerminalSafeText.sanitize("a" + C1 + "b"));
        assertEquals("ab", TerminalSafeText.sanitize("a" + BEL + "b"));
    }

    @Test
    void expandsTabsToColumnStops() {
        assertEquals("a       b", TerminalSafeText.sanitize("a\tb"));
    }

    @Test
    void preservesOrdinaryAndWideText() {
        assertEquals("中文 emoji 🎉 ok", TerminalSafeText.sanitize("中文 emoji 🎉 ok"));
    }

    @Test
    void isNullSafeAndReturnsCleanTextUnchanged() {
        assertNull(TerminalSafeText.sanitize(null));
        String clean = "plain line";
        assertSame(clean, TerminalSafeText.sanitize(clean));
    }

    @Test
    void singleLineVariantFoldsBreaksAndMapsNullToEmpty() {
        assertEquals("a b", TerminalSafeText.sanitizeLine("a\r\nb"));
        assertEquals("", TerminalSafeText.sanitizeLine(null));
    }

    @Test
    void resultContainsNoCharacterLanternaRefusesToRender() {
        String raw = ESC + "[1;33m⏺" + ESC + "[0m tool output" + ESC + "[2K\ttail" + DEL;
        String safe = TerminalSafeText.sanitize(raw);
        safe.codePoints().forEach(cp ->
            assertTrue(cp == '\n' || cp >= 0x20 && cp != 0x7F && !(cp >= 0x80 && cp <= 0x9F),
                () -> "unexpected control code point: " + cp));
    }
}
