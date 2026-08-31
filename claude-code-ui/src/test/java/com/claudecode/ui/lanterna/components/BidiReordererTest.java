package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BidiReorderer}. Bidi is gated off on macOS by default, so we
 * force-enable it via the system property to exercise the reorder path here.
 */
class BidiReordererTest {

    @BeforeAll
    static void forceEnable() {
        System.setProperty("claude.code.bidi", "true");
    }

    @Test
    void containsBidiDetectsHebrewAndArabic() {
        assertFalse(BidiReorderer.containsBidi("hello world"));
        assertTrue(BidiReorderer.containsBidi("שלום"));
        assertTrue(BidiReorderer.containsBidi("مرحبا"));
    }

    @Test
    void noBidiReturnsInputUnchanged() {
        String plain = "hello world";
        assertEquals(plain, BidiReorderer.reorder(plain));
        String ansi = "\u001B[31mred\u001B[0m text";
        assertEquals(ansi, BidiReorderer.reorder(ansi));
    }

    @Test
    void uniformRtlRunIsReversed() {
        // A uniform RTL run (Hebrew Alef-Bet-Gimel) reorders to Gimel-Bet-Alef.
        String logical = "אבג";
        String visual = BidiReorderer.reorder(logical);
        assertEquals("גבא", visual);
    }

    @Test
    void ansiSequencesArePreservedDuringReorder() {
        String input = "\u001B[31m" + "אבג" + "\u001B[0m";
        String out = BidiReorderer.reorder(input);
        // Same length (ANSI preserved), and both SGR codes survive.
        assertEquals(input.length(), out.length());
        assertTrue(Strings.CS.contains(out, "\u001B[31m"), "leading SGR preserved: " + out);
        assertTrue(Strings.CS.contains(out, "\u001B[0m"), "trailing SGR preserved: " + out);
        // All three Hebrew letters still present (as a multiset).
        assertTrue(Strings.CS.contains(out, "א"), out);
        assertTrue(Strings.CS.contains(out, "ב"), out);
        assertTrue(Strings.CS.contains(out, "ג"), out);
    }

    @Test
    void newlinesSplitParagraphs() {
        String input = "\u001B[1m" + "א" + "\u001B[0m" + "\n" + "x";
        String out = BidiReorderer.reorder(input);
        assertTrue(Strings.CS.contains(out, "\n"), out);
        assertTrue(Strings.CS.contains(out, "\u001B[1m"), out);
    }
}
