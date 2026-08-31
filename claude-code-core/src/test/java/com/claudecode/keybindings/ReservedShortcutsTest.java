package com.claudecode.keybindings;


import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReservedShortcutsTest {

    // ── List contents ───────────────────────────────────────────────────────

    @Test
    void nonRebindable_hasExactlyThreeHardcodedKeys() {
        assertEquals(3, ReservedShortcuts.NON_REBINDABLE.size());
        // All three must be ERROR severity (rebinding = explicit error).
        ReservedShortcuts.NON_REBINDABLE.forEach(rs ->
            assertEquals(ReservedShortcuts.Severity.ERROR, rs.severity(),
                "NON_REBINDABLE entries must be ERROR severity: " + rs.key()));
    }

    @Test
    void terminalReserved_listsCtrlZAndCtrlBackslash_butNotCtrlS() {
        boolean hasZ        = false;
        boolean hasBackslash = false;
        boolean hasS         = false;
        for (var rs : ReservedShortcuts.TERMINAL_RESERVED) {
            if (Strings.CS.equals(rs.key(), "ctrl+z"))  hasZ        = true;
            if (Strings.CS.equals(rs.key(), "ctrl+\\")) hasBackslash = true;
            if (Strings.CS.equals(rs.key(), "ctrl+s"))  hasS         = true;
        }
        assertTrue(hasZ, "must include ctrl+z (SIGTSTP)");
        assertTrue(hasBackslash, "must include ctrl+\\ (SIGQUIT)");
        // ctrl+s is intentionally NOT reserved — Claude Code uses it for stash.
        assertFalse(hasS, "ctrl+s must NOT be reserved — used by stash feature");
    }

    @Test
    void macosReserved_includesSystemShortcuts() {
        assertEquals(7, ReservedShortcuts.MACOS_RESERVED.size());
        for (String key : new String[]{
            "cmd+c", "cmd+v", "cmd+x", "cmd+q", "cmd+w", "cmd+tab", "cmd+space"
        }) {
            boolean found = ReservedShortcuts.MACOS_RESERVED.stream()
                .anyMatch(rs -> rs.key().equals(key));
            assertTrue(found, "missing macOS reserved: " + key);
        }
    }

    @Test
    void getReservedShortcuts_appendsMacosOnDarwin() {
        var all = ReservedShortcuts.getReservedShortcuts();
        // 3 (NON_REBINDABLE) + 2 (TERMINAL_RESERVED) + maybe 7 (macOS).
        int expected = 3 + 2 + (DefaultBindings.IS_DARWIN ? 7 : 0);
        assertEquals(expected, all.size());
    }

    // ── normalizeKeyForComparison ───────────────────────────────────────────

    @Test
    void normalize_lowercasesAndCanonicalisesModifierAliases() {
        // Aliases: Control → ctrl, Option/Opt → alt, Command/Cmd → cmd.
        assertEquals("ctrl+c",   ReservedShortcuts.normalizeKeyForComparison("Control+C"));
        assertEquals("alt+v",    ReservedShortcuts.normalizeKeyForComparison("Option+V"));
        assertEquals("alt+v",    ReservedShortcuts.normalizeKeyForComparison("Opt+v"));
        assertEquals("cmd+q",    ReservedShortcuts.normalizeKeyForComparison("Command+Q"));
        assertEquals("cmd+q",    ReservedShortcuts.normalizeKeyForComparison("CMD+q"));
    }

    @Test
    void normalize_sortsModifiersAlphabetically() {

        assertEquals("ctrl+shift+a",
            ReservedShortcuts.normalizeKeyForComparison("shift+ctrl+a"));
        assertEquals("ctrl+shift+a",
            ReservedShortcuts.normalizeKeyForComparison("ctrl+shift+a"));
        assertEquals("alt+ctrl+meta+shift+f",
            ReservedShortcuts.normalizeKeyForComparison("meta+shift+alt+ctrl+f"));
    }

    @Test
    void normalize_handlesChordsPerStep() {

        // Splitting on '+' first would mangle to "x ctrl".
        assertEquals("ctrl+x ctrl+b",
            ReservedShortcuts.normalizeKeyForComparison("ctrl+x ctrl+b"));
        // Whitespace collapse + ordering inside each step.
        assertEquals("ctrl+x ctrl+e",
            ReservedShortcuts.normalizeKeyForComparison("  ctrl+x   ctrl+e  "));
    }

    @Test
    void isNonRebindable_handlesAllAliases() {
        // Direct match.
        assertTrue(ReservedShortcuts.isNonRebindable("ctrl+c"));
        assertTrue(ReservedShortcuts.isNonRebindable("ctrl+d"));
        assertTrue(ReservedShortcuts.isNonRebindable("ctrl+m"));
        // Alias normalisation must catch alternative spellings.
        assertTrue(ReservedShortcuts.isNonRebindable("Control+C"));
        assertTrue(ReservedShortcuts.isNonRebindable("CONTROL+D"));
        // ctrl+z is reserved but rebindable.
        assertFalse(ReservedShortcuts.isNonRebindable("ctrl+z"));
        assertFalse(ReservedShortcuts.isNonRebindable("ctrl+a"));
    }

    @Test
    void normalize_emptyAndNullInputs_areSafe() {
        assertEquals("", ReservedShortcuts.normalizeKeyForComparison(""));
        assertEquals("", ReservedShortcuts.normalizeKeyForComparison(null));
    }
}
