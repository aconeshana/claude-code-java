package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellOutputTruncatorTest {

    @Test
    void foldsByVisualRowsInsteadOfRawNewlines() {
        var rendered = ShellOutputTruncator.truncate("x".repeat(50), 20);

        assertEquals(List.of("x".repeat(10), "x".repeat(10), "x".repeat(10)),
            rendered.visibleRows());
        assertEquals(2, rendered.remainingRows());
    }

    @Test
    void showsTheFourthVisualRowInsteadOfAPlusOneHint() {
        var rendered = ShellOutputTruncator.truncate("x".repeat(40), 20);

        assertEquals(List.of(
            "x".repeat(10), "x".repeat(10), "x".repeat(10), "x".repeat(10)),
            rendered.visibleRows());
        assertEquals(0, rendered.remainingRows());
    }

    @Test
    void countsWideGraphemesUsingTerminalColumns() {
        var rendered = ShellOutputTruncator.truncate("中".repeat(11), 14);

        assertEquals(List.of("中".repeat(5), "中".repeat(5), "中"), rendered.visibleRows());
        assertEquals(0, rendered.remainingRows());
    }

    @Test
    void preservesDeclaredNewlinesAndDropsTrailingBlankRows() {
        var rendered = ShellOutputTruncator.truncate("one\ntwo\nthree\nfour\n", 80);

        assertEquals(List.of("one", "two", "three", "four"), rendered.visibleRows());
        assertEquals(0, rendered.remainingRows());
    }

    @Test
    void estimatesHugeTailsWithoutChangingTheVisiblePrefix() {
        var rendered = ShellOutputTruncator.truncate("x".repeat(10_000), 110);

        assertEquals(List.of("x".repeat(100), "x".repeat(100), "x".repeat(100)),
            rendered.visibleRows());
        assertEquals(97, rendered.remainingRows());
    }

    @Test
    void emptyAndWhitespaceOnlyOutputHasNoRows() {
        assertEquals(new ShellOutputTruncator.TruncatedOutput(List.of(), 0),
            ShellOutputTruncator.truncate(" \n\t", 80));
    }
}
