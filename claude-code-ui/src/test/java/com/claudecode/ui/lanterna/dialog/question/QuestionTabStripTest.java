package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The {@code d0t} strip: label budgets, checkbox state, and which chip wears the current colour. */
class QuestionTabStripTest {

    private static List<DisplayQuestion> questions(String... headers) {
        return QuestionSanitizer.sanitize(Arrays.stream(headers)
            .map(header -> new QuestionPresenter.Question(
                "Question about " + header, header,
                List.of(new QuestionPresenter.Option("yes", "why", null)), false))
            .toList());
    }

    private static String text(List<Segment> strip) {
        return strip.stream().map(Segment::text).collect(Collectors.joining());
    }

    private static int width(List<Segment> strip) {
        return FormatUtils.displayWidth(text(strip));
    }

    // ── budget branches ─────────────────────────────────────────────────────

    @Test
    void aStripThatFitsKeepsEveryHeaderIntact() {
        List<Segment> strip =
            QuestionTabStrip.render(questions("Approach", "Storage"), 0, Set.of(), false, 120);

        assertEquals("←  ☐ Approach  ☐ Storage  ✔ Submit  →", text(strip));
        assertTrue(width(strip) <= 120);
    }

    @Test
    void aCrowdedStripTruncatesTheHeadersToFit() {
        List<DisplayQuestion> many =
            questions("Architecture", "Persistence", "Deployment", "Observability");
        List<Segment> strip = QuestionTabStrip.render(many, 0, Set.of(), false, 60);

        assertTrue(text(strip).contains("…"), text(strip));
        assertTrue(width(strip) <= 60, text(strip) + " is " + width(strip) + " wide");
    }

    @Test
    void theCurrentTabIsGivenUpToHalfTheBudgetWhileTheRestShareTheRemainder() {
        List<DisplayQuestion> many =
            questions("Architecture", "Persistence", "Deployment", "Observability");
        String first = text(QuestionTabStrip.render(many, 0, Set.of(), false, 60));
        String third = text(QuestionTabStrip.render(many, 2, Set.of(), false, 60));

        assertTrue(first.contains("Architecture"), first + " — the focused tab is not truncated");
        assertFalse(third.contains("Architecture"), third + " — an unfocused tab is");
        assertTrue(third.contains("Deployment"), third);
    }

    @Test
    void aBudgetSwallowedWholeByTheArrowsAndSubmitLeavesOnlyTheCurrentHeaderClipped() {
        // The bundle blanks every other label here, then its render step falls back to the full
        // header — so the strip overflows. Reproduced rather than corrected.
        List<Segment> strip =
            QuestionTabStrip.render(questions("Approach", "Storage"), 1, Set.of(), false, 8);

        assertTrue(text(strip).contains("☐ St…"), text(strip) + " — clipped to three columns");
        assertTrue(text(strip).contains("☐ Approach"), text(strip) + " — the bundle's own fallback");
    }

    // ── shape ───────────────────────────────────────────────────────────────

    @Test
    void anAnsweredQuestionFlipsItsCheckbox() {
        List<DisplayQuestion> two = questions("Approach", "Storage");
        List<Segment> strip =
            QuestionTabStrip.render(two, 0, Set.of(two.getFirst().key()), false, 120);

        assertTrue(text(strip).contains("☒ Approach"), text(strip));
        assertTrue(text(strip).contains("☐ Storage"), text(strip));
    }

    @Test
    void aQuestionWithNoUsableHeaderIsAddressedByItsPosition() {
        List<DisplayQuestion> blank = QuestionSanitizer.sanitize(List.of(
            new QuestionPresenter.Question("First?", "",
                List.of(new QuestionPresenter.Option("yes", "why", null)), false),
            new QuestionPresenter.Question("Second?", "\u200b",
                List.of(new QuestionPresenter.Option("yes", "why", null)), false)));

        assertEquals("←  ☐ Q1  ☐ Q2  ✔ Submit  →",
            text(QuestionTabStrip.render(blank, 0, Set.of(), false, 120)));
    }

    @Test
    void aLoneAutoSubmittingQuestionShowsNeitherSubmitTabNorArrows() {
        List<Segment> strip = QuestionTabStrip.render(questions("Approach"), 0, Set.of(), true, 120);

        assertEquals(" ☐ Approach ", text(strip));
    }

    @Test
    void severalQuestionsKeepTheirArrowsEvenWhenSubmitIsHidden() {
        List<Segment> strip =
            QuestionTabStrip.render(questions("Approach", "Storage"), 0, Set.of(), true, 120);

        assertEquals("←  ☐ Approach  ☐ Storage  →", text(strip));
    }

    // ── colours ─────────────────────────────────────────────────────────────

    @Test
    void onlyTheCurrentChipIsPaintedWithThePermissionBackground() {
        List<Segment> strip =
            QuestionTabStrip.render(questions("Approach", "Storage"), 1, Set.of(), false, 120);
        List<Segment> highlighted = strip.stream()
            .filter(segment -> LanternaTheme.permission().equals(segment.bgColor()))
            .toList();

        assertEquals(1, highlighted.size());
        assertEquals(" ☐ Storage ", highlighted.getFirst().text());
        assertEquals(LanternaTheme.inverseText(), highlighted.getFirst().color());
    }

    @Test
    void theSubmitChipTakesTheHighlightOnceFocusMovesPastTheLastQuestion() {
        List<DisplayQuestion> two = questions("Approach", "Storage");
        List<Segment> strip = QuestionTabStrip.render(two, 2, Set.of(), false, 120);

        assertEquals(" ✔ Submit ", strip.stream()
            .filter(segment -> LanternaTheme.permission().equals(segment.bgColor()))
            .findFirst().orElseThrow().text());
    }

    @Test
    void anArrowDimsOnceThereIsNowhereFurtherToGoInThatDirection() {
        List<DisplayQuestion> two = questions("Approach", "Storage");
        List<Segment> atStart = QuestionTabStrip.render(two, 0, Set.of(), false, 120);
        List<Segment> atEnd = QuestionTabStrip.render(two, 2, Set.of(), false, 120);

        assertEquals(LanternaTheme.welcomeDim(), atStart.getFirst().color());
        assertEquals(LanternaTheme.inputText(), atStart.getLast().color());
        assertEquals(LanternaTheme.inputText(), atEnd.getFirst().color());
        assertEquals(LanternaTheme.welcomeDim(), atEnd.getLast().color());
    }
}
