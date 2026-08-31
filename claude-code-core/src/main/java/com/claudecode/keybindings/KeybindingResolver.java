package com.claudecode.keybindings;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a keystroke to an action against the merged default + user keybindings.
 */
public record KeybindingResolver(List<ParsedBinding> bindings) {

    /** A single parsed keybinding: a chord, an action (may be null = unbind), and a context. */
    public record ParsedBinding(KeystrokeParser.Chord chord, String action, String context) {}

/**
     * Result of a single-keystroke resolution.
     */
    public sealed interface ResolveResult permits ResolveResult.Match,
        ResolveResult.None, ResolveResult.Unbound {
        record Match(String action) implements ResolveResult {}
        record None() implements ResolveResult {}
        record Unbound() implements ResolveResult {}
    }

/**
     * Result of a chord-aware resolution.
     */
    public sealed interface ChordResolveResult permits ChordResolveResult.ChordMatch,
        ChordResolveResult.ChordNone, ChordResolveResult.ChordUnbound,
        ChordResolveResult.ChordStarted, ChordResolveResult.ChordCancelled {
        record ChordMatch(String action) implements ChordResolveResult {}
        record ChordNone() implements ChordResolveResult {}
        record ChordUnbound() implements ChordResolveResult {}
        record ChordStarted(KeystrokeParser.Chord pending) implements ChordResolveResult {}
        record ChordCancelled() implements ChordResolveResult {}
    }

    private static final ResolveResult.None RESOLVE_NONE = new ResolveResult.None();
    private static final ResolveResult.Unbound RESOLVE_UNBOUND = new ResolveResult.Unbound();
    private static final ChordResolveResult.ChordNone CHORD_NONE =
        new ChordResolveResult.ChordNone();
    private static final ChordResolveResult.ChordUnbound CHORD_UNBOUND =
        new ChordResolveResult.ChordUnbound();
    private static final ChordResolveResult.ChordCancelled CHORD_CANCELLED =
        new ChordResolveResult.ChordCancelled();

    private static final class DefaultHolder {
        private static final List<ParsedBinding> BINDINGS =
            List.copyOf(fromDefaultBlocks());
        private static final KeybindingResolver RESOLVER =
            new KeybindingResolver(BINDINGS);
    }

    public KeybindingResolver(List<ParsedBinding> bindings) {
        // Defensive copy so the merged binding list is immutable after construction.
        this.bindings = List.copyOf(bindings);
    }



    /**
     * Resolve a single keystroke. Only single-keystroke bindings are
     * considered; the last matching binding wins (user overrides default).
     */
    public ResolveResult resolve(List<String> activeContexts, KeystrokeParser.Keystroke key) {
        Set<String> ctxSet = new HashSet<>(activeContexts);
        ParsedBinding match = null;
        for (ParsedBinding b : bindings) {
            if (b.chord().keystrokes.size() != 1) continue;
            if (!ctxSet.contains(b.context())) continue;
            if (keystrokesEqual(b.chord().keystrokes.getFirst(), key)) {
                match = b;
            }
        }
        if (match == null) return RESOLVE_NONE;
        if (match.action() == null) return RESOLVE_UNBOUND;
        return new ResolveResult.Match(match.action());
    }



    /**
     * Resolve a keystroke while tracking chord (multi-keystroke) state.
     */
    public ChordResolveResult resolveChord(
        List<String> activeContexts,
        KeystrokeParser.Keystroke key,
        KeystrokeParser.Chord pending
    ) {

// Key's `.escape`; our caller builds a Keystroke whose key == "escape"

        if (Strings.CS.equals(key.key(), "escape") && pending != null) {
            return CHORD_CANCELLED;
        }

// Unresolvable keystroke (no name, no modifier) — cancel a pending chord, else no match.
        if (key.key().isEmpty()
                && !key.ctrl() && !key.alt() && !key.shift() && !key.meta() && !key.superMod()) {
            return pending != null
                ? CHORD_CANCELLED
                : CHORD_NONE;
        }

        List<KeystrokeParser.Keystroke> testKeys = new ArrayList<>();
        if (pending != null) testKeys.addAll(pending.keystrokes);
        testKeys.add(key);
        KeystrokeParser.Chord testChord = new KeystrokeParser.Chord(testKeys);

        Set<String> ctxSet = new HashSet<>(activeContexts);
        List<ParsedBinding> contextBindings = new ArrayList<>();
        for (ParsedBinding b : bindings) {
            if (ctxSet.contains(b.context())) contextBindings.add(b);
        }

        // Could this keystroke be the prefix of a longer chord? Group by chord
        // string so a later null-override shadows the default it unbinds
        // (otherwise a null-unbind of "ctrl+x ctrl+k" would still leave
        // "ctrl+x" waiting and the single-key binding never firing).
        Map<String, String> chordWinners = new LinkedHashMap<>();
        for (ParsedBinding b : contextBindings) {
            if (b.chord().keystrokes.size() > testChord.keystrokes.size()
                    && chordPrefixMatches(testChord, b)) {
                chordWinners.put(KeystrokeParser.chordToString(b.chord()), b.action());
            }
        }
        boolean hasLongerChords = false;
        for (String action : chordWinners.values()) {
            if (action != null) {
                hasLongerChords = true;
                break;
            }
        }

        if (hasLongerChords) {
            return new ChordResolveResult.ChordStarted(testChord);
        }

        // Exact match (last wins).
        ParsedBinding exactMatch = null;
        for (ParsedBinding b : contextBindings) {
            if (chordExactlyMatches(testChord, b)) {
                exactMatch = b;
            }
        }
        if (exactMatch != null) {
            if (exactMatch.action() == null) return CHORD_UNBOUND;
            return new ChordResolveResult.ChordMatch(exactMatch.action());
        }

        if (pending != null) return CHORD_CANCELLED;
        return CHORD_NONE;
    }



