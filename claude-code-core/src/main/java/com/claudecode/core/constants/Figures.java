package com.claudecode.core.constants;

import com.claudecode.core.platform.Platform;

/**
 * Unicode glyph constants used across the terminal UI.
 */
public final class Figures {

    private Figures() {}


    public static final String BLACK_CIRCLE = Platform.IS_DARWIN ? "⏺" : "●";

    public static final String BULLET_OPERATOR     = "∙";
    public static final String TEARDROP_ASTERISK   = "✻";

    /** ↑ — used for opus 1m merge notice. */
    public static final String UP_ARROW            = "↑";
    /** ↓ — used for scroll hint. */
    public static final String DOWN_ARROW          = "↓";
    /** ↯ — used for fast mode indicator. */
    public static final String LIGHTNING_BOLT      = "↯";

    /** ❯ — figures.pointer, used for command/skill message prefix. */
    public static final String POINTER             = "❯";
    /** › — figures.pointerSmall, used for queued command echo. */
    public static final String POINTER_SMALL       = "›";
    /** ◯ — figures.circle, the un-viewed row bullet in the coordinator panel. */
    public static final String CIRCLE              = "◯";

    // Effort levels
    public static final String EFFORT_LOW          = "○"; // ○
    public static final String EFFORT_MEDIUM       = "◐"; // ◐
    public static final String EFFORT_HIGH         = "●"; // ●
    public static final String EFFORT_MAX          = "◉"; // ◉

    // Media / trigger status
    public static final String PLAY_ICON           = "▶"; // ▶
    public static final String PAUSE_ICON          = "⏸"; // ⏸

    // MCP subscription
    public static final String CHANNEL_ARROW       = "←"; // ← - inbound channel

    // ── figures package glyphs used by the AskUserQuestion design card ──────

    /** ✔ — {@code figures.tick}, the chosen-option and Submit-tab mark. */
    public static final String TICK                = "✔";
    /** ☒ — {@code figures.checkboxOn}, an answered question's tab. */
    public static final String CHECKBOX_ON         = "☒";
    /** ☐ — {@code figures.checkboxOff}, an unanswered question's tab. */
    public static final String CHECKBOX_OFF        = "☐";
    /** ← — {@code figures.arrowLeft}, the tab strip's previous-question affordance. */
    public static final String ARROW_LEFT          = "←";
    /** → — {@code figures.arrowRight}, the tab strip's next-question affordance and the
     *  answer marker on the review screen. */
    public static final String ARROW_RIGHT         = "→";
    /** ✂ — U+2702, the preview box's "N lines hidden" cut bar. */
    public static final String SCISSORS            = "✂";
    /** ⚠ — {@code figures.warning}, the review screen's unanswered-questions status icon. */
    public static final String WARNING             = "⚠";
    /** ● — U+25CF, {@code figures.bullet}. Unlike {@link #BLACK_CIRCLE} this glyph does not switch
     *  on the platform; the bundle's {@code figures} table uses it verbatim everywhere. */
    public static final String BULLET              = "●";

    // Review status (ultrareview diamond states)
    public static final String DIAMOND_OPEN        = "◇"; // ◇ - running
    public static final String DIAMOND_FILLED      = "◆"; // ◆ - completed/failed
    public static final String REFERENCE_MARK      = "※"; // ※ - komejirushi

    /** ▎ — left one-quarter block, blockquote prefix. */
    public static final String BLOCKQUOTE_BAR      = "▎";

    // ── Tool-result indent prefix ─────────────────────────────────────────



    // INDENT_COLS is the total visual width; RESULT_INDENT uses it as a repeat count.
    public static final int    INDENT_COLS   = 5;
    /** ⎿ — U+23BF, the result-branch glyph used as tool-output prefix. */
    public static final String RESULT_BRANCH = "⎿";
    /** Five-column first-line gutter; NBSP keeps the branch attached to its body. */
    public static final String RESULT_PREFIX = "  " + RESULT_BRANCH + " \u00a0";
    /** {@code "     "} — 5-space continuation indent matching {@link #RESULT_PREFIX} width. */
    public static final String RESULT_INDENT = " ".repeat(INDENT_COLS);

}
