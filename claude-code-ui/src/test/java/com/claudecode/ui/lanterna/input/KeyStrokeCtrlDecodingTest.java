package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.input.CtrlAndCharacterPattern;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.KeyStroke;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test pinning down Lanterna's Ctrl+letter decoding semantics that
 * {@link InputPanel} relies on for its readline shortcut dispatch.
 * <p>
 * <b>The bug this guards against:</b> InputPanel originally matched Ctrl+O
 * with {@code case ''} (the raw ASCII control byte). In practice
 * Lanterna's {@link CtrlAndCharacterPattern} rewrites a raw 0x0F into
 * {@code KeyStroke(character='o', ctrlDown=true)} before the application
 * ever sees it — the raw-byte case never matched, Ctrl+O fell through to
 * default, and the letter "o" was inserted into the text box instead of
 * toggling transcript mode. Same hazard applies to every Ctrl+A..Z binding.
 */
class KeyStrokeCtrlDecodingTest {

    private static KeyStroke decode(char rawByte) {
        CharacterPattern p = new CtrlAndCharacterPattern();
        var m = p.match(List.of(rawByte));
        assertNotNull(m, "CtrlAndCharacterPattern must match raw control byte 0x" + Integer.toHexString(rawByte));
        return m.fullMatch;
    }

    @Test
    void ctrlO_rawByte0x0F_decodesAsLetterOWithCtrlDown() {
        KeyStroke ks = decode((char) 0x0F);
        assertEquals(Character.valueOf('o'), ks.getCharacter(),
            "Lanterna decodes 0x0F → character 'o', NOT '\\u000F'");
        assertTrue(ks.isCtrlDown(),
            "ctrlDown must be set so callers can dispatch via isCtrlDown() + letter");
        assertFalse(ks.isAltDown());
    }

    @Test
    void ctrlA_rawByte0x01_decodesAsLetterA() {
        KeyStroke ks = decode((char) 0x01);
        assertEquals(Character.valueOf('a'), ks.getCharacter());
        assertTrue(ks.isCtrlDown());
    }

    @Test
    void ctrlUnderscore_rawByte0x1F_decodesAsUnderscore() {
        // Used for Ctrl+_ (undo). Verifies the non-alpha branch.
        KeyStroke ks = decode((char) 0x1F);
        assertEquals(Character.valueOf('_'), ks.getCharacter());
        assertTrue(ks.isCtrlDown());
    }

    @Test
    void allAlphaCtrlBytes_decodeToCorrespondingLetter() {
        // Sanity: 0x01→a, 0x02→b, ..., 0x1A→z, skipping Lanterna's special-cases
        // (LF/CR/Tab/Esc/Backspace return null per CtrlAndCharacterPattern).
        for (int b = 0x01; b <= 0x1A; b++) {
            if (b == '\n' || b == '\r' || b == '\t' || b == 0x08) continue;
            KeyStroke ks = decode((char) b);
            char expected = (char) ('a' - 1 + b);
            assertEquals(Character.valueOf(expected), ks.getCharacter(),
                "raw 0x" + Integer.toHexString(b) + " should decode to '" + expected + "'");
            assertTrue(ks.isCtrlDown());
        }
    }
}
