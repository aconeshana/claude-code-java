package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputPanelStashTest {

    private static final KeyStroke CTRL_S = new KeyStroke('s', true, false);

    @Test
    void ctrlS_pushesAndPopsPromptWithoutTouchingConversation() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("draft");
        panel.handleKeyForTest(new KeyStroke(KeyType.END));
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT));
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT));

        panel.handleKeyForTest(CTRL_S);

        assertEquals("", panel.getText());
        assertEquals(1, actions.stashUsageCalls.get());

        panel.handleKeyForTest(CTRL_S);
        panel.handleKeyForTest(new KeyStroke('X', false, false));

        assertEquals("draXft", panel.getText(), "cursor position must be restored with the text");
        assertEquals(1, actions.stashUsageCalls.get(), "popping does not record a second use");
    }

    @Test
    void ctrlS_preservesPastedContentMetadata() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        panel.setText("[Image #1]");
        panel.restoreImageChips(Map.of(1, PastedContent.image(1, "base64", "image/png", null, null)));

        panel.handleKeyForTest(CTRL_S);
        assertTrue(panel.getPastedContents().isEmpty());

        panel.handleKeyForTest(CTRL_S);
        assertEquals("base64", panel.getPastedContents().get(1).content());
    }

    @Test
    void replacingImageChipsWithAnEmptyMapClearsTheCurrentImages() {
        InputPanel panel = new InputPanel();
        panel.restoreImageChips(Map.of(
            1, PastedContent.image(1, "base64", "image/png", null, null)));

        panel.replaceImageChips(Map.of());

        assertTrue(panel.getPastedContents().isEmpty());
    }

    private static final class RecordingActions implements InputActions {
        final AtomicInteger stashUsageCalls = new AtomicInteger();
        @Override public void submit(String text) {}
        @Override public void cancel() {}
        @Override public void showMessageSelector() {}
        @Override public void toggleTranscript() {}
        @Override public void transcriptShowAll() {}
        @Override public void redrawScreen() {}
        @Override public void externalEditor() {}
        @Override public void stash() { stashUsageCalls.incrementAndGet(); }
        @Override public void undo() {}
        @Override public void permissionModeChanged(String uiMode) {}
        @Override public void openTasksDialog() {}
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
