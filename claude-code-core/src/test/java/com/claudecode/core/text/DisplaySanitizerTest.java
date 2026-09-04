package com.claudecode.core.text;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 2.1.236 display-sanitization primitives. Every expectation here is the behaviour of the
 * bundle function named in the test's comment, not a Java-side convention.
 */
class DisplaySanitizerTest {

    private static final String FFFD = DisplaySanitizer.REPLACEMENT;

    // ── To ──────────────────────────────────────────────────────────────────

    @Test
    void truncationNeverSplitsASurrogatePair() {
        // "😀😀" is four code units; a budget of three has to drop the whole second pair.
        assertEquals("😀", DisplaySanitizer.truncateCodeUnits("😀😀", 3));
        assertEquals("😀😀", DisplaySanitizer.truncateCodeUnits("😀😀", 4));
    }

    @Test
    void truncationIsIdentityBelowTheBudgetAndEmptyAtOrBelowZero() {
        assertSame("short", DisplaySanitizer.truncateCodeUnits("short", 256));
        assertEquals("", DisplaySanitizer.truncateCodeUnits("short", 0));
        assertEquals("", DisplaySanitizer.truncateCodeUnits(null, 256));
    }

    // ── Xyt / FT ────────────────────────────────────────────────────────────

    @Test
    void defaultIgnorablesAndSeparatorsBecomeReplacements() {
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\u200Bb"), "U+200B is Cf");
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\u2028b"), "line separator");
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\u2029b"), "paragraph separator");
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\u2800b"), "blank Braille cell");
    }

    @Test
    void theStrictPassEatsJoinersButThePermissivePassKeepsThem() {
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\u200Db"));
        assertEquals("a\u200Db", DisplaySanitizer.scrubPermissive("a\u200Db"),
            "vfv spares ZWJ so emoji sequences survive");
    }

    @Test
    void emojiPresentationSelectorsAreDroppedRatherThanReplaced() {
        // vfv whitelists VS16 through JCf, then s2b removes it outright in cWd.
        assertEquals("ab", DisplaySanitizer.scrubPermissive("a\uFE0Fb"));
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\uFE0Fb"));
    }

