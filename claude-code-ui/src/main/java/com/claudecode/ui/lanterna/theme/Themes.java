package com.claudecode.ui.lanterna.theme;

import java.util.Locale;
import java.util.Map;


public final class Themes {

    private Themes() {}

    public static final String DARK_NAME             = "dark";
    public static final String LIGHT_NAME            = "light";
    public static final String DARK_DALTONIZED_NAME  = "dark-daltonized";
    public static final String LIGHT_DALTONIZED_NAME = "light-daltonized";
    public static final String DARK_ANSI_NAME        = "dark-ansi";
    public static final String LIGHT_ANSI_NAME       = "light-ansi";

    



    public static final Theme DARK = new Theme(
        // Core UI
        RgbColor.parse("rgb(175,135,255)"),  // autoAccept — Electric violet
        RgbColor.parse("rgb(253,93,177)"),   // bashBorder — Bright pink
        RgbColor.parse("rgb(215,119,87)"),   // claude — Claude orange
        RgbColor.parse("rgb(235,159,127)"),  // claudeShimmer — Lighter claude orange
        RgbColor.parse("rgb(147,165,255)"),  // claudeBlue_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(177,195,255)"),  // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(177,185,249)"),  // permission — Light blue-purple
        RgbColor.parse("rgb(207,215,255)"),  // permissionShimmer
        RgbColor.parse("rgb(72,150,140)"),   // planMode — Muted sage green
        RgbColor.parse("rgb(71,130,200)"),   // ide — Muted blue
        RgbColor.parse("rgb(136,136,136)"),  // promptBorder — Medium gray
        RgbColor.parse("rgb(166,166,166)"),  // promptBorderShimmer
        RgbColor.parse("rgb(255,255,255)"),  // text — White
        RgbColor.parse("rgb(0,0,0)"),        // inverseText — Black
        RgbColor.parse("rgb(153,153,153)"),  // inactive — Light gray
        RgbColor.parse("rgb(193,193,193)"),  // inactiveShimmer
        RgbColor.parse("rgb(80,80,80)"),     // subtle — Dark gray
        RgbColor.parse("rgb(177,185,249)"),  // suggestion — Light blue-purple
        RgbColor.parse("rgb(177,185,249)"),  // remember
        RgbColor.parse("rgb(0,204,204)"),    // background — Bright cyan
        // Semantic
        RgbColor.parse("rgb(78,186,101)"),   // success — Bright green
        RgbColor.parse("rgb(255,107,128)"),  // error — Bright red
        RgbColor.parse("rgb(255,193,7)"),    // warning — Bright amber
        RgbColor.parse("rgb(175,135,255)"),  // merged — Electric violet (matches autoAccept)
        RgbColor.parse("rgb(255,223,57)"),   // warningShimmer
        // Diff
        RgbColor.parse("rgb(34,92,43)"),     // diffAdded — Dark green
        RgbColor.parse("rgb(122,41,54)"),    // diffRemoved — Dark red
        RgbColor.parse("rgb(71,88,74)"),     // diffAddedDimmed
        RgbColor.parse("rgb(105,72,77)"),    // diffRemovedDimmed
        RgbColor.parse("rgb(56,166,96)"),    // diffAddedWord — Medium green
        RgbColor.parse("rgb(179,89,107)"),   // diffRemovedWord — Softer red
        // Agent colors (Tailwind 600 palette)
        RgbColor.parse("rgb(220,38,38)"),    // red_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(37,99,235)"),    // blue_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(22,163,74)"),    // green_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(202,138,4)"),    // yellow_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(147,51,234)"),   // purple_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(234,88,12)"),    // orange_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(219,39,119)"),   // pink_FOR_SUBAGENTS_ONLY
        RgbColor.parse("rgb(8,145,178)"),    // cyan_FOR_SUBAGENTS_ONLY
        // Grove / Chrome
        RgbColor.parse("rgb(106,155,204)"),  // professionalBlue
        RgbColor.parse("rgb(251,188,4)"),    // chromeYellow
        // TUI V2
        RgbColor.parse("rgb(215,119,87)"),   // clawd_body
        RgbColor.parse("rgb(0,0,0)"),        // clawd_background
        RgbColor.parse("rgb(55,55,55)"),     // userMessageBackground — Lighter grey for contrast
        RgbColor.parse("rgb(70,70,70)"),     // userMessageBackgroundHover
        RgbColor.parse("rgb(44,50,62)"),     // messageActionsBackground — cool gray
        RgbColor.parse("rgb(38,79,120)"),    // selectionBg — VS Code dark default
        RgbColor.parse("rgb(65,60,65)"),     // bashMessageBackgroundColor
        RgbColor.parse("rgb(55,65,70)"),     // memoryBackgroundColor
        RgbColor.parse("rgb(177,185,249)"),  // rate_limit_fill
        RgbColor.parse("rgb(80,83,112)"),    // rate_limit_empty
        RgbColor.parse("rgb(255,120,20)"),   // fastMode — Electric orange
        RgbColor.parse("rgb(255,165,70)"),   // fastModeShimmer
        // Brief
        RgbColor.parse("rgb(122,180,232)"),  // briefLabelYou — Light blue
        RgbColor.parse("rgb(215,119,87)"),   // briefLabelClaude — Brand orange
        // Rainbow
        RgbColor.parse("rgb(235,95,87)"),    // rainbow_red
        RgbColor.parse("rgb(245,139,87)"),   // rainbow_orange
        RgbColor.parse("rgb(250,195,95)"),   // rainbow_yellow
        RgbColor.parse("rgb(145,200,130)"),  // rainbow_green
        RgbColor.parse("rgb(130,170,220)"),  // rainbow_blue
        RgbColor.parse("rgb(155,130,200)"),  // rainbow_indigo
        RgbColor.parse("rgb(200,130,180)"),  // rainbow_violet
        RgbColor.parse("rgb(250,155,147)"),  // rainbow_red_shimmer
        RgbColor.parse("rgb(255,185,137)"),  // rainbow_orange_shimmer
        RgbColor.parse("rgb(255,225,155)"),  // rainbow_yellow_shimmer
        RgbColor.parse("rgb(185,230,180)"),  // rainbow_green_shimmer
        RgbColor.parse("rgb(180,205,240)"),  // rainbow_blue_shimmer
        RgbColor.parse("rgb(195,180,230)"),  // rainbow_indigo_shimmer
        RgbColor.parse("rgb(230,180,210)")   // rainbow_violet_shimmer
    );

    



