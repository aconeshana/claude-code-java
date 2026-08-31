package com.claudecode.ui.lanterna.theme;

import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.TextColor;

import java.util.Objects;

/**
 * RGB color tuple — 8-bit channels.
 */
public record RgbColor(int r, int g, int b, TextColor.ANSI ansi) {

    public RgbColor {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            throw new IllegalArgumentException("RGB channel out of range: " + r + "," + g + "," + b);
        }
    }

    /** Canonical 3-arg constructor — RGB without ANSI provenance. */
    public RgbColor(int r, int g, int b) {
        this(r, g, b, null);
    }


    public static RgbColor parse(String tsRgb) {
        if (tsRgb == null) throw new IllegalArgumentException("null color string");
        String s = tsRgb.trim();
        if (!Strings.CS.startsWith(s, "rgb(") || !Strings.CS.endsWith(s, ")")) {
            throw new IllegalArgumentException("not a TS rgb() literal: " + tsRgb);
        }
        String[] parts = s.substring(4, s.length() - 1).split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected 3 channels: " + tsRgb);
        }
        return new RgbColor(
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim()));
    }


    public String toRgbString() {
        return "rgb(" + r + "," + g + "," + b + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RgbColor other)) return false;
        return r == other.r && g == other.g && b == other.b;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, g, b);
    }
}
