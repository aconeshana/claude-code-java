package com.claudecode.ui.lanterna.theme;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.permissions.PermissionMode;
import com.googlecode.lanterna.TextColor;

/**
 * Maps Claude Code's 6-theme color system to Lanterna TextColor.RGB.
 */
public final class LanternaTheme {

    public enum Scheme { AUTO, DARK, LIGHT, DARK_DALTONIZED, LIGHT_DALTONIZED, DARK_ANSI, LIGHT_ANSI }

    private static volatile Scheme current = Scheme.DARK;
    private static volatile SystemThemeWatcher autoWatcher;
    private static volatile Runnable onAutoResolve;

    public static void setScheme(Scheme scheme) {
        current = scheme != null ? scheme : Scheme.DARK;
        if (current == Scheme.AUTO) ensureAutoWatcher();
        else stopAutoWatcher();
    }

    /**
     * Parses a kebab-case theme name ({@code "dark"}, {@code "light-daltonized"}, …)
     * into a {@link Scheme}. Inverse of {@link #activeThemeName}. Returns {@code null}
     * for unrecognized names — callers should treat that as "leave the setting unchanged".
     */
    public static Scheme schemeFromName(String name) {
        if (name == null) return null;
        try {
            return Scheme.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * Returns the active palette (the {@link Themes} record for the current
     * scheme). Use this when you need to read raw RGB tuples — e.g. modules
     * outside the Lanterna package that emit ANSI escape sequences directly
     * ({@link com.claudecode.ui.MarkdownRenderer}, {@link com.claudecode.ui.SyntaxHighlighter}).
     */
    public static Theme activeTheme() {
        return theme();
    }


    public static String activeThemeName() {
        Scheme resolved = resolveAuto(current);
        return resolved.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Set a callback that fires whenever {@link Scheme#AUTO} resolves to a new dark/light value. */
    public static void setOnAutoResolve(Runnable callback) {
        onAutoResolve = callback;
        if (current == Scheme.AUTO) ensureAutoWatcher();
    }

    /** Returns the active {@link Theme} for this scheme. */
    private static Theme theme() {
        Scheme resolved = resolveAuto(current);
        return Themes.get(resolved.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** Resolve {@link Scheme#AUTO} via {@link SystemTheme}; passthrough for explicit schemes. */
    private static Scheme resolveAuto(Scheme s) {
        if (s != Scheme.AUTO) return s;
        return SystemTheme.getSystemTheme() == SystemTheme.Mode.LIGHT ? Scheme.LIGHT : Scheme.DARK;
    }

    private static synchronized void ensureAutoWatcher() {
        if (autoWatcher != null) return;
        autoWatcher = new SystemThemeWatcher();
        autoWatcher.start(_ -> {
            Runnable cb = onAutoResolve;
            if (cb != null) cb.run();
        });
    }

    private static synchronized void stopAutoWatcher() {
        if (autoWatcher != null) {
            autoWatcher.stop();
            autoWatcher = null;
        }
    }

    // ── Brand ──────────────────────────────────────────────────────────────
    public static TextColor claude()               { return toLC(theme().claude()); }
    public static TextColor claudeShimmer()        { return toLC(theme().claudeShimmer()); }
/**
     * System-spinner blue — used while compact hooks are running.
     */
    public static TextColor systemSpinner()        { return toLC(theme().claudeBlue_FOR_SYSTEM_SPINNER()); }
/**
     * System-spinner shimmer blue.
     */
    public static TextColor systemSpinnerShimmer() { return toLC(theme().claudeBlueShimmer_FOR_SYSTEM_SPINNER()); }

    // ── Prompt ────────────────────────────────────────────────────────────
    public static TextColor promptBorder()  { return toLC(theme().promptBorder()); }
    public static TextColor bashBorder()    { return toLC(theme().bashBorder()); }
    public static TextColor bashBg()        { return toLC(theme().bashMessageBackgroundColor()); }
    public static TextColor inputText()     { return toLC(theme().text()); }
    public static TextColor ghostText()     { return toLC(theme().inactive()); }
    public static TextColor suggestion()    { return toLC(theme().suggestion()); }

    public static TextColor remember()      { return toLC(theme().remember()); }

    public static TextColor subtle()        { return toLC(theme().subtle()); }

    // ── Messages ──────────────────────────────────────────────────────────
    public static TextColor assistantDot()  { return toLC(theme().claude()); }
    public static TextColor thinking()      { return toLC(theme().inactive()); }
    public static TextColor toolSuccess()   { return toLC(theme().success()); }
    public static TextColor toolError()     { return toLC(theme().error()); }
    public static TextColor toolWarning()   { return toLC(theme().warning()); }
    public static TextColor divider()       { return toLC(theme().subtle()); }

    // ── Status bar ────────────────────────────────────────────────────────
    public static TextColor statusFg()      { return toLC(theme().inactive()); }
    public static TextColor statusCost()    { return toLC(theme().warning()); }
    public static TextColor modeAuto()      { return toLC(theme().autoAccept()); }
    public static TextColor modePlan()      { return toLC(theme().planMode()); }

// ── Agent colors  ───────────
// 8-color pool matches AGENT_COLORS: red, blue, green, yellow, purple, orange, pink, cyan
    public static TextColor agentRed()      { return toLC(theme().red_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentBlue()     { return toLC(theme().blue_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentGreen()    { return toLC(theme().green_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentYellow()   { return toLC(theme().yellow_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentPurple()   { return toLC(theme().purple_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentOrange()   { return toLC(theme().orange_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentPink()     { return toLC(theme().pink_FOR_SUBAGENTS_ONLY()); }
    public static TextColor agentCyan()     { return toLC(theme().cyan_FOR_SUBAGENTS_ONLY()); }

    /**
     * Theme-driven foreground for the swarm banner badge.
     */
    public static TextColor inverseText()   { return toLC(theme().inverseText()); }

/**
     * Maps an agent color name to its theme TextColor.
     */
    public static TextColor agentColor(String name) {
        if (name == null) return null;
        return switch (name) {
            case "red"    -> agentRed();
            case "blue"   -> agentBlue();
            case "green"  -> agentGreen();
            case "yellow" -> agentYellow();
            case "purple" -> agentPurple();
            case "orange" -> agentOrange();
            case "pink"   -> agentPink();
            case "cyan"   -> agentCyan();
            default       -> null;
        };
    }

    // ── Welcome screen ────────────────────────────────────────────────────
    public static TextColor welcomeBorder() { return toLC(theme().claude()); }
    public static TextColor welcomeDim()    { return toLC(theme().inactive()); }
    public static TextColor clawdBody()       { return toLC(theme().clawd_body()); }
    public static TextColor clawdBackground() { return toLC(theme().clawd_background()); }

// ── Permission mode hint colors ───────────────────────────────────────.
    public static TextColor bypassRed()    { return toLC(theme().error()); }
    public static TextColor autoYellow()   { return toLC(theme().warning()); }
    public static TextColor acceptPurple() { return toLC(theme().autoAccept()); }
    public static TextColor planTeal()     { return toLC(theme().planMode()); }
    public static TextColor permission()   { return toLC(theme().permission()); }



    public static TextColor diffAdded()        { return toLC(theme().diffAdded()); }

    public static TextColor diffRemoved()       { return toLC(theme().diffRemoved()); }

    public static TextColor diffAddedDimmed()   { return toLC(theme().diffAddedDimmed()); }

    public static TextColor diffRemovedDimmed() { return toLC(theme().diffRemovedDimmed()); }

    public static TextColor diffAddedWord()     { return toLC(theme().diffAddedWord()); }

    public static TextColor diffRemovedWord()   { return toLC(theme().diffRemovedWord()); }

    /**
     * Colors used by the structured code-diff renderer.
     *
     * <p>These deliberately do not reuse the similarly named theme keys above. The original
     *  uses a separate, substantially
     * darker background palette so Monokai comments remain readable inside changed lines.
     */
    public record DiffRenderPalette(
        TextColor addedLineBackground,
        TextColor addedWordBackground,
        TextColor addedDecoration,
        TextColor removedLineBackground,
        TextColor removedWordBackground,
        TextColor removedDecoration
    ) {}


    public static DiffRenderPalette diffRenderPalette() {
        Scheme resolved = resolveAuto(current);
        return switch (resolved) {
            case DARK -> new DiffRenderPalette(
                darkDiffColor(2, 40, 0, 22),
                darkDiffColor(4, 71, 0, 28),
                toLC(new RgbColor(80, 200, 80)),
                toLC(new RgbColor(61, 1, 0)),
                toLC(new RgbColor(92, 2, 0)),
                toLC(new RgbColor(220, 90, 90)));
            case DARK_DALTONIZED -> new DiffRenderPalette(
                darkDiffColor(0, 27, 41, 17),
                darkDiffColor(0, 48, 71, 24),
                toLC(new RgbColor(81, 160, 200)),
                toLC(new RgbColor(61, 1, 0)),
                toLC(new RgbColor(92, 2, 0)),
                toLC(new RgbColor(220, 90, 90)));
            case LIGHT -> new DiffRenderPalette(
                toLC(new RgbColor(220, 255, 220)),
                toLC(new RgbColor(178, 255, 178)),
                toLC(new RgbColor(36, 138, 61)),
                toLC(new RgbColor(255, 220, 220)),
                toLC(new RgbColor(255, 199, 199)),
                toLC(new RgbColor(207, 34, 46)));
            case LIGHT_DALTONIZED -> new DiffRenderPalette(
                toLC(new RgbColor(219, 237, 255)),
                toLC(new RgbColor(179, 217, 255)),
                toLC(new RgbColor(36, 87, 138)),
                toLC(new RgbColor(255, 220, 220)),
                toLC(new RgbColor(255, 199, 199)),
                toLC(new RgbColor(207, 34, 46)));
            case DARK_ANSI, LIGHT_ANSI -> new DiffRenderPalette(
                null, null, TextColor.ANSI.GREEN_BRIGHT,
                null, null, TextColor.ANSI.RED_BRIGHT);
            case AUTO -> throw new IllegalStateException("AUTO must be resolved before diff palette lookup");
        };
    }

/** matches color-diff's fixed xterm indices below truecolor for dark themes. */
    private static TextColor darkDiffColor(int r, int g, int b, int indexed) {
        return CHALK_LEVEL >= 3 ? new TextColor.RGB(r, g, b) : new TextColor.Indexed(indexed);
    }


    public static TextColor professionalBlue() { return toLC(theme().professionalBlue()); }

    /**
     * Resolve a {@link PermissionMode} to its theme-keyed display color.
     */
    public static TextColor colorFor(PermissionMode mode) {
        if (mode == null) return inputText();
        return switch (mode.colorKey()) {
            case TEXT        -> inputText();
            case PLAN_MODE   -> planTeal();
            case PERMISSION  -> toLC(theme().permission());
            case AUTO_ACCEPT -> acceptPurple();
            case ERROR       -> bypassRed();
            case WARNING     -> autoYellow();
        };
    }

    // ── User query row background ─────────────────────────────────────────
    public static TextColor userQueryBg()  { return toLC(theme().userMessageBackground()); }
    /** Selected logical-message background in Message Actions mode. */
    public static TextColor messageActionsBackground() {
        return toLC(theme().messageActionsBackground());
    }
    /** Subtle text color used by queued (pending) user messages. */
    public static TextColor queuedText()   { return toLC(theme().inactive()); }

    // ── Brief mode labels ─────────────────────────────────────────────────

    public static TextColor briefLabelYou() { return toLC(theme().briefLabelYou()); }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolved chalk-equivalent color level (0=none, 1=16, 2=256, 3=truecolor).
     */
    private static final int CHALK_LEVEL = resolveChalkLevel();

    /** Public accessor for the resolved chalk-equivalent level. Used for diagnostics. */
    public static int chalkLevel() { return CHALK_LEVEL; }

    private static int resolveChalkLevel() {
        String force = SubprocessEnvironment.get("FORCE_COLOR");
        if (force != null) {
            try {
                int n = Integer.parseInt(force);
                if (n >= 0 && n <= 3) return n;
            } catch (NumberFormatException _) { /* fall through */ }
        }
        if (SubprocessEnvironment.get("NO_COLOR") != null) return 0;
        if (Strings.CS.equals("1", SubprocessEnvironment.get(
                "CLAUDE_CODE_FORCE_TRUECOLOR"))) return 3;

        String term = System.getenv("TERM");
        if (Strings.CS.equals("dumb", term)) return 1;
        if (term == null
                && System.getenv("COLORTERM") == null
                && System.getenv("TERM_PROGRAM") == null) {
            return 1;
        }
        return 2;
    }

    /**
     * Convert RGB to xterm-256 index using chalk's algorithm exactly.
     * matches color-convert rgb.ansi256:
     *   greyscale: r==g==b → grey ramp 232-255 (or 16/231 at extremes)
     *   color cube: 16 + 36*round(r/255*5) + 6*round(g/255*5) + round(b/255*5)
     * Note: chalk uses Math.round; Lanterna's Indexed.fromRGB uses floor — they
     * diverge for values mid-way between cube steps (e.g. g=39 → round=1 vs floor=0).
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
     * Convert a {@link RgbColor} to a Lanterna {@link TextColor}.
     */
    public static TextColor toLC(RgbColor c) {
        if (c.ansi() != null) return c.ansi();
        if (CHALK_LEVEL < 3) {
            return new TextColor.Indexed(rgbToAnsi256(c.r(), c.g(), c.b()));
        }
        return new TextColor.RGB(c.r(), c.g(), c.b());
    }

    /**
     * Linear interpolation between two TextColors at factor {@code t} (0..1).
     */
    public static TextColor interpolate(TextColor from, TextColor to, double t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        if (from instanceof TextColor.RGB f && to instanceof TextColor.RGB tt) {
            int r = (int) (f.getRed()   + (tt.getRed()   - f.getRed())   * t);
            int g = (int) (f.getGreen() + (tt.getGreen() - f.getGreen()) * t);
            int b = (int) (f.getBlue()  + (tt.getBlue()  - f.getBlue())  * t);
            return new TextColor.RGB(r, g, b);
        }
        return t > 0.5 ? to : from;
    }

    private LanternaTheme() {}
}
