package com.claudecode.ui.lanterna.dialog;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
/**
 * Text-layout helpers shared by hand-drawn Lanterna dialogs.
 */
final class DialogText {

    private DialogText() { }

    /**
     * Wraps text at spaces while preserving explicit paragraph boundaries.
     * Words wider than {@code width} remain intact.
     */
    static List<String> wrapWords(String text, int width) {
        if (StringUtils.isEmpty(text)) return List.of();
        if (width <= 0) throw new IllegalArgumentException("width must be positive");

        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (line.isEmpty()) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
        }
        return List.copyOf(lines);
    }
}
