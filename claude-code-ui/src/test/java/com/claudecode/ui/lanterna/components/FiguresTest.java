package com.claudecode.ui.lanterna.components;

import java.util.Locale;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.constants.Figures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FiguresTest {

    @Test
    void blackCircle_matchesPlatformDispatch() {
        // macOS gets the vertically-aligned ⏺ (U+23FA), other platforms get ●.
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isDarwin = Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin");
        if (isDarwin) {
            assertEquals("⏺", Figures.BLACK_CIRCLE);
        } else {
            assertEquals("●", Figures.BLACK_CIRCLE);
        }
    }

    @Test
    void arrowsHaveCorrectCodepoints() {
        assertEquals(0x2191, Figures.UP_ARROW.codePointAt(0));
        assertEquals(0x2193, Figures.DOWN_ARROW.codePointAt(0));
        assertEquals(0x21af, Figures.LIGHTNING_BOLT.codePointAt(0));
    }

    @Test
    void effortLevels_useDistinctGlyphs() {
        assertNotEquals(Figures.EFFORT_LOW, Figures.EFFORT_MEDIUM);
        assertNotEquals(Figures.EFFORT_MEDIUM, Figures.EFFORT_HIGH);
        assertNotEquals(Figures.EFFORT_HIGH, Figures.EFFORT_MAX);

        assertEquals(0x25cb, Figures.EFFORT_LOW.codePointAt(0));
        assertEquals(0x25d0, Figures.EFFORT_MEDIUM.codePointAt(0));
        assertEquals(0x25cf, Figures.EFFORT_HIGH.codePointAt(0));
        assertEquals(0x25c9, Figures.EFFORT_MAX.codePointAt(0));
    }

    @Test
    void mcpIndicators_codepointsExact() {
        assertEquals(0x2190, Figures.CHANNEL_ARROW.codePointAt(0));
    }

    @Test
    void diamondStates_areClearlyDistinct() {
        assertEquals(0x25c7, Figures.DIAMOND_OPEN.codePointAt(0));
        assertEquals(0x25c6, Figures.DIAMOND_FILLED.codePointAt(0));
    }

    @Test
    void pointerGlyphs_matchFiguresNpmPackage() {
        // ❯ U+276F and › U+203A — from npm figures package, used by UserCommandMessage,
        // UserPromptMessage, BriefTool/UI, FullscreenLayout, etc.
        assertEquals(0x276f, Figures.POINTER.codePointAt(0));
        assertEquals(0x203a, Figures.POINTER_SMALL.codePointAt(0));
    }

    @Test
    void mediaIcons_codepointsExact() {
        assertEquals(0x25b6, Figures.PLAY_ICON.codePointAt(0));
        assertEquals(0x23f8, Figures.PAUSE_ICON.codePointAt(0));
    }

    @Test
    void blockquoteAndFlag_codepointsExact() {
        assertEquals(0x258e, Figures.BLOCKQUOTE_BAR.codePointAt(0));
        assertEquals(0x203b, Figures.REFERENCE_MARK.codePointAt(0));
    }
}
