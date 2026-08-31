package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptTextLayoutTest {

    @Test
    void emptyTextAndInvalidWidthStillProduceOneCursorSafeLine() {
        PromptTextLayout layout = PromptTextLayout.create(null, 0);

        assertEquals("", layout.text());
        assertEquals(1, layout.wrapColumns());
        assertEquals(1, layout.lineCount());
        assertEquals("", layout.lines().getFirst().text());
        assertEquals(new PromptTextLayout.Position(0, 0), layout.positionAt(0));
        assertEquals(0, layout.offsetAt(new PromptTextLayout.Position(10, 10)));
    }

    @Test
    void reservesOneColumnForTheCursorAndWrapsAtWords() {
        PromptTextLayout layout = PromptTextLayout.create("alpha beta", 7);

        assertEquals(6, layout.wrapColumns());
        assertEquals(List.of("alpha ", "beta"), layout.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());

        PromptTextLayout boundary = PromptTextLayout.create("abcde", 5);
        assertEquals(List.of("abcd", "e"), boundary.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());
    }

    @Test
    void hardWrapsLongWordsWithoutSplittingGraphemes() {
        PromptTextLayout layout = PromptTextLayout.create("中文ABC", 5);

        assertEquals(List.of("中文", "ABC"), layout.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());

        PromptTextLayout emoji = PromptTextLayout.create("👨‍👩‍👧‍👦ABC", 4);
        assertEquals(List.of("👨‍👩‍👧‍👦A", "BC"), emoji.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());
    }

    @Test
    void preservesExplicitNewlinesAndBlankLines() {
        PromptTextLayout layout = PromptTextLayout.create("ab\n\ncd", 10);

        assertEquals(List.of("ab", "", "cd"), layout.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());
        assertTrue(layout.lines().get(0).endsWithNewline());
        assertTrue(layout.lines().get(1).endsWithNewline());
        assertTrue(layout.lines().get(2).precededByNewline());

        PromptTextLayout trailing = PromptTextLayout.create("a\n", 10);
        assertEquals(List.of("a", ""), trailing.lines().stream()
            .map(PromptTextLayout.VisualLine::text).toList());
        assertTrue(trailing.lines().getFirst().endsWithNewline());
        assertTrue(trailing.lines().getLast().precededByNewline());
    }

    @Test
    void preservesTrimFalseWhitespaceAndMonotonicSourceOffsets() {
        PromptTextLayout layout = PromptTextLayout.create("repeat repeat repeat", 7);

        assertEquals(List.of("repeat", " ", "repeat", " ", "repeat"),
            layout.lines().stream().map(PromptTextLayout.VisualLine::text).toList());
        assertEquals(List.of(0, 6, 7, 13, 14), layout.lines().stream()
            .map(PromptTextLayout.VisualLine::startOffset).toList());
        assertEquals(List.of("repeat", "", "repeat", "", "repeat"),
            layout.lines().stream().map(PromptTextLayout.VisualLine::displayText).toList());
        assertEquals(new PromptTextLayout.Position(1, 0), layout.positionAt(6));
        assertEquals(7, layout.offsetAt(new PromptTextLayout.Position(1, 0)));
    }

    @Test
    void mapsAbsoluteOffsetsToVisualPositionsAndBack() {
        PromptTextLayout layout = PromptTextLayout.create("alpha beta", 7);

        assertEquals(new PromptTextLayout.Position(0, 0), layout.positionAt(0));
        assertEquals(new PromptTextLayout.Position(0, 5), layout.positionAt(5));
        assertEquals(new PromptTextLayout.Position(1, 0), layout.positionAt(6));
        assertEquals(new PromptTextLayout.Position(1, 4), layout.positionAt(10));
        assertEquals(3, layout.offsetAt(new PromptTextLayout.Position(0, 3)));
        assertEquals(9, layout.offsetAt(new PromptTextLayout.Position(1, 3)));
    }

    @Test
    void visualColumnsUseTerminalWidthRatherThanUtf16Length() {
        PromptTextLayout layout = PromptTextLayout.create("中文A", 5);

        assertEquals(new PromptTextLayout.Position(0, 2), layout.positionAt(1));
        assertEquals(new PromptTextLayout.Position(1, 0), layout.positionAt(2));
        assertEquals(new PromptTextLayout.Position(1, 1), layout.positionAt(3));
    }

    @Test
    void verticalMovementPreservesDisplayColumnAndClampsAtLineEnd() {
        PromptTextLayout layout = PromptTextLayout.create("alpha beta", 7);

        assertEquals(3, layout.moveVertically(9, -1));
        assertEquals(10, layout.moveVertically(5, 1));
        assertEquals(9, layout.moveVertically(9, 1));
    }

    @Test
    void changingWidthReflowsWithoutChangingNormalizedText() {
        PromptTextLayout wide = PromptTextLayout.create("e\u0301中文AB", 8);
        PromptTextLayout narrow = PromptTextLayout.create("e\u0301中文AB", 5);

        assertEquals("é中文AB", wide.text());
        assertEquals(wide.text(), narrow.text());
        assertEquals(1, wide.lineCount());
        assertEquals(2, narrow.lineCount());
    }
}
