package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.PastedContent;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/** Regression coverage for the ordinary single-line typing path measured by the PTY benchmark. */
class InputPanelHotPathTest {

    @Test
    void plainTypingKeepsSingleLineGeometryAndNormalModeStable() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        for (char ch : "ordinary prompt".toCharArray()) {
            panel.handleKeyForTest(new KeyStroke(ch, false, false));
        }

        assertEquals("ordinary prompt", panel.getText());
        assertEquals(1, panel.textRowsForTest());
        assertEquals(InputPanel.Mode.NORMAL, panel.modeForTest());
        assertEquals("ordinary prompt".length(), actions.queryChanges);
    }

    @Test
    void singleLineCaretOffsetTracksInsertionsWithoutScanningTextBoxRows() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        panel.setText("ac");
        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT));
        panel.handleKeyForTest(new KeyStroke('b', false, false));

        assertEquals("abc", panel.getText());
        assertEquals(2, panel.caretOffsetForTest());
    }

    @Test
    void onlyUnmodifiedOrdinaryCharactersUseTheDirectEditorRoute() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        assertTrue(panel.plainCharacterFastPathForTest(
            new KeyStroke('a', false, false)));
        assertFalse(panel.plainCharacterFastPathForTest(
            new KeyStroke('/', true, false)));
        assertFalse(panel.plainCharacterFastPathForTest(
            new KeyStroke(KeyType.BACKSPACE)));

        panel.setText("/");
        panel.showSuggestions(List.of(
            new SuggestionPanel.Suggestion(
                "/model", "Set the AI model")), 100);
        assertFalse(panel.plainCharacterFastPathForTest(
            new KeyStroke('m', false, false)), "visible slash autocomplete must keep ownership of the key protocol");
    }

    @Test
    void insertingAndRemovingNewlineStillResizesThePrompt() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        panel.setText("first\nsecond");

        assertEquals(2, panel.textRowsForTest());

        panel.setText("first second");

        assertEquals(1, panel.textRowsForTest());
    }

    @Test
    void guiBurstPublishesOnlyTheFinalQueryState() {
        RecordingActions actions = new RecordingActions();
        Queue<Runnable> guiTasks = new ArrayDeque<>();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setGuiInvoker(guiTasks::add);

        for (char ch : "thirty-two-character-input-burst!".toCharArray()) {
            panel.handleKeyForTest(new KeyStroke(ch, false, false));
        }

        assertEquals(0, actions.queryChanges,
            "query work should wait until the PTY input burst has drained");
        assertEquals(1, guiTasks.size(),
            "one GUI-cycle callback should represent the complete burst");

        guiTasks.remove().run();

        assertEquals(1, actions.queryChanges);
        assertEquals(panel.getText(), actions.lastQueryText);
        assertEquals(panel.getText().length(), actions.lastQueryCursor);
    }

    @Test
    void guiInputBatchMutatesTheEditorOnlyOnceForAPlainTextBurst() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.beginGuiInputBatch();
        for (char ch : "plain-input-burst".toCharArray()) {
            panel.handleKeyForTest(new KeyStroke(ch, false, false));
        }

        assertEquals("", panel.getText(),
            "PTY bytes from one read cycle should stay in the lightweight input buffer");
        assertEquals(0, actions.queryChanges);

        panel.endGuiInputBatch();

        assertEquals("plain-input-burst", panel.getText());
        assertEquals(1, actions.queryChanges,
            "typeahead should observe only the final text from the terminal burst");
    }

    @Test
    void guiInputBatchDefersNewSuggestionsUntilAfterItsEchoFrame() {
        RecordingActions actions = new RecordingActions();
        Queue<Runnable> guiTasks = new ArrayDeque<>();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setGuiInvoker(guiTasks::add);

        panel.beginGuiInputBatch();
        assertTrue(panel.handleGuiTextBatchForTest("/config"));
        panel.endGuiInputBatch();

        assertEquals(0, actions.queryChanges,
            "a new dropdown must not delay the first prompt echo frame");
        assertEquals(1, guiTasks.size());
        guiTasks.remove().run();
        assertEquals(1, actions.queryChanges);
    }

    @Test
    void guiInputBatchReplacesVisibleSuggestionsBeforeItsFirstFrame() {
        RecordingActions actions = new RecordingActions();
        Queue<Runnable> guiTasks = new ArrayDeque<>();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setGuiInvoker(guiTasks::add);
        panel.setText("/");
        panel.showSuggestions(List.of(
            new SuggestionPanel.Suggestion("/config", "Open config panel")), 100);
        actions.queryChanges = 0;
        guiTasks.clear();

        panel.beginGuiInputBatch();
        assertTrue(panel.handleGuiTextBatchForTest("config"));
        panel.endGuiInputBatch();

        assertEquals(1, actions.queryChanges,
            "a visible dropdown must use the final query in the first frame");
        assertTrue(guiTasks.isEmpty(),
            "replacing visible suggestions must not require a stale intermediate frame");
    }

    @Test
    void exclamationAfterBufferedTextDoesNotAccidentallyEnterBashMode() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        panel.beginGuiInputBatch();
        panel.handleKeyForTest(new KeyStroke('a', false, false));
        panel.handleKeyForTest(new KeyStroke('!', false, false));
        panel.endGuiInputBatch();

        assertEquals("a!", panel.getText());
        assertEquals(InputPanel.Mode.NORMAL, panel.modeForTest());
    }

    @Test
    void guiBackspaceBurstMutatesAndPublishesOnce() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("abcdefghijklmnopqrstuvwxyz012345");
        actions.queryChanges = 0;

        panel.beginGuiInputBatch();
        for (int i = 0; i < 32; i++) {
            panel.handleKeyForTest(new KeyStroke(KeyType.BACKSPACE));
        }

        assertEquals("abcdefghijklmnopqrstuvwxyz012345", panel.getText());
        assertEquals(0, actions.queryChanges);

        panel.endGuiInputBatch();

        assertEquals("", panel.getText());
        assertEquals(1, actions.queryChanges);
    }

    @Test
    void guiBackspaceBatchMutatesAndPublishesOnce() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("abcdefghijklmnopqrstuvwxyz012345");
        actions.queryChanges = 0;

        panel.beginGuiInputBatch();
        assertTrue(panel.handleGuiBackspaceBatchForTest(32));
        panel.endGuiInputBatch();

        assertEquals("", panel.getText());
        assertEquals(1, actions.queryChanges);
    }

    @Test
    void visibleSlashSuggestionsDoNotSplitPlainTextTerminalBurst() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("/");
        panel.showSuggestions(List.of(
            new SuggestionPanel.Suggestion("/config", "Open config panel"),
            new SuggestionPanel.Suggestion("/context", "Show context usage")), 100);
        actions.queryChanges = 0;

        panel.beginGuiInputBatch();
        assertTrue(panel.handleGuiTextBatchForTest("config"));

        assertEquals("/", panel.getText(),
            "autocomplete should not force one editor mutation per decoded character");
        assertEquals(0, actions.queryChanges);

        panel.endGuiInputBatch();

        assertEquals("/config", panel.getText());
        assertEquals(1, actions.queryChanges,
            "slash filtering should observe only the final terminal burst");
    }

    @Test
    void visibleSlashSuggestionsDoNotSplitBackspaceTerminalBurst() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("/config");
        panel.showSuggestions(List.of(
            new SuggestionPanel.Suggestion("/config", "Open config panel"),
            new SuggestionPanel.Suggestion("/update-config", "Update configuration")), 100);
        actions.queryChanges = 0;

        panel.beginGuiInputBatch();
        assertTrue(panel.handleGuiBackspaceBatchForTest(6),
            "ordinary Backspace remains an editor operation while autocomplete is visible");

        assertEquals("/config", panel.getText());
        assertEquals(0, actions.queryChanges);

        panel.endGuiInputBatch();

        assertEquals("/", panel.getText());
        assertEquals(1, actions.queryChanges,
            "slash expansion should rebuild suggestions once for the final query");
    }

    private static final class RecordingActions implements InputActions {
        private int queryChanges;
        private String lastQueryText;
        private int lastQueryCursor;
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
        @Override public void queryChanged(String text, int cursor) {
            queryChanges++;
            lastQueryText = text;
            lastQueryCursor = cursor;
        }
        @Override public void pastedContentsChanged(Map<Integer, PastedContent> contents) {}
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
