package com.claudecode.ui.lanterna.input;

import com.claudecode.keybindings.KeybindingResolver;
import com.claudecode.keybindings.KeystrokeParser;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;


public final class ContextKeybindingDispatcher {

    public sealed interface Result permits Result.Action, Result.Consumed, Result.None {
        record Action(String value) implements Result {}
        record Consumed() implements Result {}
        record None() implements Result {}
    }

    private static final long CHORD_TIMEOUT_MS = 1000;
    private static final Result.Consumed CONSUMED = new Result.Consumed();
    private static final Result.None NONE = new Result.None();
    /** Immutable default binding graph is shared by every dialog/input surface. */
    private static final KeybindingResolver DEFAULT_RESOLVER =
        KeybindingResolver.defaultResolver();
    private final LongSupplier clock;
    private UserKeybindingsStore store;
    private KeystrokeParser.Chord pendingChord;
    private long pendingChordTs;

    public ContextKeybindingDispatcher() {
        this(System::currentTimeMillis);
    }

    /** Package-private clock seam for deterministic chord-timeout tests. */
    ContextKeybindingDispatcher(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setStore(UserKeybindingsStore store) {
        this.store = store;
        this.pendingChord = null;
    }

    /** Lets the prompt bypass resolver allocation for ordinary printable keys when no chord is active. */
    boolean hasPendingChord() {
        return pendingChord != null;
    }

    /** Whether a UI surface must consult the resolver instead of its allocation-free native defaults. */
    public boolean isCustomizationEnabled() {
        return store != null && store.isEnabled();
    }

    public Result resolve(String context, KeyStroke key) {
        return resolve(List.of(context), key);
    }

    /** Resolve against active contexts, adding Global when the caller did not include it. */
    public Result resolve(List<String> contexts, KeyStroke key) {
        long now = clock.getAsLong();
        if (pendingChord != null
                && now - pendingChordTs > CHORD_TIMEOUT_MS) {
            pendingChord = null;
        }
        KeystrokeParser.Keystroke parsed = LanternaKeyAdapter.toKeystroke(key);
        if (parsed == null) return NONE;
        KeybindingResolver resolver = store == null
            ? DEFAULT_RESOLVER : store.currentResolver();
        List<String> activeContexts = new ArrayList<>(contexts);
        if (!activeContexts.contains("Global")) activeContexts.add("Global");
        KeybindingResolver.ChordResolveResult resolved = resolver
            .resolveChord(activeContexts, parsed, pendingChord);
        return switch (resolved) {
            case KeybindingResolver.ChordResolveResult.ChordMatch(String action) -> {
                pendingChord = null;
                yield new Result.Action(action);
            }
            case KeybindingResolver.ChordResolveResult.ChordUnbound() -> {
                pendingChord = null;
                yield CONSUMED;
            }
            case KeybindingResolver.ChordResolveResult.ChordCancelled() -> {
                pendingChord = null;
                yield CONSUMED;
            }
            case KeybindingResolver.ChordResolveResult.ChordStarted(KeystrokeParser.Chord pending) -> {
                pendingChord = pending;
                pendingChordTs = now;
                yield CONSUMED;
            }
            case KeybindingResolver.ChordResolveResult.ChordNone() -> {
                pendingChord = null;
                yield NONE;
            }
        };
    }
}
