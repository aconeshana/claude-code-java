package com.claudecode.keybindings;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.platform.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keystroke + chord parser/formatter.
 */
public final class KeystrokeParser {

    private KeystrokeParser() {}

    public enum DisplayPlatform { MACOS, WINDOWS, LINUX, WSL, UNKNOWN }

    public record Keystroke(
        String key,
        boolean ctrl,
        boolean alt,
        boolean shift,
        boolean meta,
        boolean superMod
    ) {
        private static final Keystroke EMPTY =
            new Keystroke("", false, false, false, false, false);

        public static Keystroke empty() {
            return EMPTY;
        }
    }

    /** A chord is an ordered list of one or more keystrokes. */
    public static final class Chord {
        public final List<Keystroke> keystrokes;
        public Chord(List<Keystroke> keystrokes) { this.keystrokes = List.copyOf(keystrokes); }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /**
     * Parses {@code "ctrl+shift+k"} into a {@link Keystroke}. Modifier aliases
     * (control, opt/option, command/super/win) collapse to canonical forms.
     */
    public static Keystroke parseKeystroke(String input) {
        if (input == null) return Keystroke.empty();
        String[] parts = input.split("\\+");
        boolean ctrl = false, alt = false, shift = false, meta = false, superMod = false;
        String key = "";

        for (String part : parts) {
            String lower = part.toLowerCase(Locale.ROOT);
            switch (lower) {
                case "ctrl", "control"                  -> ctrl     = true;
                case "alt", "opt", "option"            -> alt      = true;
                case "shift"                            -> shift    = true;
                case "meta"                             -> meta     = true;
                case "cmd", "command", "super", "win"  -> superMod = true;
                case "esc"                              -> key = "escape";
                case "return"                           -> key = "enter";
                case "space"                            -> key = " ";
                case "↑"                                -> key = "up";
                case "↓"                                -> key = "down";
                case "←"                                -> key = "left";
                case "→"                                -> key = "right";
                default                                 -> key = lower;
            }
        }
        return new Keystroke(key, ctrl, alt, shift, meta, superMod);
    }

    /**
     * Parses {@code "ctrl+k ctrl+s"} into a multi-keystroke chord.
     */
    public static Chord parseChord(String input) {
        if (Strings.CS.equals(" ", input)) {
            return new Chord(List.of(parseKeystroke("space")));
        }
        String trimmed = input == null ? "" : input.trim();
        String[] steps = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        List<Keystroke> out = new ArrayList<>(steps.length);
        for (String s : steps) out.add(parseKeystroke(s));
        return new Chord(out);
    }

    // ── Canonical string output ─────────────────────────────────────────────

    /**
     * Canonical (non-platform-aware) keystroke string. {@code super} renders
     * as {@code cmd}; arrows render as glyphs (↑↓←→).
     */
    public static String keystrokeToString(Keystroke ks) {
        List<String> parts = new ArrayList<>(6);
        if (ks.ctrl())     parts.add("ctrl");
        if (ks.alt())      parts.add("alt");
        if (ks.shift())    parts.add("shift");
        if (ks.meta())     parts.add("meta");
        if (ks.superMod()) parts.add("cmd");
        parts.add(keyToDisplayName(ks.key()));
        return String.join("+", parts);
    }

    public static String chordToString(Chord chord) {
        List<String> out = new ArrayList<>(chord.keystrokes.size());
        for (Keystroke ks : chord.keystrokes) out.add(keystrokeToString(ks));
        return String.join(" ", out);
    }

    // ── Platform-aware display ──────────────────────────────────────────────

    /**
     * Platform-appropriate keystroke display. On macOS, {@code alt} renders as
     * {@code opt} and {@code super} as {@code cmd}; on other platforms,
     * {@code alt} stays {@code alt} and {@code super} renders as {@code super}.
     * Alt and meta are equivalent in terminals — both render as a single key.
     */
    public static String keystrokeToDisplayString(Keystroke ks, DisplayPlatform platform) {
        List<String> parts = new ArrayList<>(5);
        if (ks.ctrl()) parts.add("ctrl");
        if (ks.alt() || ks.meta()) {
            parts.add(platform == DisplayPlatform.MACOS ? "opt" : "alt");
        }
        if (ks.shift()) parts.add("shift");
        if (ks.superMod()) {
            parts.add(platform == DisplayPlatform.MACOS ? "cmd" : "super");
        }
        parts.add(keyToDisplayName(ks.key()));
        return String.join("+", parts);
    }

    public static String chordToDisplayString(Chord chord, DisplayPlatform platform) {
        List<String> out = new ArrayList<>(chord.keystrokes.size());
        for (Keystroke ks : chord.keystrokes) out.add(keystrokeToDisplayString(ks, platform));
        return String.join(" ", out);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String keyToDisplayName(String key) {
        return switch (key) {
            case "escape"   -> "Esc";
            case " "        -> "Space";
            case "tab"      -> "tab";
            case "enter"    -> "Enter";
            case "backspace"-> "Backspace";
            case "delete"   -> "Delete";
            case "up"       -> "↑";
            case "down"     -> "↓";
            case "left"     -> "←";
            case "right"    -> "→";
            case "pageup"   -> "PageUp";
            case "pagedown" -> "PageDown";
            case "home"     -> "Home";
            case "end"      -> "End";
            default          -> key;
        };
    }

    /**
     * Auto-detects the current display platform from {@code os.name}.
     */
    public static DisplayPlatform currentPlatform() {
        return switch (Platform.CURRENT) {
            case DARWIN -> DisplayPlatform.MACOS;
            case WIN32  -> DisplayPlatform.WINDOWS;
            default     -> DisplayPlatform.LINUX;
        };
    }
}
