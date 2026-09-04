package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The {@code d$c} card: its two-column geometry, the rows the design variant deliberately omits,
 * and the key handler's three modes.
 */
class DesignQuestionViewTest {

    private static final int COLUMNS = 100;
    private static final int ROWS = 40;

    private static List<DisplayQuestion> questions(int count) {
        return QuestionSanitizer.sanitize(IntStream.range(0, count)
            .mapToObj(index -> new QuestionPresenter.Question(
                "Which approach " + (index + 1) + "?", "Approach " + (index + 1),
                List.of(
                    new QuestionPresenter.Option("Tabs", "keeps state", "# Tabs\n\nA preview."),
                    new QuestionPresenter.Option("Drawer", "hides state", "# Drawer\n\nAnother."),
                    new QuestionPresenter.Option("Modal", "blocks state", null)),
                false))
            .toList());
    }

    private static DesignQuestionView.Context context(List<DisplayQuestion> all, int index) {
        return new DesignQuestionView.Context(all, index, Set.of(), all.size() == 1,
            PreviewBox.sharedMinWidth(all), COLUMNS, ROWS, "Vim");
    }

    private static String text(List<Segment> line) {
        return line.stream().map(Segment::text).collect(Collectors.joining());
    }

    private static List<String> lines(
            DesignQuestionView.Context context, QuestionState state) {
        return DesignQuestionView.render(context, state).stream()
            .map(DesignQuestionViewTest::text)
            .toList();
    }

    private static KeyStroke character(char value) {
        return new KeyStroke(value, false, false);
    }

