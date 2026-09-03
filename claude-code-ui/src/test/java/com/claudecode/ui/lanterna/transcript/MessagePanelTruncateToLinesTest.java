package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The line budget behind the collapsed group's thinking-summary row: truncation has to agree with
 * the wrapper that will render the result, or the row silently grows past its allowance.
 */
class MessagePanelTruncateToLinesTest {

    private static String words(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) text.append(' ');
            text.append("word").append(i);
        }
        return text.toString();
    }

    @Test
    void textThatAlreadyFitsIsReturnedUntouched() {
        String text = words(4);
        assertSame(text, MessagePanel.truncateToLines(text, 40, 3));
    }

    @Test
    void overlongTextIsClampedToTheLineBudget() {
        String truncated = MessagePanel.truncateToLines(words(60), 20, 3);

        assertTrue(truncated.endsWith("…"), truncated);
        assertTrue(MessagePanel.wordWrapAtBoundaries(truncated, 20).size() <= 3, truncated);
        // Shrinking stops as soon as the ellipsis fits, so the budget stays nearly full.
        assertTrue(truncated.startsWith("word0 word1 word2"), truncated);
    }

    @Test
    void theEllipsisNeverSpillsOntoAnExtraLine() {
        // Exercises the shrink loop: every prefix length is a candidate for pushing "…" over.
        for (int wordCount = 10; wordCount <= 40; wordCount++) {
            String truncated = MessagePanel.truncateToLines(words(wordCount), 17, 2);
            assertTrue(MessagePanel.wordWrapAtBoundaries(truncated, 17).size() <= 2,
                wordCount + " words wrapped to more than 2 lines: " + truncated);
        }
    }

    @Test
    void aSurrogatePairIsNeverCutInHalf() {
        String text = ("🎉🎉🎉🎉 ").repeat(30);
        for (int width = 8; width <= 24; width++) {
            String truncated = MessagePanel.truncateToLines(text, width, 2);
            assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 2)),
                "width " + width + " left a dangling high surrogate: " + truncated);
        }
    }

    @Test
    void degenerateBoundsAreReturnedUnchanged() {
        assertEquals("", MessagePanel.truncateToLines("", 20, 3));
        assertEquals("abc", MessagePanel.truncateToLines("abc", 0, 3));
        assertEquals("abc", MessagePanel.truncateToLines("abc", 20, 0));
    }
}
