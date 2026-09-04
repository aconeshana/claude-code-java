package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputPanelQueuedEditingTest {

    @Test
    void upOnFirstLineRestoresEditableQueueBeforeHistory() {
        RecordingActions actions = new RecordingActions();
        PastedContent image = PastedContent.image(4, "base64", "image/png", null, null);
        actions.toRestore = new QueuedInputDraft("queued [Image #4]\ndraft", 20, Map.of(4, image));
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("draft");
        panel.setCaretOffsetForTest(2);

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_UP));

        assertEquals(1, actions.popCalls);
        assertEquals("queued [Image #4]\ndraft", panel.getText());
        assertEquals(20, panel.caretCol());
        assertEquals(image, panel.getPastedContents().get(4));
    }

    @Test
    void escapeRestoresEditableQueueBeforeDoubleEscapeHandling() {
        RecordingActions actions = new RecordingActions();
        actions.toRestore = new QueuedInputDraft("queued", 6, Map.of());
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.handleKeyForTest(new KeyStroke(KeyType.ESCAPE));

        assertEquals(1, actions.popCalls);
        assertEquals("queued", panel.getText());
        assertEquals(6, panel.caretCol());
    }

    @Test
    void upInsideMultilineDraftMovesCaretWithoutPoppingQueue() {
        RecordingActions actions = new RecordingActions();
        actions.toRestore = new QueuedInputDraft("queued", 6, Map.of());
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("abc\ndef");
        panel.setCaretOffsetForTest(6);

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_UP));

        assertEquals(0, actions.popCalls);
        assertEquals("abc\ndef", panel.getText());
        assertEquals(2, panel.caretCol());
    }

    @Test
    void queuedPreviewIsReactiveAndHidesNonEditableNotifications() {
        InputPanel panel = new InputPanel();
        panel.setQueuedCommands(List.of(
            QueuedCommand.prompt("editable prompt"),
            QueuedCommand.notification("hidden notification")));

        assertEquals("  ❯ editable prompt", panel.queuedPreviewTextForTest());

        panel.setQueuedCommands(List.of());
        assertEquals("", panel.queuedPreviewTextForTest());
    }

    private static final class RecordingActions implements InputActions {
        QueuedInputDraft toRestore;
        int popCalls;
        @Override public QueuedInputDraft popEditableQueuedCommands(String input, int cursor) {
            popCalls++;
            QueuedInputDraft result = toRestore;
            toRestore = null;
            return result;
        }
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
        @Override public void queryChanged(String text, int cursor) {}
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
