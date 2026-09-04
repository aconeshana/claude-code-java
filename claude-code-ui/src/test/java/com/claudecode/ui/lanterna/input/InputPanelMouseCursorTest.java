package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputPanelMouseCursorTest {

    private static final TerminalPosition ORIGIN = new TerminalPosition(3, 4);
    private static final TerminalSize SIZE = new TerminalSize(20, 4);

    @Test
    void bareClickMovesCaretAndPublishesAbsoluteOffset() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel("alpha beta", actions);

        assertTrue(panel.handlePromptBareClickForTest(8, 4, ORIGIN, SIZE));

        assertEquals(5, panel.caretCol());
        assertEquals(5, actions.cursorOffset);
    }

    @Test
    void clickPastVisibleTextClampsToLineEndAndOutsideClickIsIgnored() {
        InputPanel panel = panel("draft", new RecordingActions());

        assertTrue(panel.handlePromptBareClickForTest(18, 4, ORIGIN, SIZE));
        assertEquals(5, panel.caretCol());

        panel.setCaretOffsetForTest(2);
        assertFalse(panel.handlePromptBareClickForTest(2, 4, ORIGIN, SIZE));
        assertEquals(2, panel.caretCol());
    }

    @Test
    void softWrappedAndWideCharacterClicksUseVisualCoordinates() {
        InputPanel wrapped = new InputPanel();
        wrapped.setSize(new TerminalSize(10, 20));
        wrapped.setActions(new RecordingActions());
        wrapped.setText("alpha beta");

        assertTrue(wrapped.handlePromptBareClickForTest(
            3, 5, ORIGIN, new TerminalSize(7, 2)));
        assertEquals(6, wrapped.caretCol());

        InputPanel wide = new InputPanel();
        wide.setSize(new TerminalSize(8, 20));
        wide.setActions(new RecordingActions());
        wide.setText("中文A");
        assertTrue(wide.handlePromptBareClickForTest(
            5, 4, ORIGIN, new TerminalSize(5, 2)));
        assertEquals(1, wide.caretCol());
    }

    @Test
    void historySearchConsumesClickWithoutMovingCaret() {
        InputPanel panel = panel("draft", new RecordingActions());
        panel.setCaretOffsetForTest(2);
        panel.handleKeyForTest(new KeyStroke('r', true, false));

        assertTrue(panel.handlePromptBareClickForTest(7, 4, ORIGIN, SIZE));
        assertEquals(2, panel.caretCol());
    }

    @Test
    void clickClearsFooterSelectionBeforeRestoringPromptInput() {
        InputPanel panel = panel("draft", new RecordingActions());
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN)); // ≡ projects button (extension stop)
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_DOWN)); // → Collaboration
        assertTrue(panel.isCollaborationPillSelected());

        panel.handlePromptBareClickForTest(6, 4, ORIGIN, SIZE);

        assertFalse(panel.isCollaborationPillSelected());
        assertEquals(3, panel.caretCol());
    }

    @Test
    void vimInsertUsesTheCaretSetByThePreviousClick() {
        InputPanel panel = panel("abcde", new RecordingActions());
        panel.setVimEnabled(true);

        panel.handlePromptBareClickForTest(5, 4, ORIGIN, SIZE);
        panel.handleKeyForTest(new KeyStroke('X', false, false));

        assertEquals("abXcde", panel.getText());
        assertEquals(3, panel.caretCol());
    }

    private static InputPanel panel(String text, RecordingActions actions) {
        InputPanel panel = new InputPanel();
        panel.setSize(new TerminalSize(23, 20));
        panel.setActions(actions);
        panel.setText(text);
        return panel;
    }

    private static final class RecordingActions implements InputActions {
        int cursorOffset = -1;
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
