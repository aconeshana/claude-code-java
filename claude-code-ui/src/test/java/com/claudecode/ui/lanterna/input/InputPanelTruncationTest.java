package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.InputPasteTruncation;
import com.googlecode.lanterna.CursorStyle;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;


class InputPanelTruncationTest {

    @Test
    void setText_truncatesAndRegistersChip() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        String text = "a".repeat(InputPasteTruncation.TRUNCATION_THRESHOLD + 100);
        panel.setText(text);

        String display = panel.getText();
        assertTrue(Strings.CS.contains(display, "[...Truncated text #1 "),
            "oversized setText is shortened to a truncated chip; got: " + display);

        Map<Integer, PastedContent> pasted = panel.getPastedContents();
        assertEquals(1, pasted.size(), "fresh panel assigns id #1");
        assertTrue(pasted.containsKey(1));
        assertEquals("text", pasted.get(1).type());
        assertEquals(text.substring(500, text.length() - 500), pasted.get(1).content(),
            "the lifted middle is stashed under #1");
    }

    @Test
    void setRestoredText_stripsBashPrefixThenTruncates() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        // "!" + a 10001-char body => after stripping the prefix the body is > threshold.
        String body = "b".repeat(InputPasteTruncation.TRUNCATION_THRESHOLD + 1);
        panel.setRestoredText("!" + body);

        String display = panel.getText();
        assertFalse(Strings.CS.startsWith(display, "!"), "bash '!' prefix must be stripped before truncation");
        assertTrue(Strings.CS.contains(display, "[...Truncated text #1 "),
            "restored bash body is truncated; got: " + display);

        Map<Integer, PastedContent> pasted = panel.getPastedContents();
        assertEquals(1, pasted.size());
        assertEquals(body.substring(500, body.length() - 500), pasted.get(1).content());
    }

    @Test
    void applyHistoryPickerEntry_restoresOwnChipsThenTruncatesWithNewId() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());

        String body = "c".repeat(InputPasteTruncation.TRUNCATION_THRESHOLD + 50);
        PastedContent ownChip = PastedContent.text(5, "history-owned");
        PromptHistory.Entry entry = new PromptHistory.Entry(
            body, "sid", 0L, "project", "/cwd", Map.of(5, ownChip));

        panel.applyHistoryPickerEntry(entry);

        String display = panel.getText();
        assertTrue(Strings.CS.contains(display, "[...Truncated text #6 "),
            "new truncation chip uses max(existingIds)+1 = 6; got: " + display);

        Map<Integer, PastedContent> pasted = panel.getPastedContents();
        assertEquals(2, pasted.size(), "entry's own chip + the new truncation chip");
        assertEquals("history-owned", pasted.get(5).content(), "entry's own chip survives restore");
        assertEquals(body.substring(500, body.length() - 500), pasted.get(6).content(),
            "new chip holds the lifted middle under the bumped id");
    }

    @Test
    void setText_smallInput_notTruncated() {
        InputPanel panel = new InputPanel();
        panel.setActions(new RecordingActions());
        String text = "short prompt";
        panel.setText(text);
        assertEquals(text, panel.getText());
        assertTrue(panel.getPastedContents().isEmpty());
    }

    private static final class RecordingActions implements InputActions {
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
