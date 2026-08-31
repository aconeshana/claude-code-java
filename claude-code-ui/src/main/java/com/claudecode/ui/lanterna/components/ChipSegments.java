package com.claudecode.ui.lanterna.components;

import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.lanterna.repl.LanternaSessionSink;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Splits a user-echo line into styled segments, painting {@code [Image #N]} / {@code [Pasted text
 * #N +X lines]} / {@code [...Truncated text #N...]} chips in a distinct color while the surrounding
 * text keeps the base color.
 */
public final class ChipSegments {

    private ChipSegments() {}


    private static final Pattern CHIP_PATTERN = Pattern.compile(
        "\\[(Pasted text|Image|\\.\\.\\.Truncated text) #(\\d+)(?: \\+\\d+ lines)?(\\.)*]");

    /**
     * Split {@code text} into segments: plain runs in {@code baseColor}, chip
     * refs in {@code chipColor}, all over {@code bgColor}. Never returns empty —
     * a chip-less line yields a single base-colored segment.
     */
    public static List<MessagePanel.Segment> of(
            String text, TextColor baseColor, TextColor chipColor, TextColor bgColor) {
        List<MessagePanel.Segment> out = new ArrayList<>();
        Matcher m = CHIP_PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(new MessagePanel.Segment(text.substring(last, m.start()), baseColor, bgColor));
            }
            out.add(new MessagePanel.Segment(m.group(), chipColor, bgColor));
            last = m.end();
        }
        if (last < text.length()) {
            out.add(new MessagePanel.Segment(text.substring(last), baseColor, bgColor));
        }
        return out.isEmpty()
            ? List.of(new MessagePanel.Segment(text, baseColor, bgColor))
            : out;
    }
}