    private static KeyStroke control(char value) {
        return new KeyStroke(value, true, false);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    @Test
    void thePreviewColumnStartsAfterTheThirtyColumnOptionListAndItsFourColumnGap() {
        List<DisplayQuestion> all = questions(1);
        String optionRow = lines(context(all, 0), new QuestionState()).stream()
            .filter(line -> line.contains("Tabs"))
            .findFirst()
            .orElseThrow();

        assertEquals("❯ 1. Tabs", optionRow.substring(0, 9));
        assertEquals(DesignQuestionView.OPTION_COLUMN_WIDTH + DesignQuestionView.COLUMN_GAP,
            optionRow.indexOf('┌'),
            optionRow + " — the box must open at column 34");
    }

    @Test
    void theCardIsRuledTopAndAboveTheChatRow() {
        List<String> rendered = lines(context(questions(1), 0), new QuestionState());
        String rule = "─".repeat(COLUMNS);

        assertEquals(rule, rendered.getFirst());
        int chatRow = rendered.indexOf("  " + DesignQuestionView.CHAT_LABEL);
        assertTrue(chatRow > 0, rendered.toString());
        assertEquals(rule, rendered.get(chatRow - 1));
    }

    @Test
    void theDesignVariantShowsNoOptionDescriptions() {
        List<String> rendered = lines(context(questions(1), 0), new QuestionState());

        assertTrue(rendered.stream().anyMatch(line -> line.contains("Tabs")), rendered.toString());
        assertFalse(rendered.stream().anyMatch(line -> line.contains("keeps state")),
            rendered.toString());
    }

    @Test
    void everyPreviewBoxOnACardIsTheSameWidthSoMovingTheSelectionCannotJitterIt() {
        List<DisplayQuestion> all = questions(1);
        DesignQuestionView.Context context = context(all, 0);
        QuestionState first = new QuestionState();
        QuestionState second = new QuestionState();
        second.setFocus(1);

        assertEquals(boxWidth(lines(context, first)), boxWidth(lines(context, second)));
    }

    private static int boxWidth(List<String> rendered) {
        String top = rendered.stream().filter(line -> line.contains("┌")).findFirst().orElseThrow();
        return FormatUtils.displayWidth(top.substring(top.indexOf('┌')));
    }

    @Test
    void anOptionWithNoPreviewFallsBackToTheStandingNotice() {
        QuestionState state = new QuestionState();
        state.setFocus(2);   // "Modal" carries no preview

        assertTrue(lines(context(questions(1), 0), state).stream()
            .anyMatch(line -> line.contains(PreviewBox.NO_PREVIEW)));
    }

    @Test
    void theSelectedOptionIsTicked() {
        QuestionState state = new QuestionState();
        state.selectOnly(1);

        assertTrue(lines(context(questions(1), 0), state).stream()
            .anyMatch(line -> line.startsWith("  2. Drawer ✔")));
    }

    // ── footer ──────────────────────────────────────────────────────────────

    @Test
    void aLoneQuestionOffersNoTabChordAndNoEditorChordUntilTheNotesEditorIsOpen() {
        List<DisplayQuestion> all = questions(1);
        QuestionState state = new QuestionState();

        assertEquals("Enter to select · ↑/↓ to navigate · n to add notes · Esc to cancel",
            lines(context(all, 0), state).getLast());

        state.setNotesEditing(true);
        assertEquals("Enter to select · ↑/↓ to navigate · n to add notes"
                + " · ctrl+g to edit in Vim · Esc to cancel",
            lines(context(all, 0), state).getLast());
    }

    @Test
    void severalQuestionsAddTheTabChord() {
        assertTrue(lines(context(questions(2), 0), new QuestionState()).getLast()
            .contains("Tab to switch questions"));
    }

    // ── keys: options ───────────────────────────────────────────────────────

    @Test
    void navigationClampsAtBothEndsInsteadOfWrapping() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();

        DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_UP), question, state);
        assertEquals(0, state.focus());
        assertFalse(state.chatFocused());

        DesignQuestionView.handleKey(control('p'), question, state);
        assertEquals(0, state.focus());
    }

    @Test
    void movingPastTheLastOptionFocusesTheChatRowRatherThanTheFirstOption() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();

        for (int step = 0; step < 2; step++) {
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_DOWN), question, state);
        }
        assertEquals(2, state.focus());
        assertFalse(state.chatFocused());

        DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_DOWN), question, state);
        assertTrue(state.chatFocused());
        assertEquals(2, state.focus());
    }

    @Test
    void aDigitOnlyMovesTheFocusAndNeverSubmits() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();

        assertInstanceOf(DesignQuestionView.Action.None.class,
            DesignQuestionView.handleKey(character('2'), question, state));
        assertEquals(1, state.focus());
        assertFalse(state.hasSelection());

        // out of range: ignored outright
        DesignQuestionView.handleKey(character('9'), question, state);
        assertEquals(1, state.focus());
    }

    @Test
    void enterAnswersWithTheFocusedOptionsRawValue() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        state.setFocus(1);

        DesignQuestionView.Action action =
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ENTER), question, state);

        assertEquals(new DesignQuestionView.Action.Answer("Drawer"), action);
        assertTrue(state.isSelected(1));
    }

    @Test
    void tabSwitchesQuestionsOnlyWhileNeitherNotesNorChatHoldsTheKeyboard() {
        DisplayQuestion question = questions(2).getFirst();
        QuestionState state = new QuestionState();

        assertEquals(new DesignQuestionView.Action.SwitchTab(1),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.TAB), question, state));
        assertEquals(new DesignQuestionView.Action.SwitchTab(-1),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.TAB, false, false, true),
                question, state));

        state.setNotesEditing(true);
        assertInstanceOf(DesignQuestionView.Action.None.class,
            DesignQuestionView.handleKey(new KeyStroke(KeyType.TAB), question, state));
    }

    @Test
    void theArrowKeysSwitchQuestionsEverywhereExceptInsideTheNotesEditor() {
        DisplayQuestion question = questions(2).getFirst();
        QuestionState state = new QuestionState();

        assertEquals(new DesignQuestionView.Action.SwitchTab(-1),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_LEFT), question, state));
        assertEquals(new DesignQuestionView.Action.SwitchTab(1),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_RIGHT), question, state));

        state.setNotesEditing(true);
        assertInstanceOf(DesignQuestionView.Action.None.class,
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_LEFT), question, state));
    }

    // ── keys: notes ─────────────────────────────────────────────────────────

    @Test
    void escapeInsideTheNotesEditorLeavesTheEditorRatherThanTheCard() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();

        DesignQuestionView.handleKey(character('n'), question, state);
        assertTrue(state.notesEditing());

        DesignQuestionView.Action action =
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ESCAPE), question, state);

        assertInstanceOf(DesignQuestionView.Action.None.class, action);
        assertFalse(state.notesEditing());

        assertInstanceOf(DesignQuestionView.Action.Cancel.class,
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ESCAPE), question, state));
    }

    @Test
    void pastedTextLandsInTheNotesBufferInsteadOfLeakingToTheInputBehindTheCard() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        DesignQuestionView.handleKey(character('n'), question, state);

        DesignQuestionView.Action action = DesignQuestionView.handleKey(
            new PasteKeyStroke("two\nlines"), question, state);

        assertInstanceOf(DesignQuestionView.Action.None.class, action);
        assertEquals("two lines", state.text());
    }

    @Test
    void submittingBareNotesAnswersWithTheNotesOnlySentinel() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        DesignQuestionView.handleKey(character('n'), question, state);
        DesignQuestionView.handleKey(character('h'), question, state);

        assertEquals(new DesignQuestionView.Action.Answer(DesignQuestionView.NOTES_ONLY),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ENTER), question, state));
        assertFalse(state.notesEditing());
    }

    @Test
    void submittingNotesOverAnAlreadyChosenOptionKeepsThatOptionAsTheAnswer() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        state.selectOnly(0);
        DesignQuestionView.handleKey(character('n'), question, state);
        DesignQuestionView.handleKey(character('h'), question, state);

        assertEquals(new DesignQuestionView.Action.Answer("Tabs"),
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ENTER), question, state));
    }

    @Test
    void anEmptyNotesEditorSubmitsNothingAtAll() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        DesignQuestionView.handleKey(character('n'), question, state);

        assertInstanceOf(DesignQuestionView.Action.None.class,
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ENTER), question, state));
    }

    // ── keys: chat row ──────────────────────────────────────────────────────

    @Test
    void theChatRowAnswersToEnterAndReleasesTheFocusUpwards() {
        DisplayQuestion question = questions(1).getFirst();
        QuestionState state = new QuestionState();
        state.setChatFocused(true);

        assertInstanceOf(DesignQuestionView.Action.RespondToClaude.class,
            DesignQuestionView.handleKey(new KeyStroke(KeyType.ENTER), question, state));

        DesignQuestionView.handleKey(new KeyStroke(KeyType.ARROW_UP), question, state);
        assertFalse(state.chatFocused());
    }

    @Test
    void escapeOnTheChatRowStillCancelsTheWholeCard() {
        QuestionState state = new QuestionState();
        state.setChatFocused(true);

        assertInstanceOf(DesignQuestionView.Action.Cancel.class, DesignQuestionView.handleKey(
            new KeyStroke(KeyType.ESCAPE), questions(1).getFirst(), state));
    }

    // ── narrow terminals ────────────────────────────────────────────────────

    @Test
    void aTerminalTooNarrowForThePreviewColumnIsReportedRatherThanLaidOut() {
        assertFalse(DesignQuestionView.fitsTerminal(DesignQuestionView.MIN_TERMINAL_COLUMNS - 1));
        assertTrue(DesignQuestionView.fitsTerminal(DesignQuestionView.MIN_TERMINAL_COLUMNS));
    }
}
