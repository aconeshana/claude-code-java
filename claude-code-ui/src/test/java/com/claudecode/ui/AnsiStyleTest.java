package com.claudecode.ui;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.AnsiColor;
import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.googlecode.lanterna.TextColor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ANSI style and color utilities.
 */
class AnsiStyleTest {

    @Test
    void styledAppliesBoldCodes() {
        String result = Ansi.styled("hello", AnsiStyle.BOLD);
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.contains(result, "\u001B[1m"), "Should contain bold on code");
            assertTrue(Strings.CS.contains(result, "\u001B[0m"), "Should contain reset code");
            assertTrue(Strings.CS.contains(result, "hello"));
        } else {
            assertEquals("hello", result);
        }
    }

    @Test
    void styledAppliesMultipleStyles() {
        String result = Ansi.styled("text", AnsiStyle.BOLD, AnsiStyle.ITALIC);
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.contains(result, "\u001B[1m"));
            assertTrue(Strings.CS.contains(result, "\u001B[3m"));
            assertTrue(Strings.CS.contains(result, "text"));
        } else {
            assertEquals("text", result);
        }
    }

    @Test
    void styledWithNoStylesReturnsPlainText() {
        String result = Ansi.styled("plain");
        assertEquals("plain", result);
    }

    @Test
    void coloredAppliesColorCode() {
        String result = Ansi.colored("red text", AnsiColor.RED);
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.contains(result, "\u001B[31m"), "Should contain red color code");
            assertTrue(Strings.CS.contains(result, "red text"));
            assertTrue(Strings.CS.contains(result, "\u001B[0m"), "Should contain reset");
        } else {
            assertEquals("red text", result);
        }
    }

    @Test
    void styledWithColorAndStyles() {
        String result = Ansi.styled("fancy", AnsiColor.GREEN, AnsiStyle.BOLD, AnsiStyle.UNDERLINE);
        if (Ansi.isColorSupported()) {
            assertTrue(Strings.CS.contains(result, "\u001B[32m"), "Should contain green code");
            assertTrue(Strings.CS.contains(result, "\u001B[1m"), "Should contain bold code");
            assertTrue(Strings.CS.contains(result, "\u001B[4m"), "Should contain underline code");
            assertTrue(Strings.CS.contains(result, "fancy"));
        } else {
            assertEquals("fancy", result);
        }
    }

    @Test
    void rgbForegroundOpenerAlwaysStartsWithEscape() {
        String opener = Ansi.rgbForegroundOpener(230, 219, 116);
        assertTrue(Strings.CS.startsWith(opener, "\u001B[38;"));
        assertTrue(Strings.CS.endsWith(opener, "m"));
    }

    @Test
    void ansiForegroundOpenerIncludesEscapePrefix() {
        RgbColor red = new RgbColor(170, 0, 0, TextColor.ANSI.RED);
        assertEquals("\u001B[31m", Ansi.foregroundOpener(red));
    }

    @Test
    void allAnsiStylesHaveOnAndOffCodes() {
        for (AnsiStyle style : AnsiStyle.values()) {
            assertNotNull(style.on(), style.name() + " should have on code");
            assertNotNull(style.off(), style.name() + " should have off code");
            assertTrue(Strings.CS.startsWith(style.on(), "\u001B["), style.name() + " on code should be ANSI escape");
            assertTrue(Strings.CS.startsWith(style.off(), "\u001B["), style.name() + " off code should be ANSI escape");
        }
    }

    @Test
    void allAnsiColorsHaveCodes() {
        for (AnsiColor color : AnsiColor.values()) {
            assertNotNull(color.code(), color.name() + " should have color code");
            assertTrue(Strings.CS.startsWith(color.code(), "\u001B["), color.name() + " code should be ANSI escape");
        }
    }

    @Test
    void ansiColorEnumHasExpectedValues() {
        assertEquals(8, AnsiColor.values().length);
        assertNotNull(AnsiColor.RED);
        assertNotNull(AnsiColor.GREEN);
        assertNotNull(AnsiColor.YELLOW);
        assertNotNull(AnsiColor.BLUE);
        assertNotNull(AnsiColor.MAGENTA);
        assertNotNull(AnsiColor.CYAN);
        assertNotNull(AnsiColor.WHITE);
        assertNotNull(AnsiColor.GRAY);
    }

    @Test
    void ansiStyleEnumHasExpectedValues() {
        assertEquals(5, AnsiStyle.values().length);
        assertNotNull(AnsiStyle.BOLD);
        assertNotNull(AnsiStyle.DIM);
        assertNotNull(AnsiStyle.ITALIC);
        assertNotNull(AnsiStyle.UNDERLINE);
        assertNotNull(AnsiStyle.STRIKETHROUGH);
    }
}
