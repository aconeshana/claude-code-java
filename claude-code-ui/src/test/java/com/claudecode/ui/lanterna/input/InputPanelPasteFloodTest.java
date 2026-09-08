package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unbracketed-paste flood coverage: a paste that arrives WITHOUT the
 * {@code \e[200~…\e[201~} wrapper (tmux {@code paste-buffer}, which rewrites
 * {@code \n} to {@code \r}; CRLF clipboards) decodes every newline as a plain
 * ENTER. Human keystrokes arrive one PTY drain at a time, so an ENTER sharing
 * one GUI input batch with pasted text is a paste newline — it must split the
 * line instead of submitting, and the batch end folds the accumulated text
 * into a chip, matching the bracketed-paste end state.
 */
class InputPanelPasteFloodTest {

    @Test
    void crFloodSplitsLinesInsteadOfSubmitting_andFoldsIntoChipAtBatchEnd() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        List<String> lines = List.of(
            "teamai import --from-repo https://gitlab.ximalaya.com/infra/deepgate",
            "✔ Import remote repository [9s]",
            "  › Repository: https://gitlab.ximalaya.com/infra/deepgate",
            "ℹ Importing remote repo: infra/deepgate (provider: git)",
            "ℹ Shallow clone to cache: /Users/xmly/.teamai/cache/repos/git/infra/deepgate",
            "ℹ Clone/Fetch complete: SHA=94697e38, branch=master",
            "ℹ Scanning repository...",
            "⚠ AI codebase scan failed (non-blocking): AI call failed:");

        panel.beginGuiInputBatch();
        for (int i = 0; i < lines.size(); i++) {
            type(panel, lines.get(i));
            if (i < lines.size() - 1) panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        }
        panel.endGuiInputBatch();

        assertEquals(List.of(), actions.submissions,
            "an unbracketed CR paste flood must not submit any line");
        assertTrue(panel.getText().startsWith("[Pasted text #"),
            "the flood folds into a chip at batch end, like bracketed paste — was: " + panel.getText());
        PastedContent folded = panel.getPastedContents().values().iterator().next();
        String expected = String.join("\n", lines);
        assertEquals(expected, folded.content(),
            "the chip carries the full pasted text with newlines preserved");
    }

    @Test
    void smallFloodBelowTheChipThresholdStaysAMultilineDraft() {
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.beginGuiInputBatch();
        type(panel, "alpha");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        type(panel, "beta");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        type(panel, "gamma");
        panel.endGuiInputBatch();

        assertEquals(List.of(), actions.submissions);
        assertEquals("alpha\nbeta\ngamma", panel.getText(),
            "flood ENTERs split lines; a small paste stays an editable draft");
        assertTrue(panel.getPastedContents().isEmpty(), "below the threshold no chip is created");
    }

    @Test
    void enterSharingOneDrainWithTypedTextDoesNotSubmit_butTheNextEnterDoes() {
        // The deliberate trade-off of batch-granularity detection: a human
        // Enter landing in the same PTY drain as typed text is treated as a
        // newline. A second Enter in its own drain submits normally.
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.beginGuiInputBatch();
        type(panel, "hi");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.endGuiInputBatch();

        assertEquals(List.of(), actions.submissions,
            "an ENTER sharing the drain with text is a newline, not a submit");
        assertEquals("hi\n", panel.getText());

        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));

        assertEquals(List.of("hi"), actions.submissions,
            "a lone ENTER outside any text batch still submits");
    }

    @Test
    void enterAsTheFirstKeyOfABatchKeepsItsNormalMeaning() {
        // No text preceded it in this drain, so the ENTER is a real submit —
        // the flood guard only downgrades ENTERs that follow pasted text.
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);
        panel.setText("typed earlier");

        panel.beginGuiInputBatch();
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.endGuiInputBatch();

        assertEquals(List.of("typed earlier"), actions.submissions);
    }

    private static void type(InputPanel panel, String text) {
        for (char ch : text.toCharArray()) {
            panel.handleKeyForTest(new KeyStroke(ch, false, false));
        }
    }

    private static final class RecordingActions implements InputActions {
        final List<String> submissions = new ArrayList<>();

        @Override public void submit(String text) { submissions.add(text); }
        @Override public void cancel() {}
        @Override public void exitOnEmptyEof() {}
        @Override public boolean backgroundForegroundTasks() { return false; }
        @Override public void showMessageSelector() {}
        @Override public void toggleFastMode() {}
        @Override public void openAgents() {}
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
        @Override public void cursorStyleChanged(com.googlecode.lanterna.CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
