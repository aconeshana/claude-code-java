package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptPasteTextPolicyTest {

    @Test
    void normalizesAnsiLineEndingsAndTabs() {
        assertEquals("", PromptPasteTextPolicy.normalize(null));
        assertEquals("red\nnext\n    indented",
            PromptPasteTextPolicy.normalize(
                "\u001B[31mred\u001B[0m\r\nnext\r\tindented"));
    }

    @Test
    void foldsTextOnlyBeyondTheExistingCharacterOrLineThreshold() {
        assertFalse(PromptPasteTextPolicy.shouldFoldIntoChip("a".repeat(800), 0));
        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("a".repeat(801), 0));
        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("short", 1));
    }

    @Test
    void detectsOnlyTheExistingLargeUnbracketedPasteShapes() {
        assertFalse(PromptPasteTextPolicy.looksLikeUnbracketedPaste("a".repeat(800)));
        assertTrue(PromptPasteTextPolicy.looksLikeUnbracketedPaste("a".repeat(801)));
        assertFalse(PromptPasteTextPolicy.looksLikeUnbracketedPaste(
            String.join("\n", java.util.Collections.nCopies(7, "short"))));
        assertTrue(PromptPasteTextPolicy.looksLikeUnbracketedPaste(
            String.join("\n", java.util.Collections.nCopies(7, "x".repeat(30)))));
    }
}
