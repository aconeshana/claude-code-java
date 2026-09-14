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
    void foldsTextOnlyBeyondTheOfficialCharacterThreshold() {
        assertFalse(PromptPasteTextPolicy.shouldFoldIntoChip("a".repeat(800), 0, 50));
        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("a".repeat(801), 0, 50));
    }

    @Test
    void newlineCapFollowsTerminalHeight() {
        // Official: Math.max(0, Math.min(rows - 10, 2)). Verified live against
        // 2.1.236 in tmux — two newlines stay editable at 50 rows but fold at
        // 11, and three newlines fold at 50.
        assertFalse(PromptPasteTextPolicy.shouldFoldIntoChip("short", 2, 50));
        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("short", 3, 50));

        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("short", 2, 11));
        assertFalse(PromptPasteTextPolicy.shouldFoldIntoChip("short", 1, 11));

        assertTrue(PromptPasteTextPolicy.shouldFoldIntoChip("short", 1, 10));
        assertFalse(PromptPasteTextPolicy.shouldFoldIntoChip("short", 0, 10));
    }
}
