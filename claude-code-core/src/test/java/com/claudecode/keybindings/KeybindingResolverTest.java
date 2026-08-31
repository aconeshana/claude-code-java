package com.claudecode.keybindings;

import com.claudecode.keybindings.KeybindingResolver.ChordResolveResult;
import com.claudecode.keybindings.KeybindingResolver.ParsedBinding;
import com.claudecode.keybindings.KeybindingResolver.ResolveResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KeybindingResolver}.
 */
class KeybindingResolverTest {

    @Test
    void immutableDefaultGraphAndResolverAreShared() {
        assertSame(KeybindingResolver.defaultsAsBindings(),
            KeybindingResolver.defaultsAsBindings());
        assertSame(KeybindingResolver.defaultResolver(),
            KeybindingResolver.defaultResolver());
    }

    /** Build a live keystroke the way the UI would (via the shared parser). */
    private static KeystrokeParser.Keystroke ks(String keySpec) {
        return KeystrokeParser.parseKeystroke(keySpec);
    }

    private static ParsedBinding b(String key, String action, String context) {
        return new ParsedBinding(KeystrokeParser.parseChord(key), action, context);
    }

    // Merged list: defaults first, then a user override (Ctrl+C in Chat) so
    // last-wins is exercised.
    private static final List<ParsedBinding> BINDINGS = List.of(
        b("ctrl+c", "app:interrupt", "Global"),
        b("ctrl+l", "app:redraw", "Global"),
        b("escape", "chat:cancel", "Chat"),
        b("enter", "chat:submit", "Chat"),
        b("ctrl+g", null, "Chat"),                       // unbind
        b("ctrl+c", "chat:cancel", "Chat"),              // user override of Global ctrl+c
        b("ctrl+x ctrl+k", "chat:killAgents", "Chat")     // chord
    );

    private static final KeybindingResolver R = new KeybindingResolver(BINDINGS);

    private static final List<String> CHAT = List.of("Chat", "Global");

    // ── single-key resolution ───────────────────────────────────────────────

    @Test
    void single_match() {
        ResolveResult r = R.resolve(CHAT, ks("enter"));
        assertInstanceOf(ResolveResult.Match.class, r);
        assertEquals("chat:submit", ((ResolveResult.Match) r).action());
    }

    @Test
    void single_none() {
        assertInstanceOf(ResolveResult.None.class, R.resolve(CHAT, ks("z")));
        assertSame(R.resolve(CHAT, ks("z")), R.resolve(CHAT, ks("z")));
    }

    @Test
    void single_unbound_nullAction() {
        assertInstanceOf(ResolveResult.Unbound.class, R.resolve(CHAT, ks("ctrl+g")));
        assertSame(R.resolve(CHAT, ks("ctrl+g")), R.resolve(CHAT, ks("ctrl+g")));
    }

    @Test
    void lastWins_userOverridesDefault_forContext() {
        // ctrl+c in Chat must resolve to the user override, not Global's default.
        ResolveResult r = R.resolve(CHAT, ks("ctrl+c"));
        assertInstanceOf(ResolveResult.Match.class, r);
        assertEquals("chat:cancel", ((ResolveResult.Match) r).action());
    }

    @Test
    void globalContextStillResolves() {
        ResolveResult r = R.resolve(CHAT, ks("ctrl+l"));
        assertInstanceOf(ResolveResult.Match.class, r);
        assertEquals("app:redraw", ((ResolveResult.Match) r).action());
    }

    // ── chord resolution ────────────────────────────────────────────────────

    @Test
    void chord_startsOnFirstKey() {
        ChordResolveResult r = R.resolveChord(CHAT, ks("ctrl+x"), null);
        assertInstanceOf(ChordResolveResult.ChordStarted.class, r);
        ChordResolveResult.ChordStarted s = (ChordResolveResult.ChordStarted) r;
        assertEquals(1, s.pending().keystrokes.size());
    }

    @Test
    void chord_completes() {
        // First key starts the chord; second key completes it.
        ChordResolveResult started = R.resolveChord(CHAT, ks("ctrl+x"), null);
        ChordResolveResult completed = R.resolveChord(
            CHAT, ks("ctrl+k"), ((ChordResolveResult.ChordStarted) started).pending());
        assertInstanceOf(ChordResolveResult.ChordMatch.class, completed);
        assertEquals("chat:killAgents", ((ChordResolveResult.ChordMatch) completed).action());
    }

    @Test
    void chord_cancelOnEscape() {
        ChordResolveResult started = R.resolveChord(CHAT, ks("ctrl+x"), null);
        ChordResolveResult cancelled = R.resolveChord(
            CHAT, ks("escape"), ((ChordResolveResult.ChordStarted) started).pending());
        assertInstanceOf(ChordResolveResult.ChordCancelled.class, cancelled);
    }

    @Test
    void chord_noneWhenNoMatch() {
        assertInstanceOf(ChordResolveResult.ChordNone.class, R.resolveChord(CHAT, ks("z"), null));
        assertSame(R.resolveChord(CHAT, ks("z"), null),
            R.resolveChord(CHAT, ks("z"), null));
    }

    @Test
    void chord_cancelledWhenPendingAndNoMatch() {
        ChordResolveResult started = R.resolveChord(CHAT, ks("ctrl+x"), null);
        // second key that matches nothing as a continuation
        ChordResolveResult r = R.resolveChord(
            CHAT, ks("q"), ((ChordResolveResult.ChordStarted) started).pending());
        assertInstanceOf(ChordResolveResult.ChordCancelled.class, r);
    }

    // ── display ─────────────────────────────────────────────────────────────

    @Test
    void getBindingDisplayText_returnsChordString() {

        assertEquals("Enter", R.getBindingDisplayText("chat:submit", "Chat"));
        assertEquals("ctrl+x ctrl+k", R.getBindingDisplayText("chat:killAgents", "Chat"));
    }

    // ── key equality (alt ≡ meta, super distinct) ───────────────────────────

    @Test
    void keystrokesEqual_altEquivalentToMeta() {
        assertTrue(KeybindingResolver.keystrokesEqual(
            ks("alt+k"), ks("meta+k")));
    }

    @Test
    void keystrokesEqual_distinguishesSuper() {
        assertFalse(KeybindingResolver.keystrokesEqual(
            ks("cmd+k"), ks("k")));
    }

    @Test
    void keystrokesEqual_distinguishesShift() {
        assertFalse(KeybindingResolver.keystrokesEqual(
            ks("shift+k"), ks("k")));
    }

    // ── construction helpers ────────────────────────────────────────────────

    @Test
    void defaultsAsBindings_coversKnownActions() {
        KeybindingResolver def = new KeybindingResolver(KeybindingResolver.defaultsAsBindings());
        ResolveResult r = def.resolve(List.of("Global"), ks("ctrl+c"));
        assertInstanceOf(ResolveResult.Match.class, r);
    }
}
