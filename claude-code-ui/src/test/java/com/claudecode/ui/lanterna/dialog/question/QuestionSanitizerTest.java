package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.DisplaySanitizer;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code w2g} projection. Every expectation is the behaviour of the 2.1.236 bundle function
 * named in the test's comment, not a Java-side convention.
 */
class QuestionSanitizerTest {

    private static final String FFFD = DisplaySanitizer.REPLACEMENT;

    private static QuestionPresenter.Question question(QuestionPresenter.Option... options) {
        return new QuestionPresenter.Question("Which approach?", "Approach", List.of(options), false);
    }

    private static QuestionPresenter.Option option(String label, String preview) {
        return new QuestionPresenter.Option(label, "why " + label, preview);
    }

    private static DisplayQuestion.DisplayOption firstOption(QuestionPresenter.Question source) {
        return QuestionSanitizer.sanitize(List.of(source)).getFirst().options().getFirst();
    }

    // ── preview branches ────────────────────────────────────────────────────

    @Test
    void anAbsentPreviewStaysAbsent() {
        assertNull(firstOption(question(option("Redis", null))).preview());
    }

    @Test
    void anOverlongPreviewIsWithheldRatherThanTruncated() {
        String tooLong = "x".repeat(DisplaySanitizer.TEXT_LIMIT + 1);

        assertInstanceOf(DisplayQuestion.Preview.Withheld.class,
            firstOption(question(option("Redis", tooLong))).preview());
    }

    @Test
    void aPreviewExactlyAtTheLimitIsStillShown() {
        String atLimit = "x".repeat(DisplaySanitizer.TEXT_LIMIT);

        assertInstanceOf(DisplayQuestion.Preview.Full.class,
            firstOption(question(option("Redis", atLimit))).preview(), "the test is > not >=");
    }

    @Test
    void aPreviewThatScrubsAwayToNothingIsDropped() {
        // u6e strips U+FFFD before trimming, so invisible-only content counts as blank.
        assertNull(firstOption(question(option("Redis", "   \n\t "))).preview());
        assertNull(firstOption(question(option("Redis", "\u200b\u200b"))).preview());
    }

    @Test
    void anOrdinaryPreviewIsKeptAsScrubbedMarkdown() {
        DisplayQuestion.Preview preview =
            firstOption(question(option("Redis", "# Cache\n\n```java\nvar x = 1;\n```"))).preview();

        assertEquals(new DisplayQuestion.Preview.Full("# Cache\n\n```java\nvar x = 1;\n```"), preview);
    }

    @Test
    void aPreviewIsScrubbedButNotClampedOrCollapsed() {
        // $Wn is FT alone — no Cfv, so newlines and runs of spaces survive into the markdown.
        DisplayQuestion.Preview preview =
            firstOption(question(option("Redis", "a\u202eb\n  c"))).preview();

        assertEquals(new DisplayQuestion.Preview.Full("a" + FFFD + "b\n  c"), preview);
    }

    // ── displayLabel (_le with w2g's own key function) ──────────────────────

    @Test
    void anOrdinaryLabelIsCollapsedButNeitherQuotedNorClamped() {
        // w2g passes its own key function to _le, so unlike ZCf there is no 256-code-unit cap and
        // no JSON quoting when collapsing changes the text.
        assertEquals("Use Redis", firstOption(question(option("Use  Redis", null))).displayLabel());

        String long_ = "x".repeat(DisplaySanitizer.LABEL_LIMIT + 44);
        assertEquals(long_, firstOption(question(option(long_, null))).displayLabel());
    }

    @Test
    void aNewlineInALabelBecomesAVisibleScarRatherThanAWrap() {
        assertEquals("a" + FFFD + " b", firstOption(question(option("a\n b", null))).displayLabel());
    }

    @Test
    void labelsCollidingOnlyAfterScrubbingFallBackToEscapedForms() {
        // U+200B and U+2060 are both scrubbed to U+FFFD, so the two labels tie on the key.
        List<DisplayQuestion.DisplayOption> options = QuestionSanitizer
            .sanitize(List.of(question(option("a\u200bb", null), option("a\u2060b", null))))
            .getFirst()
            .options();

        assertEquals("\"a\\u200bb\"", options.get(0).displayLabel());
        assertEquals("\"a\\u2060b\"", options.get(1).displayLabel(),
            "the escaped form spells out the code unit that actually differs");
    }

    @Test
    void labelsWhoseEscapedFormsAlsoTieTakeANumberedSuffix() {
        // Both labels share their first 2000 code units, so even the escaped fallback collides.
        String shared = "x".repeat(DisplaySanitizer.TEXT_LIMIT);
        List<DisplayQuestion.DisplayOption> options = QuestionSanitizer
            .sanitize(List.of(question(option(shared + "a", null), option(shared + "b", null))))
            .getFirst()
            .options();

        assertEquals(options.get(0).displayLabel() + " (#2)", options.get(1).displayLabel());
    }

    @Test
    void theRawLabelIsPreservedAsTheOptionValue() {
        assertEquals("Use  Redis", firstOption(question(option("Use  Redis", null))).value());
    }

    // ── displayHeader (Bcr / jHe) ───────────────────────────────────────────