    public static final Theme LIGHT = new Theme(
        // Core UI
        RgbColor.parse("rgb(135,0,255)"),    // autoAccept — Electric violet
        RgbColor.parse("rgb(255,0,135)"),    // bashBorder — Vibrant pink
        RgbColor.parse("rgb(215,119,87)"),   // claude — Claude orange
        RgbColor.parse("rgb(245,149,117)"),  // claudeShimmer
        RgbColor.parse("rgb(87,105,247)"),   // claudeBlue_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(117,135,255)"),  // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(87,105,247)"),   // permission
        RgbColor.parse("rgb(137,155,255)"),  // permissionShimmer
        RgbColor.parse("rgb(0,102,102)"),    // planMode — Muted teal
        RgbColor.parse("rgb(71,130,200)"),   // ide
        RgbColor.parse("rgb(153,153,153)"),  // promptBorder
        RgbColor.parse("rgb(183,183,183)"),  // promptBorderShimmer
        RgbColor.parse("rgb(0,0,0)"),        // text — Black
        RgbColor.parse("rgb(255,255,255)"),  // inverseText — White
        RgbColor.parse("rgb(102,102,102)"),  // inactive
        RgbColor.parse("rgb(142,142,142)"),  // inactiveShimmer
        RgbColor.parse("rgb(175,175,175)"),  // subtle
        RgbColor.parse("rgb(87,105,247)"),   // suggestion
        RgbColor.parse("rgb(0,0,255)"),      // remember — pure Blue
        RgbColor.parse("rgb(0,153,153)"),    // background
        RgbColor.parse("rgb(44,122,57)"),    // success
        RgbColor.parse("rgb(171,43,63)"),    // error
        RgbColor.parse("rgb(150,108,30)"),   // warning
        RgbColor.parse("rgb(135,0,255)"),    // merged (matches autoAccept)
        RgbColor.parse("rgb(200,158,80)"),   // warningShimmer
        RgbColor.parse("rgb(105,219,124)"),  // diffAdded — Light green
        RgbColor.parse("rgb(255,168,180)"),  // diffRemoved — Light red
        RgbColor.parse("rgb(199,225,203)"),  // diffAddedDimmed
        RgbColor.parse("rgb(253,210,216)"),  // diffRemovedDimmed
        RgbColor.parse("rgb(47,157,68)"),    // diffAddedWord
        RgbColor.parse("rgb(209,69,75)"),    // diffRemovedWord
        // Agent colors — same Tailwind 600 palette as DARK.
        RgbColor.parse("rgb(220,38,38)"),
        RgbColor.parse("rgb(37,99,235)"),
        RgbColor.parse("rgb(22,163,74)"),
        RgbColor.parse("rgb(202,138,4)"),
        RgbColor.parse("rgb(147,51,234)"),
        RgbColor.parse("rgb(234,88,12)"),
        RgbColor.parse("rgb(219,39,119)"),
        RgbColor.parse("rgb(8,145,178)"),
        RgbColor.parse("rgb(106,155,204)"),  // professionalBlue
        RgbColor.parse("rgb(251,188,4)"),    // chromeYellow
        RgbColor.parse("rgb(215,119,87)"),   // clawd_body
        RgbColor.parse("rgb(0,0,0)"),        // clawd_background
        RgbColor.parse("rgb(240,240,240)"),  // userMessageBackground
        RgbColor.parse("rgb(252,252,252)"),  // userMessageBackgroundHover
        RgbColor.parse("rgb(232,236,244)"),  // messageActionsBackground
        RgbColor.parse("rgb(180,213,255)"),  // selectionBg — light-mode VS Code blue
        RgbColor.parse("rgb(250,245,250)"),  // bashMessageBackgroundColor
        RgbColor.parse("rgb(230,245,250)"),  // memoryBackgroundColor
        RgbColor.parse("rgb(87,105,247)"),   // rate_limit_fill
        RgbColor.parse("rgb(39,47,111)"),    // rate_limit_empty
        RgbColor.parse("rgb(255,106,0)"),    // fastMode
        RgbColor.parse("rgb(255,150,50)"),   // fastModeShimmer
        RgbColor.parse("rgb(37,99,235)"),    // briefLabelYou — Blue
        RgbColor.parse("rgb(215,119,87)"),   // briefLabelClaude
        // Rainbow — theme-independent palette.
        RgbColor.parse("rgb(235,95,87)"),
        RgbColor.parse("rgb(245,139,87)"),
        RgbColor.parse("rgb(250,195,95)"),
        RgbColor.parse("rgb(145,200,130)"),
        RgbColor.parse("rgb(130,170,220)"),
        RgbColor.parse("rgb(155,130,200)"),
        RgbColor.parse("rgb(200,130,180)"),
        RgbColor.parse("rgb(250,155,147)"),
        RgbColor.parse("rgb(255,185,137)"),
        RgbColor.parse("rgb(255,225,155)"),
        RgbColor.parse("rgb(185,230,180)"),
        RgbColor.parse("rgb(180,205,240)"),
        RgbColor.parse("rgb(195,180,230)"),
        RgbColor.parse("rgb(230,180,210)")
    );







