package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.constants.Figures;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TextColor;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class MessagePanelShellOutputTest {

    @Test
    void reprojectsFoldedOutputWhenTheTerminalWidthChanges() {
        MessagePanel panel = new MessagePanel();
        panel.appendToolOutput("x".repeat(100), LanternaTheme.welcomeDim(), false);

        List<String> wide = textRows(panel.displayRowsForTest(60));
        List<String> narrow = textRows(panel.displayRowsForTest(30));

        assertEquals(2, wide.size());
        assertEquals(Figures.RESULT_PREFIX + "x".repeat(50), wide.getFirst());
        assertEquals(4, narrow.size());
        assertEquals(Figures.RESULT_PREFIX + "x".repeat(20), narrow.getFirst());
        assertNotEquals(wide, narrow);
        assertTrue(Strings.CS.contains(narrow.getLast(), "… +2 lines"));
    }

    @Test
    void fullOutputModeRetainsEveryVisualRow() {
        MessagePanel panel = new MessagePanel();
        panel.appendToolOutput("x".repeat(50), LanternaTheme.welcomeDim(), true);

        List<String> rows = textRows(panel.displayRowsForTest(20));

        assertEquals(4, rows.size());
        assertTrue(rows.stream().noneMatch(row -> Strings.CS.contains(row, "lines (")));
        assertEquals(50, rows.stream().map(row -> row.substring(5))
            .mapToInt(String::length).sum());
    }

    @Test
    void canReplaceThePendingToolLineWithWidthAwareOutput() {
        MessagePanel panel = new MessagePanel();
        panel.appendLine("pending", LanternaTheme.welcomeDim());

        panel.updateToolOutputOrAppend(0, "x".repeat(50), LanternaTheme.welcomeDim(), false);

        List<String> rows = textRows(panel.displayRowsForTest(20));
        assertEquals(4, rows.size());
        assertTrue(Strings.CS.startsWith(rows.getFirst(), Figures.RESULT_PREFIX));
        assertTrue(Strings.CS.contains(rows.getLast(), "… +2 lines"));
    }

    @Test
    void parsesAnsiColorBeforeFoldingWithoutLeakingControlSequenceText() {
        MessagePanel panel = new MessagePanel();
        String output = "\u001b[38;5;153mcolored\u001b[0m plain";

        panel.appendToolOutput(output, LanternaTheme.welcomeDim(), false);

        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(80);
        assertEquals(1, rows.size());
        assertFalse(Strings.CS.contains(rows.getFirst().text(), "[38;5;153m"));
        assertTrue(rows.getFirst().segments().stream().anyMatch(segment ->
            Strings.CS.equals("colored", segment.text())
                && segment.color().equals(new TextColor.Indexed(153))));
        assertTrue(rows.getFirst().segments().stream().anyMatch(segment ->
            Strings.CS.contains(segment.text(), "plain")
                && segment.color().equals(LanternaTheme.welcomeDim())));
    }

    @Test
    void activeAnsiColorContinuesAcrossFoldedVisualRows() {
        MessagePanel panel = new MessagePanel();
        panel.appendToolOutput("\u001b[32m" + "x".repeat(100) + "\u001b[0m",
            LanternaTheme.welcomeDim(), false);

        List<MessagePanel.StyledLine> rows = panel.displayRowsForTest(30);

        assertEquals(4, rows.size());
        for (int i = 0; i < 3; i++) {
            assertTrue(rows.get(i).segments().stream().anyMatch(segment ->
                Strings.CS.contains(segment.text(), "x")
                    && segment.color().equals(TextColor.ANSI.GREEN)));
            assertFalse(Strings.CS.contains(rows.get(i).text(), "[32m"));
        }
    }

    private static List<String> textRows(List<MessagePanel.StyledLine> rows) {
        return rows.stream().map(MessagePanel.StyledLine::text).toList();
    }
}
