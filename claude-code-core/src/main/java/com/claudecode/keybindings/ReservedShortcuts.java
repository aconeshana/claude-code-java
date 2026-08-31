package com.claudecode.keybindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shortcuts that are typically intercepted by the OS, terminal, or shell and
 * will likely never reach the application. Ports
 *  1:1.
 * <p>
 * Three classes with severity levels:
 * <ul>
 *   <li>{@link #NON_REBINDABLE} — hardcoded in Claude Code (ctrl+c / ctrl+d / ctrl+m).
 *       {@code error} severity.</li>
 *   <li>{@link #TERMINAL_RESERVED} — intercepted by the terminal/OS (ctrl+z / ctrl+\\).</li>
 *   <li>{@link #MACOS_RESERVED} — added only when running on macOS.</li>
 * </ul>
 */
public final class ReservedShortcuts {

    private ReservedShortcuts() {}

    public enum Severity { ERROR, WARNING }

    public record ReservedShortcut(String key, String reason, Severity severity) {}

    /** Hardcoded in Claude Code — rebinding shows an error. */
    public static final List<ReservedShortcut> NON_REBINDABLE = List.of(
        new ReservedShortcut(
            "ctrl+c",
            "Cannot be rebound - used for interrupt/exit (hardcoded)",
            Severity.ERROR),
        new ReservedShortcut(
            "ctrl+d",
            "Cannot be rebound - used for exit (hardcoded)",
            Severity.ERROR),
        new ReservedShortcut(
            "ctrl+m",
            "Cannot be rebound - identical to Enter in terminals (both send CR)",
            Severity.ERROR)
    );

    /**
     * Terminal-intercepted shortcuts. Note: ctrl+s (XOFF) and ctrl+q (XON) are
     * NOT included — most modern terminals disable flow control by default and
     * Claude Code uses ctrl+s for the stash feature.
     */
    public static final List<ReservedShortcut> TERMINAL_RESERVED = List.of(
        new ReservedShortcut(
            "ctrl+z",
            "Unix process suspend (SIGTSTP)",
            Severity.WARNING),
        new ReservedShortcut(
            "ctrl+\\",
            "Terminal quit signal (SIGQUIT)",
            Severity.ERROR)
    );

    /** macOS-specific shortcuts intercepted by the system. */
    public static final List<ReservedShortcut> MACOS_RESERVED = List.of(
        new ReservedShortcut("cmd+c",     "macOS system copy",       Severity.ERROR),
        new ReservedShortcut("cmd+v",     "macOS system paste",      Severity.ERROR),
        new ReservedShortcut("cmd+x",     "macOS system cut",        Severity.ERROR),
        new ReservedShortcut("cmd+q",     "macOS quit application",  Severity.ERROR),
        new ReservedShortcut("cmd+w",     "macOS close window/tab",  Severity.ERROR),
        new ReservedShortcut("cmd+tab",   "macOS app switcher",      Severity.ERROR),
        new ReservedShortcut("cmd+space", "macOS Spotlight",         Severity.ERROR)
    );

    /**
     * Returns all reserved shortcuts visible on the current platform. Always
     * includes {@link #NON_REBINDABLE} + {@link #TERMINAL_RESERVED}; appends
     * {@link #MACOS_RESERVED} when running on macOS.
     */
    public static List<ReservedShortcut> getReservedShortcuts() {
        List<ReservedShortcut> out = new ArrayList<>(NON_REBINDABLE);
        out.addAll(TERMINAL_RESERVED);
        if (DefaultBindings.IS_DARWIN) {
            out.addAll(MACOS_RESERVED);
        }
        return List.copyOf(out);
    }

    /**
     * Normalize a key string for comparison: lowercase parts, canonical
     * modifier names (control→ctrl, option/opt→alt, command/cmd→cmd), modifiers
     * sorted alphabetically.
     * <p>
     * Chord strings (space-separated multi-step like {@code "ctrl+x ctrl+b"})
     * are normalized per-step — splitting on {@code +} first would mangle the
     * chord into {@code "x ctrl"} where {@code mainKey} gets overwritten.
     */
    public static String normalizeKeyForComparison(String key) {
        if (key == null) return "";
        String[] steps = key.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) out.append(' ');
            out.append(normalizeStep(steps[i]));
        }
        return out.toString();
    }

    private static final Set<String> MODIFIER_TOKENS = Set.of(
        "ctrl", "control", "alt", "opt", "option",
        "meta", "cmd", "command", "shift"
    );

    private static String normalizeStep(String step) {
        String[] parts = step.split("\\+");
        List<String> modifiers = new ArrayList<>();
        String mainKey = "";
        for (String raw : parts) {
            String lower = raw.trim().toLowerCase(Locale.ROOT);
            if (MODIFIER_TOKENS.contains(lower)) {
                modifiers.add(switch (lower) {
                    case "control"            -> "ctrl";
                    case "option", "opt"     -> "alt";
                    case "command", "cmd"    -> "cmd";
                    default                   -> lower;
                });
            } else {
                mainKey = lower;
            }
        }
        modifiers.sort(null);
        StringBuilder out = new StringBuilder();
        for (String m : modifiers) out.append(m).append('+');
        out.append(mainKey);
        return out.toString();
    }

    /**
     * Returns true if {@code key} is non-rebindable in Claude Code. Compares
     * after normalisation so callers can pass user-typed strings like
     * {@code "Control + C"}.
     */
    public static boolean isNonRebindable(String key) {
        String normalised = normalizeKeyForComparison(key);
        return NON_REBINDABLE.stream()
            .anyMatch(rs -> normalizeKeyForComparison(rs.key()).equals(normalised));
    }
}
