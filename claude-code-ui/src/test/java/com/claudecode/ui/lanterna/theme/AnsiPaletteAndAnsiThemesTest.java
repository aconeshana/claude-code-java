package com.claudecode.ui.lanterna.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnsiPaletteAndAnsiThemesTest {

    // ── AnsiPalette ─────────────────────────────────────────────────────────

    @Test
    void resolve_basicSixteenColors() {
        // VGA palette anchors.
        assertEquals(new RgbColor(0, 0, 0),       AnsiPalette.resolve("ansi:black"));
        assertEquals(new RgbColor(170, 0, 0),     AnsiPalette.resolve("ansi:red"));
        assertEquals(new RgbColor(255, 85, 85),   AnsiPalette.resolve("ansi:redBright"));
        assertEquals(new RgbColor(170, 170, 170), AnsiPalette.resolve("ansi:white"));
        assertEquals(new RgbColor(255, 255, 255), AnsiPalette.resolve("ansi:whiteBright"));
    }

    @Test
    void resolve_brightVariants_areBrighter() {
        for (String name : new String[]{"red", "green", "blue", "yellow", "magenta", "cyan"}) {
            RgbColor normal = AnsiPalette.resolve("ansi:" + name);
            RgbColor bright = AnsiPalette.resolve("ansi:" + name + "Bright");
            int normalSum = normal.r() + normal.g() + normal.b();
            int brightSum = bright.r() + bright.g() + bright.b();
            assertTrue(brightSum > normalSum,
                "bright " + name + " must outshine plain (" + brightSum + " > " + normalSum + ")");
        }
    }

    @Test
    void resolve_acceptsRgbLiterals_too() {

        assertEquals(new RgbColor(106, 155, 204), AnsiPalette.resolve("rgb(106,155,204)"));
    }

    @Test
    void resolve_rejectsUnknownTokens() {
        assertThrows(IllegalArgumentException.class, () -> AnsiPalette.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> AnsiPalette.resolve("ansi:fuchsia"));
        assertThrows(IllegalArgumentException.class, () -> AnsiPalette.resolve("#ff0000"));
    }

    // ── LIGHT_ANSI ──────────────────────────────────────────────────────────

    @Test
    void lightAnsi_textIsBlack_inverseIsWhite() {
        assertEquals(AnsiPalette.resolve("ansi:black"), Themes.LIGHT_ANSI.text());
        assertEquals(AnsiPalette.resolve("ansi:white"), Themes.LIGHT_ANSI.inverseText());
    }

    @Test
    void lightAnsi_diffAdded_isPlainGreen() {

        assertEquals(AnsiPalette.resolve("ansi:green"), Themes.LIGHT_ANSI.diffAdded());
        assertEquals(AnsiPalette.resolve("ansi:greenBright"), Themes.LIGHT_ANSI.diffAddedWord());
    }

    @Test
    void lightAnsi_chromeYellow_isPlainYellow_notBright() {

        assertEquals(AnsiPalette.resolve("ansi:yellow"), Themes.LIGHT_ANSI.chromeYellow());
    }

    // ── DARK_ANSI ───────────────────────────────────────────────────────────

    @Test
    void darkAnsi_textIsWhiteBright_inverseIsBlack() {
        assertEquals(AnsiPalette.resolve("ansi:whiteBright"), Themes.DARK_ANSI.text());
        assertEquals(AnsiPalette.resolve("ansi:black"),        Themes.DARK_ANSI.inverseText());
    }

    @Test
    void darkAnsi_keepsRgbProfessionalBlue() {

        assertEquals(new RgbColor(106, 155, 204), Themes.DARK_ANSI.professionalBlue());
    }

    @Test
    void darkAnsi_chromeYellow_isYellowBright() {
        assertEquals(AnsiPalette.resolve("ansi:yellowBright"), Themes.DARK_ANSI.chromeYellow());
    }

    @Test
    void darkAnsi_agents_areBrightVariants() {
        // Dark bg → use bright variants for all 8 agent colors.
        assertEquals(AnsiPalette.resolve("ansi:redBright"),     Themes.DARK_ANSI.red_FOR_SUBAGENTS_ONLY());
        assertEquals(AnsiPalette.resolve("ansi:blueBright"),    Themes.DARK_ANSI.blue_FOR_SUBAGENTS_ONLY());
        assertEquals(AnsiPalette.resolve("ansi:greenBright"),   Themes.DARK_ANSI.green_FOR_SUBAGENTS_ONLY());
        assertEquals(AnsiPalette.resolve("ansi:cyanBright"),    Themes.DARK_ANSI.cyan_FOR_SUBAGENTS_ONLY());
    }

    @Test
    void lightAnsi_agents_arePlainVariants() {
        // Light bg → use plain (non-bright) variants for most agent colors.
        assertEquals(AnsiPalette.resolve("ansi:red"),     Themes.LIGHT_ANSI.red_FOR_SUBAGENTS_ONLY());
        assertEquals(AnsiPalette.resolve("ansi:blue"),    Themes.LIGHT_ANSI.blue_FOR_SUBAGENTS_ONLY());
        assertEquals(AnsiPalette.resolve("ansi:green"),   Themes.LIGHT_ANSI.green_FOR_SUBAGENTS_ONLY());

        assertEquals(AnsiPalette.resolve("ansi:magentaBright"), Themes.LIGHT_ANSI.pink_FOR_SUBAGENTS_ONLY());
    }

    // ── Themes.get registry — all six palettes resolve ──────────────────────

    @Test
    void get_resolvesAllSixPalettes() {
        assertSame(Themes.DARK,             Themes.get(Themes.DARK_NAME));
        assertSame(Themes.LIGHT,            Themes.get(Themes.LIGHT_NAME));
        assertSame(Themes.DARK_DALTONIZED,  Themes.get(Themes.DARK_DALTONIZED_NAME));
        assertSame(Themes.LIGHT_DALTONIZED, Themes.get(Themes.LIGHT_DALTONIZED_NAME));
        assertSame(Themes.DARK_ANSI,        Themes.get(Themes.DARK_ANSI_NAME));
        assertSame(Themes.LIGHT_ANSI,       Themes.get(Themes.LIGHT_ANSI_NAME));
    }
}
