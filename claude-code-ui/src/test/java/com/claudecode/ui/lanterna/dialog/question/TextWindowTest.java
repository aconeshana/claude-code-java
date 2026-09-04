package com.claudecode.ui.lanterna.dialog.question;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The cursor-anchored scroll window, measured in display columns rather than chars. */
class TextWindowTest {

    @Test
    void textWindowKeepsCursorVisibleInLongText() {
        // short text: everything visible
        var shortWindow = TextWindow.of("abc", 3, 10);
        assertEquals(0, shortWindow.start());
        assertEquals("abc", shortWindow.visible());
        assertEquals(3, shortWindow.cursorColumn());

        // long pasted text, cursor at end (post-paste state): tail visible, not the prefix
        var end = TextWindow.of("0123456789", 10, 5);
        assertEquals("56789", end.visible());
        assertEquals(5, end.cursorColumn());

        // Ctrl+A: window snaps back to the start
        var home = TextWindow.of("0123456789", 0, 5);
        assertEquals("01234", home.visible());
        assertEquals(0, home.cursorColumn());

        // mid-text backspace target stays on screen
        var mid = TextWindow.of("0123456789", 8, 5);
        assertEquals("45678", mid.visible());
        assertEquals(4, mid.cursorColumn());

        // double-width (CJK) text: the window measures display columns, not chars —
        // 10 chars = 20 columns, so a 6-column window shows only the last 3 chars
        var cjk = TextWindow.of("一二三四五六七八九十", 10, 6);
        assertEquals("八九十", cjk.visible());
        assertEquals(3, cjk.cursorColumn());

        // mixed narrow/wide text scrolls on column boundaries
        var mixed = TextWindow.of("ab天地cd", 4, 6);
        assertEquals("b天地c", mixed.visible());
        assertEquals(3, mixed.cursorColumn());
    }
}
