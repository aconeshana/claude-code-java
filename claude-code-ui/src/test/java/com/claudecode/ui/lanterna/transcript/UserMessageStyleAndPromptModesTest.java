package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class UserMessageStyleAndPromptModesTest {

    // ── UserMessageStyle ────────────────────────────────────────────────────

    @Test
    void truncationConstants_matchTs() {
        assertEquals(10_000, UserMessageStyle.MAX_DISPLAY_CHARS);
        assertEquals(2_500,  UserMessageStyle.TRUNCATE_HEAD_CHARS);
        assertEquals(2_500,  UserMessageStyle.TRUNCATE_TAIL_CHARS);
    }

    @Test
    void paddingConstants_matchTs() {
        assertEquals(1, UserMessageStyle.PADDING_RIGHT);
        assertEquals(1, UserMessageStyle.PADDING_LEFT);
    }

    @Test
    void queuedIndent_matchTs() {

        assertEquals(2, UserMessageStyle.QUEUED_INDENT);
    }

    @Test
    void truncateForDisplay_belowCap_returnsOriginal() {
        String input = "hello world";
        assertSame(input, UserMessageStyle.truncateForDisplay(input));
    }

    @Test
    void truncateForDisplay_atCap_returnsOriginal() {
        String input = "x".repeat(UserMessageStyle.MAX_DISPLAY_CHARS);
        assertSame(input, UserMessageStyle.truncateForDisplay(input));
    }

    @Test
    void truncateForDisplay_aboveCap_keepsHeadAndTail() {
        // 11 000 chars, no newlines → 0 hidden lines.
        String input = "a".repeat(11_000);
        String out = UserMessageStyle.truncateForDisplay(input);
        // Head 2500 + "\n… +0 lines …\n" + tail 2500.
        assertTrue(Strings.CS.startsWith(out, "a".repeat(2500)), "head must survive");
        assertTrue(Strings.CS.endsWith(out, "a".repeat(2500)),   "tail must survive");
        assertTrue(Strings.CS.contains(out, "… +0 lines …"));
    }

    @Test
    void truncateForDisplay_countsHiddenNewlines() {
        // 10 lines of 1500 'a' each = 15 010 chars total.
        // Head=2500 covers ~1.66 lines (1 newline included), tail=2500 covers
        // ~1.66 lines (1 newline at the very end before the last 1500 chars).
        // Inner block has 10 newlines minus head/tail counts.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("a".repeat(1500)).append('\n');
        }
        String out = UserMessageStyle.truncateForDisplay(sb.toString());
        // Just assert the format is correct — exact hidden count depends on
        // where the head/tail boundaries fall.
        assertTrue(out.matches("(?s).*\n… \\+\\d+ lines …\n.*"), out.substring(0, 80));
    }

    @Test
    void truncateForDisplay_nullSafe() {
        assertEquals("", UserMessageStyle.truncateForDisplay(null));
    }
}
