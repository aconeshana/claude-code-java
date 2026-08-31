package com.claudecode.keybindings;


import com.claudecode.keybindings.KeystrokeParser.Chord;
import com.claudecode.keybindings.KeystrokeParser.DisplayPlatform;
import com.claudecode.keybindings.KeystrokeParser.Keystroke;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KeystrokeParserTest {

    @Test
    void emptyKeystrokeIsShared() {
        assertSame(Keystroke.empty(), Keystroke.empty());
        assertSame(Keystroke.empty(), KeystrokeParser.parseKeystroke(null));
    }

    // ── parseKeystroke ──────────────────────────────────────────────────────

    @Test
    void parse_singleKey() {
        Keystroke ks = KeystrokeParser.parseKeystroke("a");
        assertEquals("a", ks.key());
        assertFalse(ks.ctrl() || ks.alt() || ks.shift() || ks.meta() || ks.superMod());
    }

    @Test
    void parse_modifierAliases() {
        assertTrue(KeystrokeParser.parseKeystroke("Control+C").ctrl());
        assertTrue(KeystrokeParser.parseKeystroke("Option+V").alt());
        assertTrue(KeystrokeParser.parseKeystroke("Opt+V").alt());
        assertTrue(KeystrokeParser.parseKeystroke("Command+Q").superMod());
        assertTrue(KeystrokeParser.parseKeystroke("Cmd+Q").superMod());
        assertTrue(KeystrokeParser.parseKeystroke("Super+P").superMod());
        assertTrue(KeystrokeParser.parseKeystroke("Win+P").superMod());
        assertTrue(KeystrokeParser.parseKeystroke("Meta+m").meta());
        assertTrue(KeystrokeParser.parseKeystroke("Shift+Tab").shift());
    }

    @Test
    void parse_specialKeyNames() {
        assertEquals("escape", KeystrokeParser.parseKeystroke("esc").key());
        assertEquals("enter",  KeystrokeParser.parseKeystroke("return").key());
        assertEquals(" ",      KeystrokeParser.parseKeystroke("space").key());
        assertEquals("up",     KeystrokeParser.parseKeystroke("↑").key());
        assertEquals("down",   KeystrokeParser.parseKeystroke("↓").key());
        assertEquals("left",   KeystrokeParser.parseKeystroke("←").key());
        assertEquals("right",  KeystrokeParser.parseKeystroke("→").key());
    }

    @Test
    void parse_multiModifierKey() {
        Keystroke ks = KeystrokeParser.parseKeystroke("ctrl+shift+alt+k");
        assertEquals("k", ks.key());
        assertTrue(ks.ctrl());
        assertTrue(ks.shift());
        assertTrue(ks.alt());
        assertFalse(ks.superMod());
    }

    // ── parseChord ──────────────────────────────────────────────────────────

    @Test
    void parseChord_loneSpaceIsSpaceKey() {
        Chord c = KeystrokeParser.parseChord(" ");
        assertEquals(1, c.keystrokes.size());
        assertEquals(" ", c.keystrokes.getFirst().key());
    }

    @Test
    void parseChord_multipleSteps() {
        Chord c = KeystrokeParser.parseChord("ctrl+x ctrl+e");
        assertEquals(2, c.keystrokes.size());
        assertEquals("x", c.keystrokes.getFirst().key());
        assertEquals("e", c.keystrokes.get(1).key());
        assertTrue(c.keystrokes.getFirst().ctrl());
        assertTrue(c.keystrokes.get(1).ctrl());
    }

    @Test
    void parseChord_collapsesWhitespace() {
        Chord c = KeystrokeParser.parseChord("  ctrl+a   ctrl+b  ");
        assertEquals(2, c.keystrokes.size());
    }

    // ── Canonical string output ─────────────────────────────────────────────

    @Test
    void chordToString_renderSuperAsCmd_andArrowsAsGlyphs() {

        Chord c = KeystrokeParser.parseChord("super+up");
        assertEquals("cmd+↑", KeystrokeParser.chordToString(c));
    }

    @Test
    void chordToString_modifierOrder_isCanonical() {

        Chord c = KeystrokeParser.parseChord("shift+alt+ctrl+k");
        assertEquals("ctrl+alt+shift+k", KeystrokeParser.chordToString(c));
    }

    @Test
    void chordToString_specialKeysRenderReadable() {
        assertEquals("Esc",      KeystrokeParser.chordToString(KeystrokeParser.parseChord("esc")));
        assertEquals("Enter",    KeystrokeParser.chordToString(KeystrokeParser.parseChord("return")));
        assertEquals("PageUp",   KeystrokeParser.chordToString(KeystrokeParser.parseChord("pageup")));
        assertEquals("Backspace", KeystrokeParser.chordToString(KeystrokeParser.parseChord("backspace")));
    }

    // ── Platform-aware display ──────────────────────────────────────────────

    @Test
    void displayString_macos_altRendersAsOpt() {
        Keystroke ks = KeystrokeParser.parseKeystroke("alt+x");
        assertEquals("opt+x", KeystrokeParser.keystrokeToDisplayString(ks, DisplayPlatform.MACOS));
        assertEquals("alt+x", KeystrokeParser.keystrokeToDisplayString(ks, DisplayPlatform.LINUX));
        assertEquals("alt+x", KeystrokeParser.keystrokeToDisplayString(ks, DisplayPlatform.WINDOWS));
    }

    @Test
    void displayString_macos_superRendersAsCmd_otherwiseSuper() {
        Keystroke ks = KeystrokeParser.parseKeystroke("super+p");
        assertEquals("cmd+p",   KeystrokeParser.keystrokeToDisplayString(ks, DisplayPlatform.MACOS));
        assertEquals("super+p", KeystrokeParser.keystrokeToDisplayString(ks, DisplayPlatform.LINUX));
    }

    @Test
    void displayString_altAndMetaCollapseToOneToken() {

        Keystroke alt = KeystrokeParser.parseKeystroke("alt+m");
        Keystroke meta = KeystrokeParser.parseKeystroke("meta+m");
        assertEquals("alt+m", KeystrokeParser.keystrokeToDisplayString(alt, DisplayPlatform.LINUX));
        assertEquals("alt+m", KeystrokeParser.keystrokeToDisplayString(meta, DisplayPlatform.LINUX));
    }

    @Test
    void chordToDisplayString_multiStepChord() {
        Chord c = KeystrokeParser.parseChord("ctrl+x ctrl+e");
        assertEquals("ctrl+x ctrl+e",
            KeystrokeParser.chordToDisplayString(c, DisplayPlatform.LINUX));
    }

    @Test
    void currentPlatform_resolvesFromOsName() {
        // Just ensure it returns a non-null platform — actual value depends on
        // CI/host. Branch coverage.yml is exercised by the explicit-platform tests above.
        assertNotNull(KeystrokeParser.currentPlatform());
    }
}
