package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalTextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.Strings;

/**
 * Parses a static true-color ANSI sprite into Lanterna message segments.
 */
final class AnsiSpriteParser {

    private static final char ESC = '\u001B';

    record Sprite(int width, List<List<MessagePanel.Segment>> rows) {
        int height() { return rows.size(); }
    }

    private AnsiSpriteParser() {}

    static Sprite parse(String encoded) {
        String input = encoded.replace("\\u001B", String.valueOf(ESC))
            .replace("\r\n", "\n");
        List<List<MessagePanel.Segment>> rows = new ArrayList<>();
        List<MessagePanel.Segment> row = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        TextColor foreground = TextColor.ANSI.DEFAULT;
        TextColor background = null;
        int width = 0;

        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);
            if (c == ESC && i + 1 < input.length() && input.charAt(i + 1) == '[') {
                int end = input.indexOf('m', i + 2);
                if (end < 0) break;
                flush(row, text, foreground, background);
                ColorState state = applySgr(input.substring(i + 2, end), foreground, background);
                foreground = state.foreground();
                background = state.background();
                i = end + 1;
                continue;
            }
            if (c == '\n') {
                flush(row, text, foreground, background);
                width = Math.max(width, row.stream().mapToInt(
                    segment -> TerminalTextUtils.getColumnWidth(segment.text())).sum());
                rows.add(List.copyOf(row));
                row = new ArrayList<>();
                i++;
                continue;
            }
            text.append(c);
            i++;
        }
        flush(row, text, foreground, background);
        if (!row.isEmpty()) {
            width = Math.max(width, row.stream().mapToInt(
                segment -> TerminalTextUtils.getColumnWidth(segment.text())).sum());
            rows.add(List.copyOf(row));
        }
        while (!rows.isEmpty() && rows.getLast().isEmpty()) rows.removeLast();
        return new Sprite(width, List.copyOf(rows));
    }

    private static void flush(List<MessagePanel.Segment> row, StringBuilder text,
                              TextColor foreground, TextColor background) {
        if (text.isEmpty()) return;
        String value = text.toString();
        text.setLength(0);
        if (!row.isEmpty()) {
            MessagePanel.Segment previous = row.getLast();
            if (Objects.equals(previous.color(), foreground)
                    && Objects.equals(previous.bgColor(), background)
                    && previous.modifiers().isEmpty()) {
                row.set(row.size() - 1, new MessagePanel.Segment(
                    previous.text() + value, foreground, background));
                return;
            }
        }
        row.add(new MessagePanel.Segment(value, foreground, background));
    }

    private static ColorState applySgr(String code, TextColor foreground, TextColor background) {
        String[] parts = code.isEmpty() ? new String[]{"0"} : code.split(";");
        for (int i = 0; i < parts.length; i++) {
            int value;
            try { value = Integer.parseInt(parts[i]); }
            catch (NumberFormatException _) { continue; }
            if (value == 0) {
                foreground = TextColor.ANSI.DEFAULT;
                background = null;
            } else if (value == 39) {
                foreground = TextColor.ANSI.DEFAULT;
            } else if (value == 49) {
                background = null;
            } else if ((value == 38 || value == 48) && i + 4 < parts.length
                    && Strings.CS.equals("2", parts[i + 1])) {
                try {
                    TextColor rgb = new TextColor.RGB(
                        clamp(Integer.parseInt(parts[i + 2])),
                        clamp(Integer.parseInt(parts[i + 3])),
                        clamp(Integer.parseInt(parts[i + 4])));
                    if (value == 38) foreground = rgb;
                    else background = rgb;
                    i += 4;
                } catch (NumberFormatException _) { /* keep current colors */ }
            }
        }
        return new ColorState(foreground, background);
    }

    private static int clamp(int value) {
        return Math.clamp(value, 0, 255);
    }

    private record ColorState(TextColor foreground, TextColor background) {}
}
