package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list card's pure layout helpers: 197's glyphs, its right-aligned option numbering, and the
 * two-level description wrap Ink gets for free from {@code wrap="wrap"}.
 */
class ListQuestionViewTest {

    @Test
    void selectionGlyphsMatchClaudeCode197() {
        assertEquals("[ ]", ListQuestionView.multiSelectMarker(false));
        assertEquals("[✓]", ListQuestionView.multiSelectMarker(true));
        assertEquals("1. ", ListQuestionView.optionIndex(0, 4));
        assertEquals("10. ", ListQuestionView.optionIndex(9, 12));
        assertEquals(" 1. ", ListQuestionView.optionIndex(0, 12));
    }

    @Test
    void descriptionWrapsAtWordBoundariesLikeReleased197() {
        // Ink's default wrap="wrap" keeps long descriptions visible by wrapping
        // instead of clipping (released 2.1.197 behavior in narrow terminals).
        List<String> lines = ListQuestionView.descriptionLines(
            "alpha beta gamma delta epsilon zeta", 12);
        assertEquals(List.of("alpha beta", "gamma delta", "epsilon zeta"), lines);
    }

    @Test
    void descriptionHardWrapsOverlongWords() {
        List<String> lines = ListQuestionView.descriptionLines("abcdefghijklmnop", 6);
        assertEquals(List.of("abcdef", "ghijkl", "mnop"), lines);
    }
}
