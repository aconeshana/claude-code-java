package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InputPanelDraftUndoTest {

    private static final KeyStroke CTRL_UNDERSCORE = new KeyStroke('_', true, false);

    @Test
    void ctrlUnderscoreUndoesDraftEditsWithoutRewindingConversation() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.handleKeyForTest(new KeyStroke('a', false, false));
        panel.handleKeyForTest(new KeyStroke('b', false, false));
        panel.handleKeyForTest(CTRL_UNDERSCORE);

        assertEquals("a", panel.getText());
        assertEquals(0, actions.conversationUndoCalls.get());

        panel.handleKeyForTest(CTRL_UNDERSCORE);
        assertEquals("", panel.getText());
        assertEquals(0, actions.conversationUndoCalls.get());
    }

    @Test
    void undoRestoresAnAtomicallyDeletedImageChipAndItsMetadata() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        panel.setText("[Image #1]");
        panel.restoreImageChips(Map.of(
            1, PastedContent.image(1, "base64", "image/png", null, null)));
        panel.handleKeyForTest(new KeyStroke(KeyType.END));

        panel.handleKeyForTest(new KeyStroke(KeyType.BACKSPACE));
        assertEquals("", panel.getText());
        assertEquals(Map.of(), panel.getPastedContents());

        panel.handleKeyForTest(CTRL_UNDERSCORE);

        assertEquals("[Image #1]", panel.getText());
        assertEquals("base64", panel.getPastedContents().get(1).content());
    }

    private static final class RecordingActions implements InputActions {
        final AtomicInteger conversationUndoCalls = new AtomicInteger();
        @Override public void submit(String text) {}
        @Override public void cancel() {}
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() {}
        @Override public void undo() { conversationUndoCalls.incrementAndGet(); }
        @Override public void permissionModeChanged(String uiMode) {}
        @Override public void toggleMessageActions() {}
        @Override public void messageActionsPrev() {}
        @Override public void messageActionsNext() {}
        @Override public void messageActionsCopy() {}
        @Override public void messageActionsEdit() {}
        @Override public void queryChanged(String text, int cursor) {}
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