    public static final Theme DARK_DALTONIZED = new Theme(
        RgbColor.parse("rgb(175,135,255)"),  // autoAccept
        RgbColor.parse("rgb(51,153,255)"),   // bashBorder — Bright blue (replaces pink)
        RgbColor.parse("rgb(255,153,51)"),   // claude — Orange adjusted for deuteranopia
        RgbColor.parse("rgb(255,183,101)"),  // claudeShimmer
        RgbColor.parse("rgb(153,204,255)"),  // claudeBlue_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(183,224,255)"),  // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(153,204,255)"),  // permission
        RgbColor.parse("rgb(183,224,255)"),  // permissionShimmer
        RgbColor.parse("rgb(102,153,153)"),  // planMode — Muted gray-teal
        RgbColor.parse("rgb(71,130,200)"),   // ide
        RgbColor.parse("rgb(136,136,136)"),  // promptBorder
        RgbColor.parse("rgb(166,166,166)"),  // promptBorderShimmer
        RgbColor.parse("rgb(255,255,255)"),  // text
        RgbColor.parse("rgb(0,0,0)"),        // inverseText
        RgbColor.parse("rgb(153,153,153)"),  // inactive
        RgbColor.parse("rgb(193,193,193)"),  // inactiveShimmer
        RgbColor.parse("rgb(80,80,80)"),     // subtle
        RgbColor.parse("rgb(153,204,255)"),  // suggestion
        RgbColor.parse("rgb(153,204,255)"),  // remember
        RgbColor.parse("rgb(0,204,204)"),    // background
        RgbColor.parse("rgb(51,153,255)"),   // success — Blue (NOT green!)
        RgbColor.parse("rgb(255,102,102)"),  // error
        RgbColor.parse("rgb(255,204,0)"),    // warning — yellow-orange for deuteranopia
        RgbColor.parse("rgb(175,135,255)"),  // merged
        RgbColor.parse("rgb(255,234,50)"),   // warningShimmer
        // Diff — blue↔red instead of green↔red
        RgbColor.parse("rgb(0,68,102)"),     // diffAdded — Dark blue
        RgbColor.parse("rgb(102,0,0)"),      // diffRemoved
        RgbColor.parse("rgb(62,81,91)"),     // diffAddedDimmed
        RgbColor.parse("rgb(62,44,44)"),     // diffRemovedDimmed
        RgbColor.parse("rgb(0,119,179)"),    // diffAddedWord
        RgbColor.parse("rgb(179,0,0)"),      // diffRemovedWord
        // Agent colors — bright daltonism-friendly variants.
        RgbColor.parse("rgb(255,102,102)"),
        RgbColor.parse("rgb(102,178,255)"),
        RgbColor.parse("rgb(102,255,102)"),
        RgbColor.parse("rgb(255,255,102)"),
        RgbColor.parse("rgb(178,102,255)"),
        RgbColor.parse("rgb(255,178,102)"),
        RgbColor.parse("rgb(255,153,204)"),
        RgbColor.parse("rgb(102,204,204)"),
        RgbColor.parse("rgb(106,155,204)"),  // professionalBlue
        RgbColor.parse("rgb(251,188,4)"),    // chromeYellow
        // TUI V2 — same as DARK
        RgbColor.parse("rgb(215,119,87)"),
        RgbColor.parse("rgb(0,0,0)"),
        RgbColor.parse("rgb(55,55,55)"),
        RgbColor.parse("rgb(70,70,70)"),
        RgbColor.parse("rgb(44,50,62)"),
        RgbColor.parse("rgb(38,79,120)"),
        RgbColor.parse("rgb(65,60,65)"),
        RgbColor.parse("rgb(55,65,70)"),
        RgbColor.parse("rgb(153,204,255)"),  // rate_limit_fill
        RgbColor.parse("rgb(69,92,115)"),    // rate_limit_empty
        RgbColor.parse("rgb(255,120,20)"),
        RgbColor.parse("rgb(255,165,70)"),
        RgbColor.parse("rgb(122,180,232)"),  // briefLabelYou
        RgbColor.parse("rgb(255,153,51)"),   // briefLabelClaude — daltonism-adjusted
        // Rainbow — theme-independent
        RgbColor.parse("rgb(235,95,87)"),
        RgbColor.parse("rgb(245,139,87)"),
        RgbColor.parse("rgb(250,195,95)"),
        RgbColor.parse("rgb(145,200,130)"),
        RgbColor.parse("rgb(130,170,220)"),
        RgbColor.parse("rgb(155,130,200)"),
        RgbColor.parse("rgb(200,130,180)"),
        RgbColor.parse("rgb(250,155,147)"),
        RgbColor.parse("rgb(255,185,137)"),
        RgbColor.parse("rgb(255,225,155)"),
        RgbColor.parse("rgb(185,230,180)"),
        RgbColor.parse("rgb(180,205,240)"),
        RgbColor.parse("rgb(195,180,230)"),
        RgbColor.parse("rgb(230,180,210)")
    );

