package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.dialog.question.QuestionOutcome;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The three ways an {@code AskUserQuestion} card resolves, driven end to end through the design
 * card: {@code zys}'s answer set, {@code k2g}'s clarification body, and {@code awo}'s bare denial.
 *
 * <p>The design-card geometry itself is covered by {@code DesignQuestionViewTest}; this suite is
 * about what leaves the dialog.
 */
class AskUserQuestionOutcomeTest {

    private static final int COLUMNS = 100;

    private static QuestionPresenter.Question design(String text, String header) {
        return new QuestionPresenter.Question(text, header, List.of(
            new QuestionPresenter.Option("Tabs", "keeps state", "# Tabs\n\nA preview."),
            new QuestionPresenter.Option("Drawer", "hides state", null)), false);
    }

    /** Mounts the dialog headlessly and feeds it keys, like {@code AskUserQuestionDialogTest}. */
    private static final class Harness {
        final AskUserQuestionDialog dialog = new AskUserQuestionDialog();
        final MultiWindowTextGUI gui;
        final CompletableFuture<QuestionOutcome> result = new CompletableFuture<>();

        Harness(List<QuestionPresenter.Question> questions) throws Exception {
            var screen = new TerminalScreen(
                new DefaultVirtualTerminal(new TerminalSize(COLUMNS, 40)));
            screen.startScreen();
            gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
            dialog.setTerminalColumnsSupplier(() -> COLUMNS);
            dialog.setTerminalRowsSupplier(() -> 40);
            dialog.setEditorNameSupplier(() -> null);
            Thread.ofVirtual().start(() ->
                result.complete(dialog.showAndWait(gui, questions, () -> {})));
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

        QuestionOutcome outcome() throws Exception {
            return result.get(2, TimeUnit.SECONDS);
        }
    }

    // ── submit ──────────────────────────────────────────────────────────────

    @Test
    void aLoneSingleSelectQuestionSubmitsTheMomentItIsAnswered() throws Exception {
        Harness h = new Harness(List.of(design("Which approach?", "Approach")));
        h.key(new KeyStroke(KeyType.ENTER));

        var submitted = assertInstanceOf(QuestionOutcome.Submitted.class, h.outcome());
        var answer = submitted.answers().get("Which approach?");
        assertEquals("Tabs", answer.answer());
        assertEquals("# Tabs\n\nA preview.", answer.preview());
        assertNull(answer.notes());
    }

    @Test
    void anOptionWithoutAFullPreviewCarriesNoPreviewAnnotation() throws Exception {
        Harness h = new Harness(List.of(design("Which approach?", "Approach")));
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Drawer has no preview of its own
        h.key(new KeyStroke(KeyType.ENTER));

        var submitted = assertInstanceOf(QuestionOutcome.Submitted.class, h.outcome());
        assertEquals("Drawer", submitted.answers().get("Which approach?").answer());
        assertNull(submitted.answers().get("Which approach?").preview());
    }

    @Test
    void aSecondQuestionRoutesThroughTheReviewScreenBeforeSubmitting() throws Exception {
        Harness h = new Harness(List.of(
            design("Which approach?", "Approach"), design("Which layout?", "Layout")));
        h.key(new KeyStroke(KeyType.ENTER));        // Tabs → question 2
        assertTrue(h.dialog.isActive(), "a two-question request must not auto-submit");
        h.key(new KeyStroke(KeyType.ENTER));        // Tabs → review screen
        assertTrue(h.dialog.isActive(), "the review screen must be reached first");
        h.key(new KeyStroke(KeyType.ENTER));        // Submit answers

        var submitted = assertInstanceOf(QuestionOutcome.Submitted.class, h.outcome());
        assertEquals(List.of("Which approach?", "Which layout?"),
            List.copyOf(submitted.answers().keySet()));
    }

    @Test
    void theReviewScreensCancelRowDeniesWithoutFeedback() throws Exception {
        Harness h = new Harness(List.of(
            design("Which approach?", "Approach"), design("Which layout?", "Layout")));
        h.key(new KeyStroke(KeyType.ENTER));
        h.key(new KeyStroke(KeyType.ENTER));        // review screen
        h.key(new KeyStroke(KeyType.ARROW_DOWN));   // Cancel
        h.key(new KeyStroke(KeyType.ENTER));

        assertInstanceOf(QuestionOutcome.Cancelled.class, h.outcome());
    }

    // ── chat about this ─────────────────────────────────────────────────────

    @Test
    void theChatRowProducesTheVerbatimClarificationBody() throws Exception {
        Harness h = new Harness(List.of(
            design("Which approach?", "Approach"), design("Which layout?", "Layout")));
        h.key(new KeyStroke(KeyType.ENTER));        // answer question 1, land on question 2
        h.type("n");                                // notes editor
        h.type("unsure");
        h.key(new KeyStroke(KeyType.ESCAPE));       // leave the editor, keep the buffer
        for (int step = 0; step < 3; step++) {
            h.key(new KeyStroke(KeyType.ARROW_DOWN));   // past the options onto the chat row
        }
        h.key(new KeyStroke(KeyType.ENTER));

        var clarify = assertInstanceOf(QuestionOutcome.Clarify.class, h.outcome());
        assertEquals("""
            The user wants to clarify these questions.
                This means they may have additional information, context or questions for you.
                Take their response into account and then reformulate the questions if appropriate.
                Start by asking them what they would like to clarify.
                Questions asked:
            - "Which approach?"
              Answer: Tabs
            - "Which layout?"
              (No answer provided)
              User notes: unsure""",
            clarify.feedback());
    }

    @Test
    void anUntouchedRequestClarifiesWithNoAnswersAndNoNotes() throws Exception {
        Harness h = new Harness(List.of(design("Which approach?", "Approach")));
        for (int step = 0; step < 2; step++) {
            h.key(new KeyStroke(KeyType.ARROW_DOWN));
        }
        h.key(new KeyStroke(KeyType.ENTER));

        var clarify = assertInstanceOf(QuestionOutcome.Clarify.class, h.outcome());
        assertTrue(clarify.feedback().endsWith("""
            - "Which approach?"
              (No answer provided)"""), clarify.feedback());
        assertFalse(clarify.feedback().contains("User notes"));
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    @Test
    void escapeOnTheDesignCardDeniesWithoutFeedback() throws Exception {
        Harness h = new Harness(List.of(design("Which approach?", "Approach")));
        h.key(new KeyStroke(KeyType.ESCAPE));

        assertInstanceOf(QuestionOutcome.Cancelled.class, h.outcome());
        assertFalse(h.dialog.isActive());
    }
}