    @Test
    void aHeaderThatRendersAsNothingCollapsesToTheEmptyString() {
        assertEquals("", QuestionSanitizer.sanitizeHeader(null));
        assertEquals("", QuestionSanitizer.sanitizeHeader(""));
        assertEquals("", QuestionSanitizer.sanitizeHeader("\u200b\u200b"), "zero display width");
    }

    @Test
    void anOrdinaryHeaderSurvivesAndAnOverlongOneIsClampedToFortyEightColumns() {
        assertEquals("Approach", QuestionSanitizer.sanitizeHeader("Approach"));

        String clamped = QuestionSanitizer.sanitizeHeader("中".repeat(40));
        assertTrue(FormatUtils.displayWidth(clamped) <= DisplaySanitizer.HEADER_WIDTH_LIMIT, clamped);
        assertTrue(clamped.endsWith("…"), clamped);
    }

    // ── displayQuestion / displayDescription (pA) ───────────────────────────

    @Test
    void theQuestionTextCarriesItsMultiLineSlotFlag() {
        assertFalse(QuestionSanitizer.displayText("Which approach?").needsGutter());
        assertTrue(QuestionSanitizer.displayText("Which\napproach?").needsGutter());
        assertTrue(QuestionSanitizer.displayText("a".repeat(81)).needsGutter());
        assertFalse(QuestionSanitizer.displayText("a".repeat(80)).needsGutter());
    }

    @Test
    void anOverlongQuestionIsClampedWithATrailingEllipsis() {
        DisplayQuestion.DisplayText text =
            QuestionSanitizer.displayText("a".repeat(DisplaySanitizer.TEXT_LIMIT + 5));

        assertEquals(DisplaySanitizer.TEXT_LIMIT + 1, text.text().length());
        assertTrue(text.text().endsWith("…"));
        assertTrue(text.needsGutter());
    }

    @Test
    void theDescriptionIsScrubbedButKeepsItsLineBreaks() {
        QuestionPresenter.Option raw =
            new QuestionPresenter.Option("Redis", "fast\nbut\u202evolatile", null);

        assertEquals("fast\nbut" + FFFD + "volatile", firstOption(question(raw)).displayDescription());
    }

    // ── A2g / E2g ───────────────────────────────────────────────────────────

    @Test
    void onlyASingleSelectQuestionWithAtLeastOnePreviewIsTheDesignVariant() {
        DisplayQuestion withPreview = QuestionSanitizer
            .sanitize(List.of(question(option("Redis", "# Cache"), option("Postgres", null))))
            .getFirst();
        DisplayQuestion withoutPreview = QuestionSanitizer
            .sanitize(List.of(question(option("Redis", null), option("Postgres", null))))
            .getFirst();
        DisplayQuestion multiSelect = QuestionSanitizer
            .sanitize(List.of(new QuestionPresenter.Question(
                "Which?", "Pick", List.of(option("Redis", "# Cache")), true)))
            .getFirst();

        assertTrue(QuestionSanitizer.isDesignVariant(withPreview));
        assertFalse(QuestionSanitizer.isDesignVariant(withoutPreview));
        assertFalse(QuestionSanitizer.isDesignVariant(multiSelect));
    }

    @Test
    void aPreviewWithheldByLengthStillSelectsTheDesignVariant() {
        DisplayQuestion withheld = QuestionSanitizer
            .sanitize(List.of(question(option("Redis", "x".repeat(DisplaySanitizer.TEXT_LIMIT + 1)))))
            .getFirst();

        assertTrue(QuestionSanitizer.isDesignVariant(withheld));
    }

    @Test
    void aPreviewDroppedAsBlankFallsBackToTheListVariant() {
        DisplayQuestion blank =
            QuestionSanitizer.sanitize(List.of(question(option("Redis", "   ")))).getFirst();

        assertFalse(QuestionSanitizer.isDesignVariant(blank),
            "w2g maps a blank preview to undefined, which A2g cannot see");
    }

    @Test
    void theRecordedAnswerIsTheSanitizedLabelNotTheRawOne() {
        DisplayQuestion sanitized =
            QuestionSanitizer.sanitize(List.of(question(option("Use  Redis", null)))).getFirst();

        assertEquals("Use Redis", QuestionSanitizer.answerValueFor(sanitized, "Use  Redis"));
        assertEquals("Other", QuestionSanitizer.answerValueFor(sanitized, "Other"),
            "a value with no matching option passes through unchanged");
    }

    // ── shape ───────────────────────────────────────────────────────────────

    @Test
    void theKeyIsTheRawQuestionTextSoAnswersStayAddressable() {
        DisplayQuestion sanitized = QuestionSanitizer
            .sanitize(List.of(new QuestionPresenter.Question(
                "Which  approach?", "Approach", List.of(option("Redis", null)), false)))
            .getFirst();

        assertEquals("Which  approach?", sanitized.key());
        assertEquals("Which  approach?", sanitized.displayQuestion().text(),
            "pA clamps and scrubs but never collapses whitespace — only labels are collapsed");
    }

    @Test
    void anEmptyOrNullQuestionListProjectsToNothing() {
        assertEquals(List.of(), QuestionSanitizer.sanitize(List.of()));
        assertEquals(List.of(), QuestionSanitizer.sanitize(null));
    }
}
