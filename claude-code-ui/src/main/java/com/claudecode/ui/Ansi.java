package com.claudecode.ui;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.AnsiColor;
import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.util.SemverUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.theme.RgbColor;
import com.claudecode.core.platform.Platform;
import com.googlecode.lanterna.TextColor;
import java.util.Map;
import java.util.Set;

/**
 * ANSI styling utility.
 */
public final class Ansi {

    private static final String ESC = "\u001B";
    private static final String RESET = "\u001B[0m";
    private static final boolean COLOR_SUPPORTED = detectColorSupport();

    private Ansi() {}

    /**
     * Apply one or more styles to text.
     */
    public static String styled(String text, AnsiStyle... styles) {
        if (!COLOR_SUPPORTED || styles.length == 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (AnsiStyle style : styles) {
            sb.append(style.on());
        }
        sb.append(text);
        sb.append(RESET);
        return sb.toString();
    }

    /**
     * Apply a foreground color to text.
     */
    public static String colored(String text, AnsiColor color) {
        if (!COLOR_SUPPORTED) {
            return text;
        }
        return color.code() + text + RESET;
    }

    /**
     * Apply a 24-bit RGB foreground color via SGR {@code 38;2;r;g;b}.
     */
    public static String coloredRgb(String text, int r, int g, int b) {
        if (!COLOR_SUPPORTED) {
            return text;
        }
        return rgbForegroundOpener(r, g, b) + text + RESET;
    }

    /**
     * Build an RGB foreground opener without consulting terminal capability.
     * Keeping this pure makes escape construction independently testable while
     * the public rendering methods still honor {@link #isColorSupported}.
     */
    static String rgbForegroundOpener(int r, int g, int b) {
        int level = LanternaTheme.chalkLevel();
        if (level < 3) {
            int idx = rgbToAnsi256(r, g, b);
            return ESC + "[38;5;" + idx + "m";
        }
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }

    /**
     * xterm-256 cube quantization. matches chalk color-convert/rgb.ansi256;
     * see {@link com.claudecode.ui.lanterna.theme.LanternaTheme#rgbToAnsi256} for
     * the full discussion. Kept private here so Ansi has no compile-time
     * dependency on Lanterna types (RgbColor → r/g/b ints only).
     */
    private static int rgbToAnsi256(int r, int g, int b) {
        if (r == g && g == b) {
            if (r < 8)   return 16;
            if (r > 248) return 231;
            return (int) Math.round((r - 8.0) / 247.0 * 24) + 232;
        }
        return 16
            + 36 * (int) Math.round(r / 255.0 * 5)
            +  6 * (int) Math.round(g / 255.0 * 5)
            +      (int) Math.round(b / 255.0 * 5);
    }

    /**
     * Apply a foreground color and styles to text.
     */
    public static String styled(String text, AnsiColor color, AnsiStyle... styles) {
        if (!COLOR_SUPPORTED) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(color.code());
        for (AnsiStyle style : styles) {
            sb.append(style.on());
        }
        sb.append(text);
        sb.append(RESET);
        return sb.toString();
    }

    /**
     * Convenience overload accepting a {@link com.claudecode.ui.lanterna.theme.RgbColor} pulled
     * straight from {@code LanternaTheme.activeTheme.<key>} — the single way the rest of the app should
     * produce theme-keyed colors so we don't scatter bare {@code AnsiColor} usage.
     */
    public static String colored(String text, RgbColor color) {
        if (!COLOR_SUPPORTED) return text;
        return coloredAnsi(text, color);
    }

    /** Emit a themed foreground sequence after capability was decided by the caller. */
    static String coloredAnsi(String text, RgbColor color) {
        return foregroundOpener(color) + text + RESET;
    }

    /**
     * Combine a theme RGB foreground with one or more styles.
     */
    public static String styled(String text, RgbColor color, AnsiStyle... styles) {
        if (!COLOR_SUPPORTED) return text;
        return styledAnsi(text, color, styles);
    }

    /** Emit themed color and styles after capability was decided by the caller. */
    static String styledAnsi(String text, RgbColor color, AnsiStyle... styles) {
        StringBuilder sb = new StringBuilder();
        sb.append(foregroundOpener(color));
        for (AnsiStyle style : styles) sb.append(style.on());
        sb.append(text);
        sb.append(RESET);
        return sb.toString();
    }

    /**
     * If the color was resolved from an "ansi:<name>" token, return the raw
     * SGR opener that lets the terminal pick its own palette entry; otherwise
     * null (caller falls back to 24-bit RGB).
     */
    static String foregroundOpener(RgbColor color) {
        TextColor.ANSI ansi = color.ansi();
        if (ansi == null) return rgbForegroundOpener(color.r(), color.g(), color.b());
        return switch (ansi) {
            case BLACK          -> ESC + "[30m";
            case RED            -> ESC + "[31m";
            case GREEN          -> ESC + "[32m";
            case YELLOW         -> ESC + "[33m";
            case BLUE           -> ESC + "[34m";
            case MAGENTA        -> ESC + "[35m";
            case CYAN           -> ESC + "[36m";
            case WHITE          -> ESC + "[37m";
            case BLACK_BRIGHT   -> ESC + "[90m";
            case RED_BRIGHT     -> ESC + "[91m";
            case GREEN_BRIGHT   -> ESC + "[92m";
            case YELLOW_BRIGHT  -> ESC + "[93m";
            case BLUE_BRIGHT    -> ESC + "[94m";
            case MAGENTA_BRIGHT -> ESC + "[95m";
            case CYAN_BRIGHT    -> ESC + "[96m";
            case WHITE_BRIGHT   -> ESC + "[97m";
            default             -> rgbForegroundOpener(color.r(), color.g(), color.b());
        };
    }

    /**
     * Returns true if the current terminal supports ANSI colors.
     */
    public static boolean isColorSupported() {
        return COLOR_SUPPORTED;
    }

/**
     * Additional terminals that support OSC 8 hyperlinks.
     */
    private static final Set<String> HYPERLINK_TERMINALS = Set.of(
        "ghostty", "Hyper", "kitty", "alacritty", "iTerm.app", "iTerm2"
    );

    /**
     * Returns true if the current terminal supports OSC 8 hyperlinks.
     */
    public static boolean supportsHyperlinks() {
        return supportsHyperlinks(System.getenv());
    }

    static boolean supportsHyperlinks(Map<String, String> env) {
        String forced = env.get("FORCE_HYPERLINK");
        if (StringUtils.isNotEmpty(forced)) {
            return !Strings.CS.equals("0", forced);
        }
        if (env.containsKey("WT_SESSION") || env.containsKey("NETLIFY")) return true;

        String termProgram = env.get("TERM_PROGRAM");
        if (termProgram != null && HYPERLINK_TERMINALS.contains(termProgram)) {
            return true;
        }
        String lcTerminal = env.get("LC_TERMINAL");
        if (lcTerminal != null && HYPERLINK_TERMINALS.contains(lcTerminal)) {
            return true;
        }
        if (env.containsKey("CI") || env.containsKey("TEAMCITY_VERSION")) return false;

        String version = env.get("TERM_PROGRAM_VERSION");
        if (Strings.CS.equals("WezTerm", termProgram)) {
            if (version != null && version.matches("0-unstable-\\d{4}-\\d{2}-\\d{2}")) {
                return version.substring("0-unstable-".length()).compareTo("2020-06-20") >= 0;
            }
            return versionMajor(version) >= 20200620;
        }
        if (Strings.CS.equals("vscode", termProgram)) {
            if (env.containsKey("CURSOR_TRACE_ID")) return true;
            int[] parsed = parseTerminalVersion(version);
            return parsed[0] > 1 || parsed[0] == 1 && parsed[1] >= 72;
        }
        if (Strings.CS.equalsAny(termProgram, "zed", "ghostty")) return true;

        String vte = env.get("VTE_VERSION");
        if (vte != null) {
            if (Strings.CS.equals("0.50.0", vte)) return false;
            int[] parsed = parseTerminalVersion(vte);
            return parsed[0] > 0 || parsed[1] >= 50;
        }
        String term = env.get("TERM");
        return Strings.CS.equalsAny(term, "alacritty", "xterm-kitty")
            || term != null && Strings.CS.contains(term, "kitty");
    }

    private static int versionMajor(String value) {
        return parseTerminalVersion(value)[0];
    }

    private static int[] parseTerminalVersion(String value) {
        if (StringUtils.isBlank(value)) return new int[] {0, 0, 0};
        if (value.matches("\\d{3,4}")) {
            int split = value.length() - 2;
            return new int[] {0, parseInt(value.substring(0, split)),
                parseInt(value.substring(split))};
        }
        String[] parts = value.split("\\.");
        return new int[] {
            parts.length > 0 ? parseInt(parts[0]) : 0,
            parts.length > 1 ? parseInt(parts[1]) : 0,
            parts.length > 2 ? parseInt(parts[2]) : 0
        };
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException _) { return 0; }
    }

