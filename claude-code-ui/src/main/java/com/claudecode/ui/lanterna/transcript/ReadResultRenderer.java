package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_PREFIX;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendOrReplaceToolResultLine;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.List;
import java.util.Set;

/**
 * One-row summary of a resolved Read: what the model actually received, not the file body.
 * An image, a PDF or a rasterized page set has no text to fold, so the row states the
 * medium and its original size; text states the line count; a deduplicated re-read states
 * that nothing changed.
 *
 * <p>Authority: the shipped 2.1.197 bundle's {@code kOl}, registered as FileReadTool's
 * {@code renderToolResultMessage}. It takes no {@code verbose} flag and never renders the
 * file body, so the summary row is the complete result presentation in every mode. The
 * {@code Wn} wrapper it returns contributes only the {@code ⎿} gutter at {@code height:1}
 * ({@code Wn} emits {@code "  " + "⎿  "} dim, i.e. {@link ToolResultLines#INDENT_PREFIX}),
 * which is why no expand hint belongs on this row. Sizes come from {@code qa}, byte-for-byte
 * {@link FormatUtils#formatFileSize}.
 *
 * <p>Deviation: {@code kOl}'s empty-notebook arm is the one that returns a bare error
 * {@code Text} with no {@code Wn} wrapper, so upstream drops the gutter for it. This row keeps
 * the gutter, because the surrounding Lanterna transcript has no ink layout to inherit an
 * indent from and an unprefixed row would start at column 0.
 *
 * <ul>
 *   <li>{@code src/tools/FileReadTool/UI.tsx} — {@code renderToolResultMessage}: the
 *       {@code image} / {@code notebook} / {@code pdf} / {@code parts} / {@code text} /
 *       {@code file_unchanged} arms of the structured Read output.</li>
 * </ul>
 */
final class ReadResultRenderer {

    private ReadResultRenderer() {}

    /** True when {@code output} is a structured Read payload this renderer can summarize. */
    static boolean handles(JsonNode output) {
        return output != null && switch (output.path("type").asText("")) {
            case "image", "notebook", "pdf", "parts", "text", "file_unchanged" -> true;
            default -> false;
        };
    }

    static void render(JsonNode output, int replaceLine, MessagePanel panel) {
        JsonNode file = output.path("file");
        List<MessagePanel.Segment> row = switch (output.path("type").asText("")) {
            case "image" -> mediumRow("Read image (", file);
            case "pdf" -> mediumRow("Read PDF (", file);
            case "parts" -> partsRow(file);
            case "notebook" -> notebookRow(file);
            case "text" -> countRow(file.path("numLines").asInt(0),
                file.path("numLines").asInt(0) == 1 ? "line" : "lines");
            default -> List.of(new MessagePanel.Segment(
                INDENT_PREFIX + "Unchanged since last read", LanternaTheme.welcomeDim()));
        };
        appendOrReplaceToolResultLine(panel, replaceLine, row);
    }

    private static List<MessagePanel.Segment> mediumRow(String prefix, JsonNode file) {
        return List.of(new MessagePanel.Segment(
            INDENT_PREFIX + prefix + originalSize(file) + ")", TextColor.ANSI.DEFAULT));
    }

    private static List<MessagePanel.Segment> partsRow(JsonNode file) {
        int count = file.path("count").asInt(0);
        return List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Read ", TextColor.ANSI.DEFAULT),
            bold(Integer.toString(count)),
            new MessagePanel.Segment(
                " " + (count == 1 ? "page" : "pages") + " (" + originalSize(file) + ")",
                TextColor.ANSI.DEFAULT));
    }

    /** A notebook with no cells is the one Read outcome upstream paints in the error colour. */
    private static List<MessagePanel.Segment> notebookRow(JsonNode file) {
        JsonNode cells = file.path("cells");
        if (!cells.isArray() || cells.isEmpty()) {
            return List.of(new MessagePanel.Segment(
                INDENT_PREFIX + "No cells found in notebook", LanternaTheme.toolError()));
        }
        // 197 hard-codes the plural here ("Read <bold>N</bold> cells"), unlike the text arm.
        return countRow(cells.size(), "cells");
    }

    private static List<MessagePanel.Segment> countRow(int count, String noun) {
        return List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Read ", TextColor.ANSI.DEFAULT),
            bold(Integer.toString(count)),
            new MessagePanel.Segment(" " + noun, TextColor.ANSI.DEFAULT));
    }

    private static MessagePanel.Segment bold(String text) {
        return new MessagePanel.Segment(text, TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD));
    }

    private static String originalSize(JsonNode file) {
        return FormatUtils.formatFileSize(file.path("originalSize").asLong(0));
    }
}
