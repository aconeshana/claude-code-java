package com.claudecode.ui.lanterna.dialog;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior contract for text wrapping shared by inline dialogs. */
class DialogTextTest {

    @Test
    void wrapsAtWordBoundariesWithoutExceedingWidthWhenPossible() {
        assertEquals(
            List.of("one two", "three", "four"),
            DialogText.wrapWords("one two three four", 8));
    }

    @Test
    void preservesExplicitLineBreaksEmptyParagraphsAndTrailingBreaks() {
        assertEquals(
            List.of("alpha beta", "", "gamma", ""),
            DialogText.wrapWords("alpha beta\n\ngamma\n", 40));
    }

    @Test
    void leavesWordsLongerThanTheWidthIntact() {
        assertEquals(
            List.of("extraordinary", "word"),
            DialogText.wrapWords("extraordinary word", 5));
    }

    @Test
    void returnsAnImmutableResult() {
        List<String> lines = DialogText.wrapWords("one two three", 7);

        assertThrows(UnsupportedOperationException.class,
            () -> lines.add("mutated"));
    }

    @Test
    void nullAndEmptyTextProduceNoLayoutRows() {
        assertEquals(List.of(), DialogText.wrapWords(null, 10));
        assertEquals(List.of(), DialogText.wrapWords("", 10));
    }

    @Test
    void nonPositiveWidthIsRejectedForNonEmptyText() {
        assertThrows(IllegalArgumentException.class,
            () -> DialogText.wrapWords("text", 0));
        assertThrows(IllegalArgumentException.class,
            () -> DialogText.wrapWords("text", -1));
    }
}