    /**
     * Returns true if the current terminal supports OSC 9;4 progress reporting.
     */
    public static boolean supportsProgressReporting() {
        // Exclude Windows Terminal — interprets OSC 9;4 as notifications
        if (System.getenv("WT_SESSION") != null) return false;

        // ConEmu supports OSC 9;4 (all versions)
        if (System.getenv("ConEmuANSI") != null
            || System.getenv("ConEmuPID") != null
            || System.getenv("ConEmuTask") != null) {
            return true;
        }

        String termProgram = System.getenv("TERM_PROGRAM");
        String version = System.getenv("TERM_PROGRAM_VERSION");
        if (termProgram == null || version == null) return false;

        // Ghostty 1.2.0+
        if (Strings.CS.equals("ghostty", termProgram)) {
            return SemverUtils.gte(version, "1.2.0");
        }
        // iTerm2 3.6.6+
        if (Strings.CS.equals("iTerm.app", termProgram)) {
            return SemverUtils.gte(version, "3.6.6");
        }
        return false;
    }

    /**
     * Returns true if the terminal has the cursor-up viewport yank bug.
     */
    public static boolean hasCursorUpViewportYankBug() {
        return Platform.IS_WINDOWS || System.getenv("WT_SESSION") != null;
    }

    /**
     * Returns true if the terminal is Apple Terminal (Apple_Terminal).
     */
    public static boolean isAppleTerminal() {
        return Strings.CS.equals("Apple_Terminal", TerminalDetector.getTerminal());
    }

    /**
     * Returns true if the terminal is Kitty.
     */
    public static boolean isKitty() {
        return Strings.CS.equals("kitty", TerminalDetector.getTerminal());
    }

    static boolean detectColorSupport() {
        String term = System.getenv("TERM");
        if (Strings.CS.equals("dumb", term)) {
            return false;
        }
        String noColor = System.getenv("NO_COLOR");
        if (StringUtils.isNotEmpty(noColor)) {
            return false;
        }

        String forceColor = System.getenv("FORCE_COLOR");
        if (Strings.CS.equals("0", forceColor)) {
            return false;
        }
        if (StringUtils.isNotEmpty(forceColor)) {
            return true;
        }
        // Most modern terminals support color
        return term != null || System.getenv("COLORTERM") != null
                || System.console() != null;
    }
}
