package com.claudecode.ui.lanterna.dialog;

import com.claudecode.tools.questions.QuestionPresenter;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AskUserQuestionDialog} interaction state machine, driven through
 * {@code handleKey} on a SameTextGUIThread virtual terminal: single-select via
 * Enter, multi-select space toggling, "Other" free text, notes on a preset
 * selection, preview propagation, multi-question flow, and Esc cancel.
 */
class AskUserQuestionDialogTest {

    @Test
    void selectionGlyphsMatchClaudeCode197() {
        assertEquals("[ ]", AskUserQuestionDialog.multiSelectMarker(false));
        assertEquals("[✓]", AskUserQuestionDialog.multiSelectMarker(true));
        assertEquals("1. ", AskUserQuestionDialog.optionIndex(0, 4));
        assertEquals("10. ", AskUserQuestionDialog.optionIndex(9, 12));
        assertEquals(" 1. ", AskUserQuestionDialog.optionIndex(0, 12));
    }

    private static QuestionPresenter.Question q(String text, boolean multi,
                                                QuestionPresenter.Option... opts) {
        return new QuestionPresenter.Question(text, "Hdr", List.of(opts), multi);
    }

    private static QuestionPresenter.Option opt(String label, String preview) {
        return new QuestionPresenter.Option(label, "desc of " + label, preview);
    }

    /** Drives showAndWait on a worker thread; keys are fed through handleKey. */
    private static final class Harness {
        final AskUserQuestionDialog dialog = new AskUserQuestionDialog();
        final MultiWindowTextGUI gui;
        final CompletableFuture<Map<String, QuestionPresenter.Answer>> result =
            new CompletableFuture<>();

        Harness(List<QuestionPresenter.Question> questions) throws Exception {
            var term = new DefaultVirtualTerminal(new TerminalSize(100, 40));
            var screen = new TerminalScreen(term);
            screen.startScreen();
            gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            Thread.ofVirtual().start(() ->
                result.complete(dialog.showAndWait(gui, questions, () -> {})));
            // SameTextGUIThread: invokeLater runs when the GUI thread processes —
            // pump until the dialog activates.
            long deadline = System.currentTimeMillis() + 2000;
            while (!dialog.isActive() && System.currentTimeMillis() < deadline) {
                gui.getGUIThread().processEventsAndUpdate();
                Thread.sleep(5);
            }
            assertTrue(dialog.isActive(), "dialog must activate");
        }

        void key(KeyStroke k) {
            dialog.handleKey(k, new AtomicBoolean(true));
        }

        void type(String s) {
            for (char c : s.toCharArray()) key(new KeyStroke(c, false, false));
        }

        Map<String, QuestionPresenter.Answer> await() throws Exception {
            return result.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void singleSelectEnterPicksFocusedOption() throws Exception {
        Harness h = new Harness(List.of(
            q("Pick one?", false, opt("Alpha", null), opt("Beta", "BETA-PREVIEW"))));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus Beta
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Beta", answers.get("Pick one?").answer());
        assertEquals("BETA-PREVIEW", answers.get("Pick one?").preview());
        assertNull(answers.get("Pick one?").notes());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void multiSelectSpaceTogglesAndEnterSubmits() throws Exception {
        Harness h = new Harness(List.of(
            q("Pick many?", true, opt("A", null), opt("B", null), opt("C", null))));
        h.key(new KeyStroke(' ', false, false));    // toggle A
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // focus C
        h.key(new KeyStroke(' ', false, false));    // toggle C
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("A, C", answers.get("Pick many?").answer());
    }

    @Test
    void otherFreeTextBecomesAnswer() throws Exception {
        Harness h = new Harness(List.of(
            q("Which?", false, opt("X", null), opt("Y", null))));
        h.key(new KeyStroke(KeyType.ARROW_UP));     // wrap to Other (index 2)
        h.type("custom answer");
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("custom answer", answers.get("Which?").answer());
    }

    @Test
    void typedTextOnPresetSelectionBecomesNotes() throws Exception {
        Harness h = new Harness(List.of(
            q("Choose?", false, opt("Preset", null), opt("Alt", null))));
        h.type("some context");                     // focus on Preset, text = notes
        h.key(new KeyStroke(KeyType.ENTER));
        var answers = h.await();
        assertEquals("Preset", answers.get("Choose?").answer());
        assertEquals("some context", answers.get("Choose?").notes());
    }

    @Test
    void multiQuestionFlowCollectsAll() throws Exception {
        Harness h = new Harness(List.of(
            q("First?", false, opt("F1", null), opt("F2", null)),
            q("Second?", false, opt("S1", null), opt("S2", null))));
        h.key(new KeyStroke(KeyType.ENTER));        // F1 → advances
        assertTrue(h.dialog.isActive(), "still active on question 2");
        h.key(new KeyStroke(KeyType.ARROW_DOWN));
        h.key(new KeyStroke(KeyType.ENTER));        // S2 → submits
        var answers = h.await();
        assertEquals("F1", answers.get("First?").answer());
        assertEquals("S2", answers.get("Second?").answer());
    }

    @Test
    void escapeCancelsWithNull() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ESCAPE));
        assertNull(h.await());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void remoteResolutionCancelsAndUnblocksTheLocalDialog() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", false, opt("A", null), opt("B", null))));

        h.gui.getGUIThread().invokeLater(h.dialog::cancelPending);
        long deadline = System.currentTimeMillis() + 2000;
        while (h.dialog.isActive() && System.currentTimeMillis() < deadline) {
            h.gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertNull(h.await());
        assertFalse(h.dialog.isActive());
    }

    @Test
    void remoteResolutionBeforeMountSkipsTheQuestionDialog() throws Exception {
        var term = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(term);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        var dialog = new AskUserQuestionDialog();
        var cancelled = new AtomicBoolean(true);
        var result = new CompletableFuture<Map<String, QuestionPresenter.Answer>>();

        Thread.ofVirtual().start(() -> result.complete(dialog.showAndWait(gui,
            List.of(q("Q?", false, opt("A", null), opt("B", null))), () -> {},
            cancelled::get)));

        long deadline = System.currentTimeMillis() + 2000;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertNull(result.get(2, TimeUnit.SECONDS));
        assertFalse(dialog.isActive());
    }

    @Test
    void enterWithoutChoiceIsIgnored() throws Exception {
        Harness h = new Harness(List.of(
            q("Q?", true, opt("A", null), opt("B", null))));
        h.key(new KeyStroke(KeyType.ENTER));        // multi-select, nothing toggled
        assertTrue(h.dialog.isActive(), "empty multi-select submit must be ignored");
        h.key(new KeyStroke(' ', false, false));
        h.key(new KeyStroke(KeyType.ENTER));
        assertEquals("A", h.await().get("Q?").answer());
    }
}