    /** Reverse lookup: display string for the last binding of {@code action} in {@code context}. */
    public String getBindingDisplayText(String action, String context) {
        for (int i = bindings.size() - 1; i >= 0; i--) {
            ParsedBinding b = bindings.get(i);
            if (action.equals(b.action()) && context.equals(b.context())) {
                return KeystrokeParser.chordToString(b.chord());
            }
        }
        return null;
    }



    /**
     * Compare two keystrokes.
     */
    static boolean keystrokesEqual(KeystrokeParser.Keystroke a, KeystrokeParser.Keystroke b) {
        return a.key().equals(b.key())
            && a.ctrl() == b.ctrl()
            && a.shift() == b.shift()
            && (a.alt() || a.meta()) == (b.alt() || b.meta())
            && a.superMod() == b.superMod();
    }

    private static boolean chordPrefixMatches(KeystrokeParser.Chord prefix, ParsedBinding binding) {
        List<KeystrokeParser.Keystroke> chord = binding.chord().keystrokes;
        if (prefix.keystrokes.size() >= chord.size()) return false;
        for (int i = 0; i < prefix.keystrokes.size(); i++) {
            if (!keystrokesEqual(prefix.keystrokes.get(i), chord.get(i))) return false;
        }
        return true;
    }

    private static boolean chordExactlyMatches(KeystrokeParser.Chord chord, ParsedBinding binding) {
        List<KeystrokeParser.Keystroke> bc = binding.chord().keystrokes;
        if (chord.keystrokes.size() != bc.size()) return false;
        for (int i = 0; i < chord.keystrokes.size(); i++) {
            if (!keystrokesEqual(chord.keystrokes.get(i), bc.get(i))) return false;
        }
        return true;
    }



    /** Convert default {@link DefaultBindings} blocks into resolver bindings (no user overrides). */
    public static List<ParsedBinding> defaultsAsBindings() {
        return DefaultHolder.BINDINGS;
    }

    /** Shared resolver for the immutable default binding graph. */
    public static KeybindingResolver defaultResolver() {
        return DefaultHolder.RESOLVER;
    }

/**
     * Convert {@link DefaultBindings#BLOCKS} into resolver bindings.
     */
    private static List<ParsedBinding> fromDefaultBlocks() {
        List<ParsedBinding> out = new ArrayList<>();
        for (DefaultBindings.Block block : DefaultBindings.BLOCKS) {
            for (Map.Entry<String, String> e : block.bindings().entrySet()) {
                out.add(new ParsedBinding(
                    KeystrokeParser.parseChord(e.getKey()), e.getValue(), block.context()));
            }
        }
        return out;
    }

    /**
     * Parse user (already a Jackson tree whose top level is an array of {@code {context, bindings}})
     * into resolver bindings.
     */
    static List<ParsedBinding> fromUserJson(JsonNode root) {
        List<ParsedBinding> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        var it = root.elements();
        while (it.hasNext()) {
            JsonNode block = it.next();
            if (!block.isObject()) continue;
            JsonNode ctx = block.get("context");
            JsonNode binds = block.get("bindings");
            if (ctx == null || !ctx.isTextual() || binds == null || !binds.isObject()) continue;
            binds.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                String action = (v == null || v.isNull()) ? null : v.asText();
                out.add(new ParsedBinding(
                    KeystrokeParser.parseChord(e.getKey()), action, ctx.asText()));
            });
        }
        return out;
    }

/**
     * Positionally merge defaults then user bindings (last wins).
     */
    public static List<ParsedBinding> merge(List<ParsedBinding> defaults, List<ParsedBinding> user) {
        List<ParsedBinding> merged = new ArrayList<>(defaults.size() + user.size());
        merged.addAll(defaults);
        merged.addAll(user);
        return merged;
    }
}