    @Test
    void theSecondPassStillReplacesBidiControlsTheWhitelistLetThrough() {
        // U+061C is whitelisted by vfv but caught by sWd.
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrubPermissive("a\u061Cb"));
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrubPermissive("a\u202Eb"),
            "right-to-left override");
    }

    @Test
    void carriageReturnsAreReplacedButTabsAndNewlinesSurvive() {
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\rb"), "iWd spares only 9 and 10");
        assertEquals("a\nb", DisplaySanitizer.scrub("a\nb"));
        assertEquals("a       b", DisplaySanitizer.scrub("a\tb"), "nYo expands to 8-column stops");
    }

    @Test
    void loneSurrogatesAreReplaced() {
        assertEquals("a" + FFFD + "b", DisplaySanitizer.scrub("a\uD800b"));
        assertEquals("😀", DisplaySanitizer.scrub("😀"), "a well-formed pair is untouched");
    }

    // ── Yi ──────────────────────────────────────────────────────────────────

    @Test
    void widthTruncationBudgetsForItsOwnEllipsisAcrossWideCharacters() {
        // Each CJK glyph is two columns; a budget of five fits two of them plus the ellipsis.
        assertEquals("中文…", DisplaySanitizer.truncateToWidth("中文中文中文", 5));
        assertEquals(5, FormatUtils.displayWidth(DisplaySanitizer.truncateToWidth("中文中文中文", 5)));
    }

    @Test
    void widthTruncationIsIdentityWhenItAlreadyFitsAndDegeneratesToAnEllipsis() {
        assertEquals("中文", DisplaySanitizer.truncateToWidth("中文", 4));
        assertEquals("…", DisplaySanitizer.truncateToWidth("中文", 1));
    }

    // ── qVa ─────────────────────────────────────────────────────────────────

    @Test
    void cleanCollapsedTextIsReturnedAsIs() {
        assertEquals("hello", DisplaySanitizer.quoteIfSuspicious("hello", "hello", false));
    }

    @Test
    void eachSuspicionTriggerForcesJsonQuoting() {
        // The quoted payload is always the *scrubbed* text, so nothing collapsing removed is lost.
        assertEquals("\"a\\nb\"", DisplaySanitizer.quoteIfSuspicious("a b", "a\nb", false),
            "collapsing changed the text");
        assertEquals("\"\\\"x\"", DisplaySanitizer.quoteIfSuspicious("\"x", "\"x", false),
            "leading quote mimics markup");
        assertEquals("\"x\\\"\"", DisplaySanitizer.quoteIfSuspicious("x\"", "x\"", false),
            "trailing quote mimics markup");
        assertEquals("\"x…\"", DisplaySanitizer.quoteIfSuspicious("x…", "x…", false),
            "trailing ellipsis mimics truncation");
        assertEquals("\"x\"", DisplaySanitizer.quoteIfSuspicious("x", "x", true),
            "the input was over its code-unit budget");
    }

    // ── ZCf / XCf ───────────────────────────────────────────────────────────

    @Test
    void anOrdinaryLabelSurvivesUnchanged() {
        assertEquals("Use Redis", DisplaySanitizer.sanitizeLabel("Use Redis"));
    }

    @Test
    void aLabelWhoseWhitespaceCollapsedIsQuotedRatherThanSilentlyReflowed() {
        // qVa compares the collapsed form against the scrubbed one; a doubled space is a
        // difference, so the reader gets the original spacing back inside quotes.
        assertEquals("\"Use  Redis\"", DisplaySanitizer.sanitizeLabel("Use  Redis"));
    }

    @Test
    void aMultilineLabelIsQuotedSoTheLineBreakIsVisible() {
        assertEquals("\"a\\nb\"", DisplaySanitizer.sanitizeLabel("a\nb"));
    }

    @Test
    void anOverlongLabelIsQuotedEvenWhenItsPrefixLooksClean() {
        String quoted = DisplaySanitizer.sanitizeLabel("x".repeat(DisplaySanitizer.LABEL_LIMIT + 1));
        assertTrue(Strings.CS.startsWith(quoted, "\"") && Strings.CS.endsWith(quoted, "\""), quoted);
        assertEquals(DisplaySanitizer.LABEL_LIMIT + 2, quoted.length(), "clamped then quoted");
    }

    @Test
    void headersAreAlsoClampedToFortyEightColumns() {
        String header = DisplaySanitizer.sanitizeHeader("中".repeat(40));

        // Yi budgets t-1 columns for content, so a run of two-column glyphs lands one short.
        assertEquals(47, FormatUtils.displayWidth(header));
        assertTrue(FormatUtils.displayWidth(header) <= DisplaySanitizer.HEADER_WIDTH_LIMIT);
        assertTrue(Strings.CS.endsWith(header, "…"), header);
    }

    @Test
    void aShortHeaderIsNotPadded() {
        assertEquals("Approach", DisplaySanitizer.sanitizeHeader("Approach"));
    }

    // ── Cfv ─────────────────────────────────────────────────────────────────

    @Test
    void clampedTextFlattensTabsAndMarksTruncation() {
        assertEquals("a b", DisplaySanitizer.clampText("a\tb"));
        String clamped = DisplaySanitizer.clampText("x".repeat(DisplaySanitizer.TEXT_LIMIT + 1));
        assertEquals(DisplaySanitizer.TEXT_LIMIT + 1, clamped.length());
        assertTrue(Strings.CS.endsWith(clamped, "…"), "the ellipsis is appended past the budget");
    }

    // ── i9 ──────────────────────────────────────────────────────────────────

    @Test
    void theGutterThresholdIsExclusiveAtEightyColumns() {
        assertFalse(DisplaySanitizer.needsGutter("a".repeat(80)));
        assertTrue(DisplaySanitizer.needsGutter("a".repeat(81)));
        assertTrue(DisplaySanitizer.needsGutter("a\nb"), "any newline claims a multi-line slot");
        assertTrue(DisplaySanitizer.needsGutter("中".repeat(41)), "width, not character count");
    }

    // ── u6e ─────────────────────────────────────────────────────────────────

    @Test
    void visibilityIgnoresWhitespaceAndScrubbedAwayCharacters() {
        assertTrue(DisplaySanitizer.isVisiblyNonBlank("x"));
        assertFalse(DisplaySanitizer.isVisiblyNonBlank("   \n\t "));
        assertFalse(DisplaySanitizer.isVisiblyNonBlank("\u200B\u200B"),
            "zero-width characters scrub to U+FFFD, which is then removed");
        assertFalse(DisplaySanitizer.isVisiblyNonBlank(null));
    }

    // ── fA ──────────────────────────────────────────────────────────────────

    @Test
    void newlineFlatteningLeavesAVisibleScar() {
        assertEquals("a" + FFFD + FFFD + "b", DisplaySanitizer.flattenNewlines("a\r\nb"));
        assertEquals("a" + FFFD + "b", DisplaySanitizer.flattenNewlines("a\u2028b"));
    }

    // ── xe ──────────────────────────────────────────────────────────────────

    @Test
    void jsonQuotingMatchesEcmaScriptIncludingLoneSurrogates() {
        assertEquals("\"a\\nb\"", DisplaySanitizer.jsonQuote("a\nb"));
        assertEquals("\"a\\\\b\"", DisplaySanitizer.jsonQuote("a\\b"));
        assertEquals("\"\\u0001\"", DisplaySanitizer.jsonQuote("\u0001"));
        assertEquals("\"\\ud800\"", DisplaySanitizer.jsonQuote("\uD800"),
            "well-formed JSON.stringify escapes an unpaired surrogate");
        assertEquals("\"😀\"", DisplaySanitizer.jsonQuote("😀"), "a valid pair stays literal");
    }

    // ── _le / W9r ───────────────────────────────────────────────────────────

    @Test
    void distinctLabelsPassThroughTheKeyFunctionUntouched() {
        assertEquals(List.of("Yes", "No"), DisplaySanitizer.dedupeDisplayLabels(List.of("Yes", "No")));
    }

    @Test
    void labelsThatCollideOnlyAfterScrubbingFallBackToEscapedForms() {
        // Two different invisible characters both scrub to U+FFFD, so the sanitised labels tie.
        List<String> deduped = DisplaySanitizer.dedupeDisplayLabels(List.of("a\u200Bb", "a\u200Cb"));

        assertEquals(List.of("\"a\\u200bb\"", "\"a\\u200cb\""), deduped,
            "the escaped form spells out the code unit that actually differs");
    }

    @Test
    void identicalEscapedFormsTakeANumberedSuffix() {
        // Both values share their first 2000 code units, so the escaped forms are equal too.
        String shared = "x".repeat(DisplaySanitizer.TEXT_LIMIT);
        List<String> deduped = DisplaySanitizer.dedupeDisplayLabels(List.of(shared + "a", shared + "b"));

        assertEquals(2, deduped.size());
        assertTrue(Strings.CS.endsWith(deduped.getFirst(), "…"), deduped.getFirst());
        assertEquals(deduped.getFirst() + " (#2)", deduped.get(1));
    }

    @Test
    void theSameValueRepeatedKeepsOneOrdinal() {
        List<String> deduped =
            DisplaySanitizer.dedupeDisplayLabels(List.of("a\u200Bb", "a\u200Bb", "a\u200Cb"));

        assertEquals(deduped.getFirst(), deduped.get(1),
            "a repeated value is the same string, so it keeps its first ordinal");
    }

    @Test
    void aCustomKeyFunctionDrivesTheCollisionCheck() {
        // w2g's key function flattens newlines instead of quoting, so these two do collide.
        List<String> deduped = DisplaySanitizer.dedupeDisplayLabels(
            List.of("a\nb", "a\rb"),
            value -> DisplaySanitizer.collapseWhitespace(
                DisplaySanitizer.flattenNewlines(DisplaySanitizer.scrubPermissive(value))));

        assertEquals(List.of("\"a\\nb\"", "\"a\\rb\""), deduped);
    }

    @Test
    void anEmptyListIsReturnedEmpty() {
        assertEquals(List.of(), DisplaySanitizer.dedupeDisplayLabels(List.of()));
    }
}