    public static final Theme LIGHT_DALTONIZED = new Theme(
        RgbColor.parse("rgb(135,0,255)"),    // autoAccept — Electric violet
        RgbColor.parse("rgb(0,102,204)"),    // bashBorder — Blue (replaces pink)
        RgbColor.parse("rgb(255,153,51)"),   // claude — deuteranopia-adjusted orange
        RgbColor.parse("rgb(255,183,101)"),  // claudeShimmer
        RgbColor.parse("rgb(51,102,255)"),   // claudeBlue_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(101,152,255)"),  // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        RgbColor.parse("rgb(51,102,255)"),   // permission
        RgbColor.parse("rgb(101,152,255)"),  // permissionShimmer
        RgbColor.parse("rgb(51,102,102)"),   // planMode — Muted blue-gray
        RgbColor.parse("rgb(71,130,200)"),   // ide
        RgbColor.parse("rgb(153,153,153)"),  // promptBorder
        RgbColor.parse("rgb(183,183,183)"),  // promptBorderShimmer
        RgbColor.parse("rgb(0,0,0)"),        // text — Black
        RgbColor.parse("rgb(255,255,255)"),  // inverseText — White
        RgbColor.parse("rgb(102,102,102)"),  // inactive
        RgbColor.parse("rgb(142,142,142)"),  // inactiveShimmer
        RgbColor.parse("rgb(175,175,175)"),  // subtle
        RgbColor.parse("rgb(51,102,255)"),   // suggestion
        RgbColor.parse("rgb(51,102,255)"),   // remember
        RgbColor.parse("rgb(0,153,153)"),    // background
        RgbColor.parse("rgb(0,102,153)"),    // success — Blue (NOT green) for deuteranopia
        RgbColor.parse("rgb(204,0,0)"),      // error — pure red for distinction
        RgbColor.parse("rgb(255,153,0)"),    // warning — daltonized orange
        RgbColor.parse("rgb(135,0,255)"),    // merged (matches autoAccept)
        RgbColor.parse("rgb(255,183,50)"),   // warningShimmer
        RgbColor.parse("rgb(153,204,255)"),  // diffAdded — Light blue (NOT green!)
        RgbColor.parse("rgb(255,204,204)"),  // diffRemoved — Light red
        RgbColor.parse("rgb(209,231,253)"),  // diffAddedDimmed
        RgbColor.parse("rgb(255,233,233)"),  // diffRemovedDimmed
        RgbColor.parse("rgb(51,102,204)"),   // diffAddedWord — Medium blue
        RgbColor.parse("rgb(153,51,51)"),    // diffRemovedWord
        // Agent colors — pure / saturated daltonism-friendly palette.
        RgbColor.parse("rgb(204,0,0)"),      // red
        RgbColor.parse("rgb(0,102,204)"),    // blue
        RgbColor.parse("rgb(0,204,0)"),      // green
        RgbColor.parse("rgb(255,204,0)"),    // yellow
        RgbColor.parse("rgb(128,0,128)"),    // purple — true purple
        RgbColor.parse("rgb(255,128,0)"),    // orange
        RgbColor.parse("rgb(255,102,178)"),  // pink
        RgbColor.parse("rgb(0,178,178)"),    // cyan
        // Grove / Chrome
        RgbColor.parse("rgb(106,155,204)"),  // professionalBlue
        RgbColor.parse("rgb(251,188,4)"),    // chromeYellow
        // TUI V2 — light chrome
        RgbColor.parse("rgb(215,119,87)"),   // clawd_body
        RgbColor.parse("rgb(0,0,0)"),        // clawd_background
        RgbColor.parse("rgb(220,220,220)"),  // userMessageBackground — darker for light bg
        RgbColor.parse("rgb(232,232,232)"),  // userMessageBackgroundHover
        RgbColor.parse("rgb(210,216,226)"),  // messageActionsBackground
        RgbColor.parse("rgb(180,213,255)"),  // selectionBg
        RgbColor.parse("rgb(250,245,250)"),  // bashMessageBackgroundColor
        RgbColor.parse("rgb(230,245,250)"),  // memoryBackgroundColor
        RgbColor.parse("rgb(51,102,255)"),   // rate_limit_fill
        RgbColor.parse("rgb(23,46,114)"),    // rate_limit_empty
        RgbColor.parse("rgb(255,106,0)"),    // fastMode
        RgbColor.parse("rgb(255,150,50)"),   // fastModeShimmer
        RgbColor.parse("rgb(37,99,235)"),    // briefLabelYou — Blue
        RgbColor.parse("rgb(255,153,51)"),   // briefLabelClaude — matches claude
        // Rainbow — theme-independent
        RgbColor.parse("rgb(235,95,87)"),
        RgbColor.parse("rgb(245,139,87)"),
        RgbColor.parse("rgb(250,195,95)"),
        RgbColor.parse("rgb(145,200,130)"),
        RgbColor.parse("rgb(130,170,220)"),
        RgbColor.parse("rgb(155,130,200)"),
        RgbColor.parse("rgb(200,130,180)"),
        RgbColor.parse("rgb(250,155,147)"),
        RgbColor.parse("rgb(255,185,137)"),
        RgbColor.parse("rgb(255,225,155)"),
        RgbColor.parse("rgb(185,230,180)"),
        RgbColor.parse("rgb(180,205,240)"),
        RgbColor.parse("rgb(195,180,230)"),
        RgbColor.parse("rgb(230,180,210)")
    );


