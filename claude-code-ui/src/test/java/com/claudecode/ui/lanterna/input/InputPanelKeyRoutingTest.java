package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.FocusEventKeyStroke;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;

/**
 * Characterisation tests for InputPanel's ordered prompt-key protocol.
 */
class InputPanelKeyRoutingTest {

    private static final KeyStroke ESCAPE = new KeyStroke(KeyType.ESCAPE);

    @Test
    void focusEventsAreConsumedAndReportedWithoutEditingThePrompt() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("draft");

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new FocusEventKeyStroke(true)));
        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new FocusEventKeyStroke(false)));

        assertEquals(List.of(true, false), actions.focusEvents);
        assertEquals("draft", panel.getText());
    }

    @Test
    void mouseEventsBubbleToTheWindowInsteadOfEditingThePrompt() {
        InputPanel panel = panel(new RecordingActions());
        panel.setText("draft");

        TextBox.Result result = panel.handleKeyForTest(new MouseAction(
            MouseActionType.SCROLL_UP, 1, new TerminalPosition(0, 0)));

        assertEquals(TextBox.Result.UNHANDLED, result);
        assertEquals("draft", panel.getText());
    }

    @Test
    void autocompleteEscapeDismissesBeforeLoadingEscapeCancelsTheRequest() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.showSuggestions(List.of(new SuggestionPanel.Suggestion("/compact", "Compact")), 100);
        panel.setIsLoading(true);

        assertEquals(TextBox.Result.HANDLED, panel.handleKeyForTest(ESCAPE));
        assertEquals(0, actions.cancelCalls.get(), "first Escape closes autocomplete");

        assertEquals(TextBox.Result.HANDLED, panel.handleKeyForTest(ESCAPE));
        assertEquals(1, actions.cancelCalls.get(), "next Escape reaches loading cancellation");
    }

    @Test
    void enterSubmitsPromptAndClearsItsEditableState() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("review this change");

        assertEquals(TextBox.Result.HANDLED, panel.handleKeyForTest(new KeyStroke(KeyType.ENTER)));

        assertEquals(List.of("review this change"), actions.submissions);
        assertTrue(panel.getText().isEmpty());
    }

    @Test
    void enterWaitsForPendingPasteThenSubmitsInsertedImage() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("describe");
        panel.beginPasteForTest();

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.ENTER)));
        assertTrue(actions.submissions.isEmpty());
        assertTrue(panel.isPastingForTest());

        panel.completePasteForTest("[Image #1]");

        assertEquals(List.of("describe[Image #1]"), actions.submissions);
        assertFalse(panel.isPastingForTest());
    }

    @Test
    void editingAfterDeferredEnterCancelsAutomaticSubmission() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("draft");
        panel.beginPasteForTest();

        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.handleKeyForTest(new KeyStroke('x', false, false));
        panel.completePasteForTest("[Image #1]");

        assertTrue(actions.submissions.isEmpty());
        assertEquals("draftx[Image #1]", panel.getText());
    }

    @Test
    void failedPendingPasteStillReplaysDeferredSubmission() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("keep going");
        panel.beginPasteForTest();

        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.completePasteForTest(null);

        assertEquals(List.of("keep going"), actions.submissions);
        assertFalse(panel.isPastingForTest());
    }

    @Test
    void enterOnCommandSuggestionAcceptsAndExecutesInOneKeystroke() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("/compact");
        panel.showSuggestions(
            List.of(new SuggestionPanel.Suggestion("/compact", "Compact")), 100);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.ENTER)));

        assertEquals(List.of("/compact"), actions.submissions);
        assertTrue(panel.getText().isEmpty());
    }

    @Test
    void tabOnCommandSuggestionOnlyCompletesWithoutExecuting() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("/comp");
        panel.showSuggestions(
            List.of(new SuggestionPanel.Suggestion("/compact", "Compact")), 100);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.TAB)));

        assertEquals("/compact ", panel.getText());
        assertTrue(actions.submissions.isEmpty());
    }

    @Test
    void tabOnBashPathSuggestionReplacesOnlyTheCurrentToken() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setRestoredText("!imgcat ~/Down");
        panel.setCaretOffsetForTest(panel.getText().length());
        panel.showBashPathSuggestions(
            List.of(new SuggestionPanel.Suggestion("~/Downloads/", "dir")), 100);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.TAB)));

        assertEquals("imgcat ~/Downloads/", panel.getText());
        assertTrue(actions.submissions.isEmpty());
    }

    @Test
    void enterWithBashPathSuggestionsSubmitsTheUnchangedCommand() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setRestoredText("!imgcat ~/Down");
        panel.setCaretOffsetForTest(panel.getText().length());
        panel.showBashPathSuggestions(
            List.of(new SuggestionPanel.Suggestion("~/Downloads/", "dir")), 100);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.ENTER)));

        assertEquals(List.of("!imgcat ~/Down"), actions.submissions);
        assertTrue(panel.getText().isEmpty());
    }

    @Test
    void tabOnBashFileSuggestionAddsAnArgumentSeparator() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setRestoredText("!imgcat ~/image.pn");
        panel.setCaretOffsetForTest(panel.getText().length());
        panel.showBashPathSuggestions(
            List.of(new SuggestionPanel.Suggestion("~/image.png", "")), 100);

        panel.handleKeyForTest(new KeyStroke(KeyType.TAB));

        assertEquals("imgcat ~/image.png ", panel.getText());
    }

    @Test
    void exactCommandArgumentHintRendersInlineAfterTheCaret() {
        InputPanel panel = panel(new RecordingActions());
        panel.setText("/model ");
        panel.setArgumentHint("[model]");

        assertEquals("[model]", panel.inlineGhostTextForTest());

        panel.setText("/model");
        assertEquals(" [model]", panel.inlineGhostTextForTest());

        panel.setArgumentHint(null);
        assertNull(panel.inlineGhostTextForTest());
    }

    @Test
    void released197SlashSuggestionsKeepTheTypedCommandUnchanged() {
        InputPanel panel = panel(new RecordingActions());
        panel.setText("/m");
        panel.showSuggestions(
            List.of(new SuggestionPanel.Suggestion("/mcp", "Manage MCP servers")), 100);

        assertNull(panel.inlineGhostTextForTest(),
            "2.1.197 shows completion candidates only in the dropdown");
        assertEquals("/m", panel.getText());
    }

    @Test
    void loadingEscapeCancelsBeforeIdleDoubleEscapeCanClearDraftText() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("keep this draft");
        panel.setIsLoading(true);

        assertEquals(TextBox.Result.HANDLED, panel.handleKeyForTest(ESCAPE));

        assertEquals(1, actions.cancelCalls.get());
        assertEquals("keep this draft", panel.getText());
        assertEquals(0, actions.messageSelectorCalls.get());
    }

    @Test
    void emptyCtrlDRequestsExitWithoutCancellingAnActiveQuery() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setIsLoading(true);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke('d', true, false)));

        assertEquals(0, actions.cancelCalls.get());
        assertEquals(1, actions.exitCalls.get());
    }

    @Test
    void idleDoubleEscapeClearsNonemptyPromptWithoutSubmittingIt() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("discard this draft");

        panel.handleKeyForTest(ESCAPE);
        assertEquals("discard this draft", panel.getText(), "first Escape only arms the clear gate");
        panel.handleKeyForTest(ESCAPE);

        assertTrue(panel.getText().isEmpty());
        assertTrue(actions.submissions.isEmpty());
        assertEquals(0, actions.messageSelectorCalls.get());
    }

    @Test
    void idleDoubleEscapeOnEmptyPromptOpensTheMessageSelectorWhenMessagesExist() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setHasMessages(() -> true);

        panel.handleKeyForTest(ESCAPE);
        assertEquals(0, actions.messageSelectorCalls.get(), "first Escape only arms the selector gate");
        panel.handleKeyForTest(ESCAPE);

        assertEquals(1, actions.messageSelectorCalls.get());
    }

    @Test
    void ctrlBBackgroundsForegroundTasksBeforeFallingBackToCursorLeft() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setText("abc");
        panel.setCaretOffsetForTest(2);
        actions.backgroundForegroundTasks = true;

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke('b', true, false)));

        assertEquals(1, actions.backgroundCalls.get());
        assertEquals(2, panel.caretOffsetForTest(),
            "task:background must take priority over readline cursor-left");

        actions.backgroundForegroundTasks = false;
        panel.handleKeyForTest(new KeyStroke('b', true, false));
        assertEquals(1, panel.caretOffsetForTest(),
            "without a foreground task Ctrl+B retains readline behavior");
    }

    @Test
    void metaOTogglesFastModeThroughTheInputActionsPort() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke('o', false, true)));

        assertEquals(1, actions.fastModeToggleCalls.get());
    }

    @Test
    void plainLeftArrowOnEmptyPromptOpensAgentsWhenEnabled() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setLeftArrowOpensAgentsForTest(() -> true);

        assertEquals(TextBox.Result.HANDLED,
            panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT)));

        assertEquals(1, actions.agentsCalls.get());
    }

    @Test
    void leftArrowKeepsEditingSemanticsWhenAgentsShortcutIsUnavailable() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = panel(actions);
        panel.setLeftArrowOpensAgentsForTest(() -> false);
        panel.setText("x");
        panel.setCaretOffsetForTest(1);

        panel.handleKeyForTest(new KeyStroke(KeyType.ARROW_LEFT));

        assertEquals(0, actions.agentsCalls.get());
        assertEquals(0, panel.caretOffsetForTest());
    }

    private static InputPanel panel(RecordingActions actions) {
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        return panel;
    }

    private static final class RecordingActions implements InputActions {
        final List<String> submissions = new ArrayList<>();
        final List<Boolean> focusEvents = new ArrayList<>();
        final AtomicInteger cancelCalls = new AtomicInteger();
        final AtomicInteger messageSelectorCalls = new AtomicInteger();
        final AtomicInteger backgroundCalls = new AtomicInteger();
        final AtomicInteger exitCalls = new AtomicInteger();
        final AtomicInteger fastModeToggleCalls = new AtomicInteger();
        final AtomicInteger agentsCalls = new AtomicInteger();
        boolean backgroundForegroundTasks;

        @Override public void submit(String text) { submissions.add(text); }
        @Override public void cancel() { cancelCalls.incrementAndGet(); }
        @Override public void exitOnEmptyEof() { exitCalls.incrementAndGet(); }
        @Override public boolean backgroundForegroundTasks() {
            backgroundCalls.incrementAndGet();
            return backgroundForegroundTasks;
        }
        @Override public void showMessageSelector() { messageSelectorCalls.incrementAndGet(); }
        @Override public void toggleFastMode() { fastModeToggleCalls.incrementAndGet(); }
        @Override public void openAgents() { agentsCalls.incrementAndGet(); }
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
        @Override public void focusChanged(boolean focused) { focusEvents.add(focused); }
    }
}
