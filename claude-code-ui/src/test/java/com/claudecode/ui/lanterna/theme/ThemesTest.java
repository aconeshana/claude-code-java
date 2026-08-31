package com.claudecode.ui.lanterna.theme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemesTest {

    // ── RgbColor parsing ────────────────────────────────────────────────────

    @Test
    void rgb_parsesCanonicalTsLiteral() {
        RgbColor c = RgbColor.parse("rgb(215,119,87)");
        assertEquals(215, c.r());
        assertEquals(119, c.g());
        assertEquals(87,  c.b());
    }

    @Test
    void rgb_parsesWithSpaces() {

        RgbColor c = RgbColor.parse("rgb(55, 55, 55)");
        assertEquals(new RgbColor(55, 55, 55), c);
    }

    @Test
    void rgb_roundTripsToCanonicalString() {
        assertEquals("rgb(0,0,0)",       new RgbColor(0, 0, 0).toRgbString());
        assertEquals("rgb(255,255,255)", new RgbColor(255, 255, 255).toRgbString());
    }

    @Test
    void rgb_rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new RgbColor(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RgbColor(0, 256, 0));
    }

    @Test
    void rgb_rejectsNonRgbStrings() {
        assertThrows(IllegalArgumentException.class, () -> RgbColor.parse(null));
        assertThrows(IllegalArgumentException.class, () -> RgbColor.parse("#ff0000"));
        assertThrows(IllegalArgumentException.class, () -> RgbColor.parse("rgb(1,2)"));
    }



    @Test
    void darkTheme_claude_isBrandOrange() {
        assertEquals(new RgbColor(215, 119, 87), Themes.DARK.claude());
    }

    @Test
    void darkTheme_userMessageBackground_isLightGrey() {
        // The "lighter grey for better visual contrast" that ships across the
        // user-prompt bubble — see UserMessageStyle.PROMPT_GLYPH context.
        assertEquals(new RgbColor(55, 55, 55), Themes.DARK.userMessageBackground());
    }

    @Test
    void darkTheme_agentColors_areTailwind600Family() {

        assertEquals(new RgbColor(220, 38, 38),  Themes.DARK.red_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(37, 99, 235),  Themes.DARK.blue_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(22, 163, 74),  Themes.DARK.green_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(202, 138, 4),  Themes.DARK.yellow_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(147, 51, 234), Themes.DARK.purple_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(234, 88, 12),  Themes.DARK.orange_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(219, 39, 119), Themes.DARK.pink_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(8, 145, 178),  Themes.DARK.cyan_FOR_SUBAGENTS_ONLY());
    }

    @Test
    void darkTheme_diffColors_haveDarkBgs_andBrighterWords() {

        // dark red bg + softer red word highlight (removed side).
        assertEquals(new RgbColor(34, 92, 43),   Themes.DARK.diffAdded());
        assertEquals(new RgbColor(56, 166, 96),  Themes.DARK.diffAddedWord());
        assertEquals(new RgbColor(122, 41, 54),  Themes.DARK.diffRemoved());
        assertEquals(new RgbColor(179, 89, 107), Themes.DARK.diffRemovedWord());
    }

    @Test
    void darkTheme_text_is_pureWhite_and_inverseText_pureBlack() {
        assertEquals(new RgbColor(255, 255, 255), Themes.DARK.text());
        assertEquals(new RgbColor(0, 0, 0),       Themes.DARK.inverseText());
    }

    @Test
    void darkTheme_chromeYellow_isCanonical() {
        // Chrome brand yellow — must stay 251,188,4.
        assertEquals(new RgbColor(251, 188, 4), Themes.DARK.chromeYellow());
    }

    @Test
    void darkTheme_rainbow_sevenColorsWithShimmerPair() {
        // 7 base + 7 shimmer = 14 rainbow fields.
        assertNotNull(Themes.DARK.rainbow_red());
        assertNotNull(Themes.DARK.rainbow_violet());
        assertNotNull(Themes.DARK.rainbow_red_shimmer());
        assertNotNull(Themes.DARK.rainbow_violet_shimmer());
        // Shimmer variants must be lighter than their base — channel sum check.
        int baseSum = Themes.DARK.rainbow_red().r() + Themes.DARK.rainbow_red().g()
                    + Themes.DARK.rainbow_red().b();
        int shimSum = Themes.DARK.rainbow_red_shimmer().r() + Themes.DARK.rainbow_red_shimmer().g()
                    + Themes.DARK.rainbow_red_shimmer().b();
        assertTrue(shimSum > baseSum, "shimmer variant must be brighter than base");
    }

    // ── Themes.get registry ─────────────────────────────────────────────────

    @Test
    void get_resolvesEachThemeName() {
// Built-in palettes — all 6 now implemented.
        assertSame(Themes.DARK,             Themes.get(Themes.DARK_NAME));
        assertSame(Themes.LIGHT,            Themes.get(Themes.LIGHT_NAME));
        assertSame(Themes.DARK_DALTONIZED,  Themes.get(Themes.DARK_DALTONIZED_NAME));
        assertSame(Themes.LIGHT_DALTONIZED, Themes.get(Themes.LIGHT_DALTONIZED_NAME));
        assertSame(Themes.DARK_ANSI,        Themes.get(Themes.DARK_ANSI_NAME));
        assertSame(Themes.LIGHT_ANSI,       Themes.get(Themes.LIGHT_ANSI_NAME));
    }

    @Test
    void lightDaltonized_swapsGreenForBlueOnLightBackground() {
        // success is blue, NOT green.
        assertEquals(new RgbColor(0, 102, 153),  Themes.LIGHT_DALTONIZED.success());
        // diffAdded is light blue (NOT light green like LIGHT).
        assertEquals(new RgbColor(153, 204, 255), Themes.LIGHT_DALTONIZED.diffAdded());
        // claude matches DARK_DALTONIZED (255,153,51) — daltonism-adjusted orange.
        assertEquals(Themes.DARK_DALTONIZED.claude(), Themes.LIGHT_DALTONIZED.claude());
        // text/inverseText match light mode.
        assertEquals(new RgbColor(0, 0, 0),       Themes.LIGHT_DALTONIZED.text());
        assertEquals(new RgbColor(255, 255, 255), Themes.LIGHT_DALTONIZED.inverseText());
    }

    @Test
    void lightDaltonized_agentColors_arePureSaturated() {
        // Light bg needs more saturation. Compare to DARK_DALTONIZED's brighter
        // variants and to LIGHT's Tailwind 600.
        assertEquals(new RgbColor(204, 0, 0), Themes.LIGHT_DALTONIZED.red_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(0, 204, 0), Themes.LIGHT_DALTONIZED.green_FOR_SUBAGENTS_ONLY());
        assertEquals(new RgbColor(128, 0, 128), Themes.LIGHT_DALTONIZED.purple_FOR_SUBAGENTS_ONLY());

        assertNotEquals(Themes.LIGHT.red_FOR_SUBAGENTS_ONLY(),
                        Themes.LIGHT_DALTONIZED.red_FOR_SUBAGENTS_ONLY());
        // Differ from DARK_DALTONIZED (which uses bright pastels for dark bg).
        assertNotEquals(Themes.DARK_DALTONIZED.red_FOR_SUBAGENTS_ONLY(),
                        Themes.LIGHT_DALTONIZED.red_FOR_SUBAGENTS_ONLY());
    }

    @Test
    void allFourPortedPalettes_shareSameRainbow() {
        // Rainbow palette is theme-independent — must be identical in all 4.
        assertEquals(Themes.DARK.rainbow_red(),  Themes.LIGHT.rainbow_red());
        assertEquals(Themes.DARK.rainbow_red(),  Themes.DARK_DALTONIZED.rainbow_red());
        assertEquals(Themes.DARK.rainbow_red(),  Themes.LIGHT_DALTONIZED.rainbow_red());
        assertEquals(Themes.DARK.rainbow_violet_shimmer(),
                     Themes.LIGHT_DALTONIZED.rainbow_violet_shimmer());
    }

    @Test
    void lightTheme_spotChecks() {
        // text/inverseText are inverted vs DARK (black on white).
        assertEquals(new RgbColor(0, 0, 0),       Themes.LIGHT.text());
        assertEquals(new RgbColor(255, 255, 255), Themes.LIGHT.inverseText());
        // autoAccept: pure electric violet (135,0,255), more saturated for light bg.
        assertEquals(new RgbColor(135, 0, 255), Themes.LIGHT.autoAccept());
        // userMessageBackground is light grey (240,240,240).
        assertEquals(new RgbColor(240, 240, 240), Themes.LIGHT.userMessageBackground());
        // Tailwind 600 agent palette unchanged.
        assertEquals(new RgbColor(220, 38, 38), Themes.LIGHT.red_FOR_SUBAGENTS_ONLY());
        // chromeYellow unchanged.
        assertEquals(new RgbColor(251, 188, 4), Themes.LIGHT.chromeYellow());
    }

    @Test
    void darkDaltonized_swapsGreenForBlue() {
        // success must be blue, NOT green, for deuteranopia safety.
        assertEquals(new RgbColor(51, 153, 255), Themes.DARK_DALTONIZED.success());
        // diffAdded is dark blue (NOT green like DARK).
        assertEquals(new RgbColor(0, 68, 102), Themes.DARK_DALTONIZED.diffAdded());
        assertEquals(new RgbColor(0, 119, 179), Themes.DARK_DALTONIZED.diffAddedWord());
        // claude is daltonism-adjusted orange (255,153,51), distinct from DARK.
        assertEquals(new RgbColor(255, 153, 51), Themes.DARK_DALTONIZED.claude());
        assertNotEquals(Themes.DARK.claude(), Themes.DARK_DALTONIZED.claude());
    }

    @Test
    void darkAndLight_shareSameAgentColors_butDaltonizedDoesnt() {

        assertEquals(Themes.DARK.red_FOR_SUBAGENTS_ONLY(),
                     Themes.LIGHT.red_FOR_SUBAGENTS_ONLY());
        // Daltonized differs: bright variant (255,102,102).
        assertEquals(new RgbColor(255, 102, 102),
                     Themes.DARK_DALTONIZED.red_FOR_SUBAGENTS_ONLY());
        assertNotEquals(Themes.DARK.red_FOR_SUBAGENTS_ONLY(),
                        Themes.DARK_DALTONIZED.red_FOR_SUBAGENTS_ONLY());
    }

    @Test
    void allThreePalettes_shareSameRainbowColors() {
        // Rainbow palette is theme-independent (ultrathink keyword highlighting).
        assertEquals(Themes.DARK.rainbow_red(),    Themes.LIGHT.rainbow_red());
        assertEquals(Themes.DARK.rainbow_red(),    Themes.DARK_DALTONIZED.rainbow_red());
        assertEquals(Themes.DARK.rainbow_violet(), Themes.LIGHT.rainbow_violet());
    }

    @Test
    void get_unknownName_fallsBackToDark() {

        assertSame(Themes.DARK, Themes.get("nonexistent"));
        assertSame(Themes.DARK, Themes.get(null));
        assertSame(Themes.DARK, Themes.get(""));
    }

    @Test
    void get_isCaseInsensitive() {

        assertSame(Themes.DARK, Themes.get("Dark"));
        assertSame(Themes.DARK, Themes.get("DARK"));
    }
}