    public static final Theme LIGHT_ANSI = new Theme(
        AnsiPalette.resolve("ansi:magenta"),       // autoAccept
        AnsiPalette.resolve("ansi:magenta"),       // bashBorder
        AnsiPalette.resolve("ansi:redBright"),     // claude
        AnsiPalette.resolve("ansi:yellowBright"),  // claudeShimmer
        AnsiPalette.resolve("ansi:blue"),          // claudeBlue_FOR_SYSTEM_SPINNER
        AnsiPalette.resolve("ansi:blueBright"),    // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        AnsiPalette.resolve("ansi:blue"),          // permission
        AnsiPalette.resolve("ansi:blueBright"),    // permissionShimmer
        AnsiPalette.resolve("ansi:cyan"),          // planMode
        AnsiPalette.resolve("ansi:blueBright"),    // ide
        AnsiPalette.resolve("ansi:white"),         // promptBorder
        AnsiPalette.resolve("ansi:whiteBright"),   // promptBorderShimmer
        AnsiPalette.resolve("ansi:black"),         // text
        AnsiPalette.resolve("ansi:white"),         // inverseText
        AnsiPalette.resolve("ansi:blackBright"),   // inactive
        AnsiPalette.resolve("ansi:white"),         // inactiveShimmer
        AnsiPalette.resolve("ansi:blackBright"),   // subtle
        AnsiPalette.resolve("ansi:blue"),          // suggestion
        AnsiPalette.resolve("ansi:blue"),          // remember
        AnsiPalette.resolve("ansi:cyan"),          // background
        AnsiPalette.resolve("ansi:green"),         // success
        AnsiPalette.resolve("ansi:red"),           // error
        AnsiPalette.resolve("ansi:yellow"),        // warning
        AnsiPalette.resolve("ansi:magenta"),       // merged
        AnsiPalette.resolve("ansi:yellowBright"),  // warningShimmer
        AnsiPalette.resolve("ansi:green"),         // diffAdded
        AnsiPalette.resolve("ansi:red"),           // diffRemoved
        AnsiPalette.resolve("ansi:green"),         // diffAddedDimmed
        AnsiPalette.resolve("ansi:red"),           // diffRemovedDimmed
        AnsiPalette.resolve("ansi:greenBright"),   // diffAddedWord
        AnsiPalette.resolve("ansi:redBright"),     // diffRemovedWord
        // Agents
        AnsiPalette.resolve("ansi:red"),
        AnsiPalette.resolve("ansi:blue"),
        AnsiPalette.resolve("ansi:green"),
        AnsiPalette.resolve("ansi:yellow"),
        AnsiPalette.resolve("ansi:magenta"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:magentaBright"),
        AnsiPalette.resolve("ansi:cyan"),
        // Grove / Chrome
        AnsiPalette.resolve("ansi:blueBright"),    // professionalBlue
        AnsiPalette.resolve("ansi:yellow"),        // chromeYellow
        // TUI V2
        AnsiPalette.resolve("ansi:redBright"),     // clawd_body
        AnsiPalette.resolve("ansi:black"),         // clawd_background
        AnsiPalette.resolve("ansi:white"),         // userMessageBackground
        AnsiPalette.resolve("ansi:whiteBright"),   // userMessageBackgroundHover
        AnsiPalette.resolve("ansi:white"),         // messageActionsBackground
        AnsiPalette.resolve("ansi:cyan"),          // selectionBg
        AnsiPalette.resolve("ansi:whiteBright"),   // bashMessageBackgroundColor
        AnsiPalette.resolve("ansi:white"),         // memoryBackgroundColor
        AnsiPalette.resolve("ansi:yellow"),        // rate_limit_fill
        AnsiPalette.resolve("ansi:black"),         // rate_limit_empty
        AnsiPalette.resolve("ansi:red"),           // fastMode
        AnsiPalette.resolve("ansi:redBright"),     // fastModeShimmer
        AnsiPalette.resolve("ansi:blue"),          // briefLabelYou
        AnsiPalette.resolve("ansi:redBright"),     // briefLabelClaude
        // Rainbow
        AnsiPalette.resolve("ansi:red"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:yellow"),
        AnsiPalette.resolve("ansi:green"),
        AnsiPalette.resolve("ansi:cyan"),
        AnsiPalette.resolve("ansi:blue"),
        AnsiPalette.resolve("ansi:magenta"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:yellow"),
        AnsiPalette.resolve("ansi:yellowBright"),
        AnsiPalette.resolve("ansi:greenBright"),
        AnsiPalette.resolve("ansi:cyanBright"),
        AnsiPalette.resolve("ansi:blueBright"),
        AnsiPalette.resolve("ansi:magentaBright")
    );


    public static final Theme DARK_ANSI = new Theme(
        AnsiPalette.resolve("ansi:magentaBright"), // autoAccept
        AnsiPalette.resolve("ansi:magentaBright"), // bashBorder
        AnsiPalette.resolve("ansi:redBright"),     // claude
        AnsiPalette.resolve("ansi:yellowBright"),  // claudeShimmer
        AnsiPalette.resolve("ansi:blueBright"),    // claudeBlue_FOR_SYSTEM_SPINNER
        AnsiPalette.resolve("ansi:blueBright"),    // claudeBlueShimmer_FOR_SYSTEM_SPINNER
        AnsiPalette.resolve("ansi:blueBright"),    // permission
        AnsiPalette.resolve("ansi:blueBright"),    // permissionShimmer
        AnsiPalette.resolve("ansi:cyanBright"),    // planMode
        AnsiPalette.resolve("ansi:blue"),          // ide
        AnsiPalette.resolve("ansi:white"),         // promptBorder
        AnsiPalette.resolve("ansi:whiteBright"),   // promptBorderShimmer
        AnsiPalette.resolve("ansi:whiteBright"),   // text
        AnsiPalette.resolve("ansi:black"),         // inverseText
        AnsiPalette.resolve("ansi:white"),         // inactive
        AnsiPalette.resolve("ansi:whiteBright"),   // inactiveShimmer
        AnsiPalette.resolve("ansi:white"),         // subtle
        AnsiPalette.resolve("ansi:blueBright"),    // suggestion
        AnsiPalette.resolve("ansi:blueBright"),    // remember
        AnsiPalette.resolve("ansi:cyanBright"),    // background
        AnsiPalette.resolve("ansi:greenBright"),   // success
        AnsiPalette.resolve("ansi:redBright"),     // error
        AnsiPalette.resolve("ansi:yellowBright"),  // warning
        AnsiPalette.resolve("ansi:magentaBright"), // merged
        AnsiPalette.resolve("ansi:yellowBright"),  // warningShimmer
        AnsiPalette.resolve("ansi:green"),         // diffAdded
        AnsiPalette.resolve("ansi:red"),           // diffRemoved
        AnsiPalette.resolve("ansi:green"),         // diffAddedDimmed
        AnsiPalette.resolve("ansi:red"),           // diffRemovedDimmed
        AnsiPalette.resolve("ansi:greenBright"),   // diffAddedWord
        AnsiPalette.resolve("ansi:redBright"),     // diffRemovedWord
        // Agents
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:blueBright"),
        AnsiPalette.resolve("ansi:greenBright"),
        AnsiPalette.resolve("ansi:yellowBright"),
        AnsiPalette.resolve("ansi:magentaBright"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:magentaBright"),
        AnsiPalette.resolve("ansi:cyanBright"),

        RgbColor.parse("rgb(106,155,204)"),        // professionalBlue
        AnsiPalette.resolve("ansi:yellowBright"),  // chromeYellow
        // TUI V2
        AnsiPalette.resolve("ansi:redBright"),     // clawd_body
        AnsiPalette.resolve("ansi:black"),         // clawd_background
        AnsiPalette.resolve("ansi:blackBright"),   // userMessageBackground
        AnsiPalette.resolve("ansi:white"),         // userMessageBackgroundHover
        AnsiPalette.resolve("ansi:blackBright"),   // messageActionsBackground
        AnsiPalette.resolve("ansi:blue"),          // selectionBg
        AnsiPalette.resolve("ansi:black"),         // bashMessageBackgroundColor
        AnsiPalette.resolve("ansi:blackBright"),   // memoryBackgroundColor
        AnsiPalette.resolve("ansi:yellow"),        // rate_limit_fill
        AnsiPalette.resolve("ansi:white"),         // rate_limit_empty
        AnsiPalette.resolve("ansi:redBright"),     // fastMode
        AnsiPalette.resolve("ansi:redBright"),     // fastModeShimmer
        AnsiPalette.resolve("ansi:blueBright"),    // briefLabelYou
        AnsiPalette.resolve("ansi:redBright"),     // briefLabelClaude
        // Rainbow
        AnsiPalette.resolve("ansi:red"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:yellow"),
        AnsiPalette.resolve("ansi:green"),
        AnsiPalette.resolve("ansi:cyan"),
        AnsiPalette.resolve("ansi:blue"),
        AnsiPalette.resolve("ansi:magenta"),
        AnsiPalette.resolve("ansi:redBright"),
        AnsiPalette.resolve("ansi:yellow"),
        AnsiPalette.resolve("ansi:yellowBright"),
        AnsiPalette.resolve("ansi:greenBright"),
        AnsiPalette.resolve("ansi:cyanBright"),
        AnsiPalette.resolve("ansi:blueBright"),
        AnsiPalette.resolve("ansi:magentaBright")
    );

    private static final Map<String, Theme> THEMES_BY_NAME = Map.of(
        DARK_NAME,             DARK,
        LIGHT_NAME,            LIGHT,
        DARK_DALTONIZED_NAME,  DARK_DALTONIZED,
        LIGHT_DALTONIZED_NAME, LIGHT_DALTONIZED,
        DARK_ANSI_NAME,        DARK_ANSI,
        LIGHT_ANSI_NAME,       LIGHT_ANSI
    );

    /**
     * Returns the palette for the given theme name. Unknown names fall back to
     * {@link #DARK}, matching the compatibility {@code getTheme} {@code default} branch.
     */
    public static Theme get(String name) {
        if (name == null) return DARK;
        Theme t = THEMES_BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return t != null ? t : DARK;
    }
}
