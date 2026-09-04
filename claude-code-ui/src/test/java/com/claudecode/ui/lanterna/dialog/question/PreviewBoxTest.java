package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The {@code r$c} geometry. Every expectation is the arithmetic of the 2.1.236 bundle, not a
 * Java-side convention.
 */
class PreviewBoxTest {

    private static String text(List<Segment> row) {
        return row.stream().map(Segment::text).collect(Collectors.joining());
    }

    private static List<String> lines(PreviewBox.Rendered box) {
        return box.rows().stream().map(PreviewBoxTest::text).toList();
    }

    private static PreviewBox.Rendered render(String content, int maxLines, int maxWidth) {
        return PreviewBox.render(content, maxLines, 0, PreviewBox.MIN_WIDTH, maxWidth);
    }

    // ── geometry ────────────────────────────────────────────────────────────

    @Test
    void everyRowIsExactlyTheBoxWideSoTheRightEdgeLinesUp() {
        PreviewBox.Rendered box = render("short", 20, 60);

        for (String line : lines(box)) {
            assertEquals(box.width(), FormatUtils.displayWidth(line), line);
        }
    }

    @Test
    void aNarrowPreviewStillClaimsTheSharedMinimumWidth() {
        PreviewBox.Rendered box = render("hi", 20, 100);

        assertEquals(PreviewBox.MIN_WIDTH + 4, box.width(), "content floor is T2g, plus the frame");
    }

    @Test
    void aWidePreviewGrowsUntilItHitsTheMaxWidthCeiling() {
        PreviewBox.Rendered fits = render("x".repeat(50), 20, 100);
        PreviewBox.Rendered capped = render("x".repeat(200), 20, 60);

        assertEquals(54, fits.width(), "content + 4 while it still fits");
        assertEquals(60, capped.width(), "clamped to maxWidth, never past it");
    }

    @Test
    void anImpossiblyNarrowCeilingCollapsesToTheBareFrame() {
        PreviewBox.Rendered box = render("anything at all", 20, 1);

        assertEquals(4, box.width(), "max(4, …) is the floor the weflow tree is missing");
        for (String line : lines(box)) {
            assertEquals(4, FormatUtils.displayWidth(line), line);
        }
    }

    @Test
    void theFrameIsDrawnWithTheBundlesBoxGlyphs() {
        List<String> rows = lines(render("hi", 20, 60));

        assertTrue(rows.getFirst().startsWith("┌"), rows.getFirst());
        assertTrue(rows.getFirst().endsWith("┐"), rows.getFirst());
        assertTrue(rows.get(1).startsWith("│ "), rows.get(1));
        assertTrue(rows.get(1).endsWith(" │"), rows.get(1));
        assertTrue(rows.getLast().startsWith("└"), rows.getLast());
        assertTrue(rows.getLast().endsWith("┘"), rows.getLast());
    }

    // ── overflow ────────────────────────────────────────────────────────────

    @Test
    void aPreviewWithinTheLineBudgetHasNoCutBar() {
        List<String> rows = lines(render("a\n\nb\n\nc", 20, 60));

        assertFalse(rows.stream().anyMatch(row -> row.contains("✂")), rows.toString());
    }

    @Test
    void anOverlongPreviewIsCutAndTheBarCountsTheHiddenLines() {
        String twelveLines = String.join("\n\n", "abcdefghij".split(""));
        PreviewBox.Rendered box = PreviewBox.render(twelveLines, 4, 0, PreviewBox.MIN_WIDTH, 60);
        List<String> rows = lines(box);
        String bar = rows.get(rows.size() - 2);

        assertEquals(4 + 3, rows.size(), "top, four content rows, the bar, and bottom");
        assertTrue(bar.startsWith("├"), bar);
        assertTrue(bar.endsWith("┤"), bar);
        assertTrue(bar.contains("─── ✂ ─── 15 lines hidden "), bar);
        assertEquals(box.width(), FormatUtils.displayWidth(bar));
    }

    @Test
    void aLineTooWideForTheBoxIsWrappedRatherThanTruncated() {
        // The weflow tree omits this wrap entirely; without it the tail of a long line is lost.
        String sentence = "wrap me ".repeat(20).strip();
        List<String> rows = lines(render(sentence, 20, 44));

        assertTrue(rows.size() > 3, "one content row could not hold 160 columns: " + rows.size());
        String joined = rows.subList(1, rows.size() - 1).stream()
            .map(row -> row.substring(2, row.length() - 2).stripTrailing())
            .collect(Collectors.joining(" "));
        assertTrue(joined.endsWith("wrap me"), joined);
    }

    // ── padding ─────────────────────────────────────────────────────────────

    @Test
    void aMinimumHeightIsReachedWithBlankRowsButNeverExceedsTheLineBudget() {
        PreviewBox.Rendered padded = PreviewBox.render("one line", 20, 6, PreviewBox.MIN_WIDTH, 60);
        PreviewBox.Rendered capped = PreviewBox.render("one line", 3, 99, PreviewBox.MIN_WIDTH, 60);

        assertEquals(6 + 2, padded.rows().size());
        assertEquals(3 + 2, capped.rows().size(), "minHeight is clamped by maxLines");
        assertTrue(StringUtils.isBlank(text(padded.rows().get(5)).replace("│", " ")),
            "padding rows carry the frame and nothing else");
    }

    // ── content ─────────────────────────────────────────────────────────────

    @Test
    void anEmptyPreviewFallsBackToTheStandingNotice() {
        assertTrue(text(render("", 20, 60).rows().get(1)).contains(PreviewBox.NO_PREVIEW));
        assertTrue(text(render(null, 20, 60).rows().get(1)).contains(PreviewBox.NO_PREVIEW));
        assertTrue(text(render("   \n ", 20, 60).rows().get(1)).contains(PreviewBox.NO_PREVIEW));
    }

    @Test
    void aWithheldPreviewShowsItsNoticeInFull() {
        List<String> rows = lines(render(QuestionSanitizer.WITHHELD_PREVIEW_NOTICE, 20, 60));
        String body = String.join("", rows.subList(1, rows.size() - 1))
            .replace("│", " ")
            .replaceAll("\\s+", " ")
            .strip();

        assertEquals(QuestionSanitizer.WITHHELD_PREVIEW_NOTICE, body);
    }

    @Test
    void aFencedCodeBlockKeepsTheHighlightersColours() {
        PreviewBox.Rendered box = render("```java\nvar answer = 42;\n```", 20, 60);
        long distinctColours = box.rows().stream()
            .flatMap(List::stream)
            .map(Segment::color)
            .distinct()
            .count();

        assertTrue(distinctColours > 2,
            "frame dim plus at least two syntax colours, saw " + distinctColours);
    }
}
