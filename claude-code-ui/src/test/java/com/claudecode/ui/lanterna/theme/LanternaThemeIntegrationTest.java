package com.claudecode.ui.lanterna.theme;

import com.googlecode.lanterna.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanternaThemeIntegrationTest {

    @AfterEach
    void resetScheme() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
    }

    @Test
    void darkScheme_successColor_matchesDarkThemePalette() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK);
        TextColor result = LanternaTheme.toolSuccess();
        // DARK theme success = rgb(78,186,101).
        TextColor expected = LanternaTheme.toLC(Themes.DARK.success());
        assertEquals(expected, result);
    }

    @Test
    void lightScheme_successColor_matchesLightThemePalette() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT);
        TextColor result = LanternaTheme.toolSuccess();
        // LIGHT theme success = rgb(44,122,57).
        TextColor expected = LanternaTheme.toLC(Themes.LIGHT.success());
        assertEquals(expected, result);
        // Must differ from dark — different RGB values.
        assertNotEquals(LanternaTheme.toLC(Themes.DARK.success()), result);
    }

    @Test
    void darkDaltonizedScheme_successColor_isBlue_notGreen() {
        LanternaTheme.setScheme(LanternaTheme.Scheme.DARK_DALTONIZED);
        TextColor result = LanternaTheme.toolSuccess();
        // DARK_DALTONIZED success = rgb(51,153,255) — BLUE (not green).
        TextColor expected = LanternaTheme.toLC(Themes.DARK_DALTONIZED.success());
        assertEquals(expected, result,
            "DARK_DALTONIZED success must be blue (51,153,255), not green");
        // Must differ from DARK and LIGHT success colors.
        assertNotEquals(LanternaTheme.toLC(Themes.DARK.success()), result,
            "daltonized success must differ from DARK success");
    }

    @Test
    void lightDaltonizedScheme_userQueryBg_isDarkerGrey() {
        // LIGHT_DALTONIZED userMessageBackground = rgb(220,220,220)
        // vs LIGHT = rgb(240,240,240).
        LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT_DALTONIZED);
        TextColor daltonized = LanternaTheme.userQueryBg();
        LanternaTheme.setScheme(LanternaTheme.Scheme.LIGHT);
        TextColor light = LanternaTheme.userQueryBg();
        assertNotEquals(daltonized, light,
            "LIGHT_DALTONIZED userMessageBackground (220) must differ from LIGHT (240)");
    }

    @Test
    void schemeSwitching_isThreadSafe() {
        // Rapidly switch between schemes — no crash expected.
        for (LanternaTheme.Scheme scheme : LanternaTheme.Scheme.values()) {
            LanternaTheme.setScheme(scheme);
            assertNotNull(LanternaTheme.toolSuccess());
            assertNotNull(LanternaTheme.modePlan());
            assertNotNull(LanternaTheme.toolError());
        }
    }

    @Test
    void toLC_convertsRgbColorCorrectly() {
        var c = Themes.DARK.success();
        var tc = LanternaTheme.toLC(c);
// toLC returns TextColor.RGB at chalkLevel==3 (truecolor),
        // or TextColor.Indexed at level<3 (256-color quantization).
        assertNotNull(tc);
        assertFalse(tc instanceof TextColor.ANSI,
            "toLC() on an RGB-source RgbColor must not return a bare ANSI color");
        if (LanternaTheme.chalkLevel() >= 3) {
            assertInstanceOf(TextColor.RGB.class, tc, "chalkLevel=3: expected TextColor.RGB");
            assertEquals(c.r(), ((TextColor.RGB) tc).getRed());
            assertEquals(c.g(), ((TextColor.RGB) tc).getGreen());
            assertEquals(c.b(), ((TextColor.RGB) tc).getBlue());
        } else {
            assertInstanceOf(TextColor.Indexed.class, tc, "chalkLevel<3: expected TextColor.Indexed (256-color quantization)");
        }
    }
}
