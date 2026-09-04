package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The {@code Fys} screen: what it lists, what it omits, and the two ways its confirm select
 * disagrees with the design card's option list.
 */
class ReviewScreenTest {

    private static final int COLUMNS = 100;

    private static List<DisplayQuestion> questions(int count) {
        return QuestionSanitizer.sanitize(IntStream.range(0, count)
            .mapToObj(index -> new QuestionPresenter.Question(
                "Which approach " + (index + 1) + "?", "Approach " + (index + 1),
                List.of(
                    new QuestionPresenter.Option("Tabs", "keeps state", "# Tabs"),
                    new QuestionPresenter.Option("Drawer", "hides state", "# Drawer")),
                false))
            .toList());
    }

    private static ReviewScreen.Context context(
            List<DisplayQuestion> all, Map<String, String> answers) {
        return new ReviewScreen.Context(all, answers, false, COLUMNS);
    }

    private static Map<String, String> answers(List<DisplayQuestion> all, String... values) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            if (values[index] != null) answers.put(all.get(index).key(), values[index]);
        }
        return answers;
    }

    private static String text(List<Segment> line) {
        return line.stream().map(Segment::text).collect(Collectors.joining());
    }

    private static List<String> lines(ReviewScreen.Context context, int confirmFocus) {
        return ReviewScreen.render(context, confirmFocus).stream()
            .map(ReviewScreenTest::text)
            .toList();
    }

    private static KeyStroke character(char value) {
        return new KeyStroke(value, false, false);
    }

    // ── layout ──────────────────────────────────────────────────────────────

    @Test
    void theScreenIsRuledOnceAtTheTopAndNowhereElse() {
        List<DisplayQuestion> all = questions(2);
        List<String> rendered = lines(context(all, answers(all, "Tabs", "Drawer")), 0);
        String rule = "─".repeat(COLUMNS);

        assertEquals(rule, rendered.getFirst());
        assertEquals(1, rendered.stream().filter(rule::equals).count(), rendered.toString());
    }

    @Test
    void eachAnsweredQuestionHangsOffABulletWithItsAnswerIndentedBeneathIt() {
        List<DisplayQuestion> all = questions(2);
        List<String> rendered = lines(context(all, answers(all, "Tabs", "Drawer")), 0);

        int first = rendered.indexOf(" ● Which approach 1?");
        assertTrue(first > 0, rendered.toString());
        assertEquals("   → Tabs", rendered.get(first + 1));
        assertEquals(" ● Which approach 2?", rendered.get(first + 2));
        assertEquals("   → Drawer", rendered.get(first + 3));
    }

    @Test
    void anUnansweredQuestionIsLeftOutOfTheListButRaisesTheWarningRow() {
        List<DisplayQuestion> all = questions(2);
        List<String> rendered = lines(context(all, answers(all, "Tabs", null)), 0);

        assertTrue(rendered.contains("⚠ " + ReviewScreen.INCOMPLETE_NOTICE), rendered.toString());
        assertTrue(rendered.contains(" ● Which approach 1?"), rendered.toString());
        assertFalse(rendered.contains(" ● Which approach 2?"), rendered.toString());
    }

    @Test
    void aFullyAnsweredRequestShowsNoWarningRow() {
        List<DisplayQuestion> all = questions(2);
        ReviewScreen.Context context = context(all, answers(all, "Tabs", "Drawer"));

        assertTrue(context.allQuestionsAnswered());
        assertFalse(lines(context, 0).stream().anyMatch(line -> line.contains("⚠")),
            lines(context, 0).toString());
    }

    @Test
    void aBlankAnswerCountsAsNoAnswerAtAll() {
        List<DisplayQuestion> all = questions(1);
        ReviewScreen.Context context = context(all, answers(all, ""));

        assertFalse(context.allQuestionsAnswered());
        assertFalse(lines(context, 0).stream().anyMatch(line -> line.startsWith(" ● ")),
            lines(context, 0).toString());
    }

    @Test
    void theTitleAndThePromptBracketTheAnswerList() {
        List<DisplayQuestion> all = questions(1);
        List<String> rendered = lines(context(all, answers(all, "Tabs")), 0);

        assertTrue(rendered.indexOf(ReviewScreen.TITLE)
            < rendered.indexOf(" ● Which approach 1?"), rendered.toString());
        assertTrue(rendered.indexOf(" ● Which approach 1?")
            < rendered.indexOf(ReviewScreen.READY_PROMPT), rendered.toString());
    }

    @Test
    void theConfirmRowsAreNumberedWithSubmitFirstAndThePointerOnTheFocusedOne() {
        List<DisplayQuestion> all = questions(1);
        ReviewScreen.Context context = context(all, answers(all, "Tabs"));

        List<String> onSubmit = lines(context, ReviewScreen.SUBMIT_FOCUS);
        assertEquals("❯ 1. Submit answers", onSubmit.get(onSubmit.size() - 2));
        assertEquals("  2. Cancel", onSubmit.getLast());

        List<String> onCancel = lines(context, ReviewScreen.CANCEL_FOCUS);
        assertEquals("  1. Submit answers", onCancel.get(onCancel.size() - 2));
        assertEquals("❯ 2. Cancel", onCancel.getLast());
    }

    @Test
    void aGutterFlaggedQuestionKeepsItsBulletAndGainsTheDimLeftRule() {
        DisplayQuestion wrapped = QuestionSanitizer.sanitize(List.of(
            new QuestionPresenter.Question("A".repeat(120) + "?", "Long",
                List.of(new QuestionPresenter.Option("Tabs", "keeps state", "# Tabs")),
                false))).getFirst();
        assertTrue(wrapped.displayQuestion().needsGutter());

        List<String> rendered =
            lines(context(List.of(wrapped), Map.of(wrapped.key(), "Tabs")), 0);
        List<String> gutterRows = rendered.stream().filter(line -> line.startsWith(" │ ")).toList();

        assertEquals(2, gutterRows.size(), rendered.toString());
        assertTrue(gutterRows.getFirst().startsWith(" │ ● A"), gutterRows.toString());
        assertTrue(gutterRows.get(1).startsWith(" │   A"), gutterRows.toString());
    }

    // ── keys ────────────────────────────────────────────────────────────────

    @Test
    void theConfirmFocusWrapsAtBothEndsUnlikeTheDesignCardsOptionList() {
        assertEquals(ReviewScreen.CANCEL_FOCUS,
            ReviewScreen.handleKey(new KeyStroke(KeyType.ARROW_UP), ReviewScreen.SUBMIT_FOCUS)
                .confirmFocus());
        assertEquals(ReviewScreen.SUBMIT_FOCUS,
            ReviewScreen.handleKey(new KeyStroke(KeyType.ARROW_DOWN), ReviewScreen.CANCEL_FOCUS)
                .confirmFocus());
    }

    @Test
    void vimAndControlChordsNavigateJustLikeTheArrows() {
        assertEquals(ReviewScreen.CANCEL_FOCUS,
            ReviewScreen.handleKey(character('j'), ReviewScreen.SUBMIT_FOCUS).confirmFocus());
        assertEquals(ReviewScreen.SUBMIT_FOCUS,
            ReviewScreen.handleKey(character('k'), ReviewScreen.CANCEL_FOCUS).confirmFocus());
        assertEquals(ReviewScreen.CANCEL_FOCUS,
            ReviewScreen.handleKey(new KeyStroke('n', true, false), ReviewScreen.SUBMIT_FOCUS)
                .confirmFocus());
        assertEquals(ReviewScreen.SUBMIT_FOCUS,
            ReviewScreen.handleKey(new KeyStroke('p', true, false), ReviewScreen.CANCEL_FOCUS)
                .confirmFocus());
    }

    @Test
    void enterAcceptsWhicheverRowHasTheFocus() {
        assertInstanceOf(ReviewScreen.Action.Submit.class,
            ReviewScreen.handleKey(new KeyStroke(KeyType.ENTER), ReviewScreen.SUBMIT_FOCUS)
                .action());
        assertInstanceOf(ReviewScreen.Action.Cancel.class,
            ReviewScreen.handleKey(new KeyStroke(KeyType.ENTER), ReviewScreen.CANCEL_FOCUS)
                .action());
    }

    @Test
    void aDigitSubmitsOutrightRatherThanOnlyMovingTheFocus() {
        ReviewScreen.KeyResult submit =
            ReviewScreen.handleKey(character('1'), ReviewScreen.CANCEL_FOCUS);
        assertInstanceOf(ReviewScreen.Action.Submit.class, submit.action());
        assertEquals(ReviewScreen.CANCEL_FOCUS, submit.confirmFocus());

        assertInstanceOf(ReviewScreen.Action.Cancel.class,
            ReviewScreen.handleKey(character('2'), ReviewScreen.SUBMIT_FOCUS).action());
        assertInstanceOf(ReviewScreen.Action.None.class,
            ReviewScreen.handleKey(character('3'), ReviewScreen.SUBMIT_FOCUS).action());
    }

    @Test
    void spaceDoesNothingBecauseTheConfirmSelectIsNotMultiSelect() {
        assertInstanceOf(ReviewScreen.Action.None.class,
            ReviewScreen.handleKey(character(' '), ReviewScreen.SUBMIT_FOCUS).action());
    }

    @Test
    void escapeCancelsAndTheArrowsWalkBackToAQuestionWhileTabIsSwallowed() {
        assertInstanceOf(ReviewScreen.Action.Cancel.class,
            ReviewScreen.handleKey(new KeyStroke(KeyType.ESCAPE), ReviewScreen.SUBMIT_FOCUS)
                .action());
        assertEquals(new ReviewScreen.Action.SwitchTab(-1),
            ReviewScreen.handleKey(new KeyStroke(KeyType.ARROW_LEFT), ReviewScreen.SUBMIT_FOCUS)
                .action());
        assertEquals(new ReviewScreen.Action.SwitchTab(1),
            ReviewScreen.handleKey(new KeyStroke(KeyType.ARROW_RIGHT), ReviewScreen.SUBMIT_FOCUS)
                .action());
        assertInstanceOf(ReviewScreen.Action.None.class,
            ReviewScreen.handleKey(new KeyStroke(KeyType.TAB), ReviewScreen.SUBMIT_FOCUS)
                .action());
    }

    @Test
    void homeAndEndJumpToTheEndsWithoutAccepting() {
        ReviewScreen.KeyResult home =
            ReviewScreen.handleKey(new KeyStroke(KeyType.HOME), ReviewScreen.CANCEL_FOCUS);
        assertEquals(ReviewScreen.SUBMIT_FOCUS, home.confirmFocus());
        assertInstanceOf(ReviewScreen.Action.None.class, home.action());

        assertEquals(ReviewScreen.CANCEL_FOCUS,
            ReviewScreen.handleKey(new KeyStroke(KeyType.END), ReviewScreen.SUBMIT_FOCUS)
                .confirmFocus());
    }
}
