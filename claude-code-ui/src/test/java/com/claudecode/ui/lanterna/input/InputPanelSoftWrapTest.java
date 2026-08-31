package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

class InputPanelSoftWrapTest {

    @Test
    void paneWidthControlsVisualRowsWithoutChangingPromptText() {
        InputPanel panel = new InputPanel();
        panel.setSize(new TerminalSize(10, 20));
        panel.setText("alpha beta");

        assertEquals("alpha beta", panel.getText());
        assertEquals(2, panel.textRowsForTest());

        panel.setSize(new TerminalSize(20, 20));

        assertEquals("alpha beta", panel.getText());
        assertEquals(1, panel.textRowsForTest());
    }

    @Test
    void cjkInputUsesDisplayColumnsWhenComputingPanelHeight() {
        InputPanel panel = new InputPanel();
        panel.setSize(new TerminalSize(8, 20));
        panel.setText("中文测试");

        assertEquals(2, panel.textRowsForTest());
    }

    @Test
    void arrowKeysMoveAcrossSoftWrappedRowsBeforeHistory() {
        InputPanel panel = wrappedPanel();
        panel.setCaretOffsetForTest(9);

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_UP));
        assertEquals(3, panel.caretOffsetForTest());

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals(9, panel.caretOffsetForTest());
    }

    @Test
    void ctrlPAndCtrlNShareSoftWrappedNavigation() {
        InputPanel panel = wrappedPanel();
        panel.setCaretOffsetForTest(9);

        panel.handleKeyForTest(new KeyStroke('p', true, false));
        assertEquals(3, panel.caretOffsetForTest());

        panel.handleKeyForTest(new KeyStroke('n', true, false));
        assertEquals(9, panel.caretOffsetForTest());
    }

    @Test
    void visualCursorCoordinatesTrackSoftWrapAndWideCharacters() {
        InputPanel panel = new InputPanel();
        panel.setSize(new TerminalSize(8, 20));
        panel.setText("中文A");
        panel.setCaretOffsetForTest(2);

        assertEquals(new PromptTextLayout.Position(1, 0), panel.visualCaretPositionForTest());
    }

    private static InputPanel wrappedPanel() {
        InputPanel panel = new InputPanel();
        panel.setSize(new TerminalSize(10, 20));
        panel.setText("alpha beta");
        return panel;
    }
}
