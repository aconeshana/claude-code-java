package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputPanelMultilineOffsetTest {

    @Test
    void typingOnSecondLineUsesAbsolutePromptOffset() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        panel.setText("abc\nDEF");
        panel.setCaretOffsetForTest(5);

        panel.handleKeyForTest(new KeyStroke('X', false, false));

        assertEquals("abc\nDXEF", panel.getText());
        assertEquals(6, panel.caretCol());
    }

    @Test
    void stashRestoresSecondLineCursorAsAnAbsoluteOffset() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        panel.setText("abc\nDEF");
        panel.setCaretOffsetForTest(5);

        panel.handleKeyForTest(new KeyStroke('s', true, false));
        panel.handleKeyForTest(new KeyStroke('s', true, false));
        panel.handleKeyForTest(new KeyStroke('X', false, false));

        assertEquals("abc\nDXEF", panel.getText());
    }

    @Test
    void queryChangedPublishesAbsoluteCursorOffset() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("abc\nDEF");
        panel.setCaretOffsetForTest(5);

        panel.handleKeyForTest(new KeyStroke('X', false, false));

        assertEquals(6, actions.cursorOffset);
    }

    private static final class RecordingActions implements InputActions {
        int cursorOffset;
        @Override public void submit(String text) {}
        @Override public void cancel() {}
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() {}
        @Override public void undo() {}
        @Override public void permissionModeChanged(String uiMode) {}
        @Override public void toggleMessageActions() {}
        @Override public void messageActionsPrev() {}
        @Override public void messageActionsNext() {}
        @Override public void messageActionsCopy() {}
        @Override public void messageActionsEdit() {}
        @Override public void queryChanged(String text, int cursor) { cursorOffset = cursor; }
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
