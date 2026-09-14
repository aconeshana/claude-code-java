package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.message.PastedContent;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Strings;
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
        assertTrue(Strings.CS.startsWith(panel.getText(), "[Pasted text #"),
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

        // "alpha\nbeta\ngamma" (2 synthetic newlines, 17 chars) sits under the
        // >6-lines-and->200-chars / >800-chars paste threshold, yet these
        // ENTERs still split rather than submit: the batch carried more than
        // the short one-line write of a plain submit, so it reads as a small
        // multiline paste that stays an editable draft.
        assertEquals(List.of(), actions.submissions);
        assertEquals("alpha\nbeta\ngamma", panel.getText(),
            "flood ENTERs split lines; a small paste stays an editable draft");
        assertTrue(panel.getPastedContents().isEmpty(), "below the threshold no chip is created");
    }

    @Test
    void enterSharingOneDrainWithTypedTextStillSubmits() {
        // Official 197 discriminates pastes by content volume, not by drain
        // batching: a one-line write plus its ENTER coalesced into one PTY
        // drain (fast typists, scripted drivers) is a submit. Only a
        // flood-sized batch downgrades its ENTERs to newlines.
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.beginGuiInputBatch();
        type(panel, "hi");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.endGuiInputBatch();

        assertEquals(List.of("hi"), actions.submissions,
            "a short write sharing the drain with its ENTER still submits");
        assertEquals("", panel.getText());
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

    @Test
    void batchedTextRunsMaterializeSwallowedEntersAsNewlines() {
        // The production PTY path feeds decoded text runs through
        // handleGuiTextBatch (not per-keystroke typing), so a flood delivered
        // as [run, ENTER, run, ENTER, ...] must still materialize each
        // swallowed ENTER as a newline — otherwise the batch would end as a
        // single-line concatenation and the paste fold would misjudge it.
        RecordingActions actions = new RecordingActions();
        InputPanel panel = new InputPanel();
        panel.setActions(actions);

        panel.beginGuiInputBatch();
        panel.handleGuiTextBatchForTest("alpha bravo charlie");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.handleGuiTextBatchForTest("delta echo foxtrot");
        panel.handleKeyForTest(new KeyStroke(KeyType.ENTER));
        panel.handleGuiTextBatchForTest("golf hotel india");
        panel.endGuiInputBatch();

        assertEquals(List.of(), actions.submissions);
        assertEquals("alpha bravo charlie\ndelta echo foxtrot\ngolf hotel india",
            panel.getText(),
            "each ENTER between batched text runs becomes a newline");
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
        @Override public void cursorStyleChanged(CursorStyle style) {}
        @Override public void focusChanged(boolean focused) {}
    }
}
