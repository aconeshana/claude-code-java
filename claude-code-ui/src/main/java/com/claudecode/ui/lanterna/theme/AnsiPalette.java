package com.claudecode.ui.lanterna.theme;

import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.TextColor;

import java.util.Map;

/**
 * 16-color VT100 ANSI palette → {@link RgbColor} mapping.
 */
public final class AnsiPalette {

    private AnsiPalette() {}

    // VGA / xterm canonical 16 colors. Each entry: RGB fallback + ANSI enum.
    // The ANSI enum is what the Lanterna adapter uses to emit raw SGR when
    // rendering on an ANSI theme.
    private static final Map<String, RgbColor> COLORS = Map.ofEntries(
        Map.entry("black",         new RgbColor(0, 0, 0,         TextColor.ANSI.BLACK)),
        Map.entry("red",           new RgbColor(170, 0, 0,       TextColor.ANSI.RED)),
        Map.entry("green",         new RgbColor(0, 170, 0,       TextColor.ANSI.GREEN)),
        Map.entry("yellow",        new RgbColor(170, 85, 0,      TextColor.ANSI.YELLOW)),
        Map.entry("blue",          new RgbColor(0, 0, 170,       TextColor.ANSI.BLUE)),
        Map.entry("magenta",       new RgbColor(170, 0, 170,     TextColor.ANSI.MAGENTA)),
        Map.entry("cyan",          new RgbColor(0, 170, 170,     TextColor.ANSI.CYAN)),
        Map.entry("white",         new RgbColor(170, 170, 170,   TextColor.ANSI.WHITE)),
        Map.entry("blackBright",   new RgbColor(85, 85, 85,      TextColor.ANSI.BLACK_BRIGHT)),
        Map.entry("redBright",     new RgbColor(255, 85, 85,     TextColor.ANSI.RED_BRIGHT)),
        Map.entry("greenBright",   new RgbColor(85, 255, 85,     TextColor.ANSI.GREEN_BRIGHT)),
        Map.entry("yellowBright",  new RgbColor(255, 255, 85,    TextColor.ANSI.YELLOW_BRIGHT)),
        Map.entry("blueBright",    new RgbColor(85, 85, 255,     TextColor.ANSI.BLUE_BRIGHT)),
        Map.entry("magentaBright", new RgbColor(255, 85, 255,    TextColor.ANSI.MAGENTA_BRIGHT)),
        Map.entry("cyanBright",    new RgbColor(85, 255, 255,    TextColor.ANSI.CYAN_BRIGHT)),
        Map.entry("whiteBright",   new RgbColor(255, 255, 255,   TextColor.ANSI.WHITE_BRIGHT))
    );


    public static RgbColor resolve(String tsColor) {
        if (tsColor == null) throw new IllegalArgumentException("null color");
        String s = tsColor.trim();
        if (Strings.CS.startsWith(s, "ansi:")) {
            String name = s.substring(5);
            RgbColor color = COLORS.get(name);
            if (color == null) {
                throw new IllegalArgumentException("unknown ANSI color: " + name);
            }
            return color;
        }
        if (Strings.CS.startsWith(s, "rgb(")) return RgbColor.parse(s);
        throw new IllegalArgumentException("unknown color literal: " + tsColor);
    }


    public static RgbColor resolveLenient(String tsColor) {
        if (tsColor == null) throw new IllegalArgumentException("null color");
        String s = tsColor.trim();
        if (Strings.CI.startsWith(s, "ansi:")) {
            // Preserve case of the name half — palette keys are camelCase.
            return resolve("ansi:" + s.substring(5));
        }
        return resolve(s);
    }
}
