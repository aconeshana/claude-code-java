package com.claudecode.ui.lanterna.components;

import com.googlecode.lanterna.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Minimal segment/line model the plugin panel's tab controllers render into.
 */
public final class StyledText {

    private StyledText() {}

    public record Seg(String text, TextColor color, boolean bold) {}

    public record Line(List<Seg> segs) {
        public String plain() {
            return segs.stream().map(Seg::text).collect(Collectors.joining());
        }
    }

    public static Seg seg(String text, TextColor color) {
        return new Seg(text, color, false);
    }

    public static Seg bold(String text, TextColor color) {
        return new Seg(text, color, true);
    }

    public static Line line(Seg... segs) {
        return new Line(List.of(segs));
    }

    public static Line blank() {
        return new Line(List.of());
    }

    /** Convenience: one whole line in a single style. */
    public static Line line(String text, TextColor color) {
        return line(seg(text, color));
    }

    public static Line boldLine(String text, TextColor color) {
        return line(bold(text, color));
    }

    public static List<String> plain(List<Line> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (Line l : lines) {
            out.add(l.plain());
        }
        return out;
    }

}
