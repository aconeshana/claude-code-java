package com.claudecode.ui.lanterna.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Golden checks covering:
 * <ul>
 *   <li>line, word,
 *       decoration, daltonized, and ANSI diff palettes.</li>
 * </ul>
 */
class DiffRenderPaletteTest {

    @AfterEach
    void resetTheme() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
    }

    @Test
    void darkUsesOriginalDedicatedBackgrounds() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        var palette = LanternaTheme.diffRenderPalette();

        if (LanternaTheme.chalkLevel() >= 3) {
            assertEquals(new TextColor.RGB(2, 40, 0), palette.addedLineBackground());
            assertEquals(new TextColor.RGB(4, 71, 0), palette.addedWordBackground());
        } else {
            assertEquals(new TextColor.Indexed(22), palette.addedLineBackground());
            assertEquals(new TextColor.Indexed(28), palette.addedWordBackground());
        }
        assertEquals(LanternaTheme.toLC(new RgbColor(61, 1, 0)),
            palette.removedLineBackground());
        assertEquals(LanternaTheme.toLC(new RgbColor(92, 2, 0)),
            palette.removedWordBackground());
    }

    @Test
    void daltonizedDarkUsesBlueAdditionBackgrounds() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK_DALTONIZED);
        var palette = LanternaTheme.diffRenderPalette();

        if (LanternaTheme.chalkLevel() >= 3) {
            assertEquals(new TextColor.RGB(0, 27, 41), palette.addedLineBackground());
            assertEquals(new TextColor.RGB(0, 48, 71), palette.addedWordBackground());
        } else {
            assertEquals(new TextColor.Indexed(17), palette.addedLineBackground());
            assertEquals(new TextColor.Indexed(24), palette.addedWordBackground());
        }
    }

    @Test
    void lightUsesOriginalPastelBackgrounds() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT);
        var palette = LanternaTheme.diffRenderPalette();

        assertEquals(LanternaTheme.toLC(new RgbColor(220, 255, 220)),
            palette.addedLineBackground());
        assertEquals(LanternaTheme.toLC(new RgbColor(255, 220, 220)),
            palette.removedLineBackground());
    }

    @Test
    void ansiThemesKeepDefaultBackgroundLikeOriginal() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK_ANSI);
        var palette = LanternaTheme.diffRenderPalette();

        assertNull(palette.addedLineBackground());
        assertNull(palette.removedLineBackground());
        assertEquals(TextColor.ANSI.GREEN_BRIGHT, palette.addedDecoration());
        assertEquals(TextColor.ANSI.RED_BRIGHT, palette.removedDecoration());
    }
}
