package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.claudecode.core.message.PastedContent;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Repro: typing a space after a slash command name must insert, not be swallowed. */
class ReproGoalSpaceTest {

    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);

    private static InputPanel panel() {
        InputPanel panel = new InputPanel();
        panel.setActions(NOOP_ACTIONS);
        return panel;
    }

    /** Minimal InputActions stub with empty implementations of the abstract members. */
    private static final InputActions NOOP_ACTIONS = new InputActions() {
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
    };

    @Test
    void spaceInsertsAfterTypedCommandNameWhenSuggestionsVisible() {
        InputPanel panel = panel();
        panel.setText("/goal");
        panel.showSuggestions(
            List.of(new SuggestionPanel.Suggestion("/goal", "Set a goal")), 100);

        TextBox.Result result = panel.handleKeyForTest(SPACE);

        assertEquals("/goal ", panel.getText(),
            "Space after an exact slash command name must insert the argument separator");
        assertNotEquals(TextBox.Result.UNHANDLED, result);
    }

    @Test
    void spaceInsertsWhilePartialCommandName() {
        InputPanel panel = panel();
        panel.setText("/go");
        panel.showSuggestions(
            List.of(new SuggestionPanel.Suggestion("/goal", "Set a goal")), 100);

        panel.handleKeyForTest(SPACE);

        assertEquals("/go ", panel.getText());
    }

    @Test
    void spaceInsertsWhenNoSuggestionsVisible() {
        InputPanel panel = panel();
        panel.setText("/goal");

        panel.handleKeyForTest(SPACE);

        assertEquals("/goal ", panel.getText());
    }
}