package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextBox;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;


class ReadlineEngineTest {

    @Test
    void killToEnd_onlyRemovesTheRestOfTheCurrentLogicalLine() {
        TextBox box = multiline("abc\nDEF\nghi");
        box.setCaretPosition(1, 1);
        AtomicInteger changes = new AtomicInteger();
        ReadlineEngine engine = new ReadlineEngine(box, box::handleKeyStroke, changes::incrementAndGet);

        engine.killToEnd();

        assertEquals("abc\nD\nghi", box.getText());
        assertEquals(1, box.getCaretPosition().getRow());
        assertEquals(1, box.getCaretPosition().getColumn());
        assertEquals(1, changes.get());
    }

    @Test
    void killToStart_onlyRemovesTheCurrentLogicalLinePrefix() {
        TextBox box = multiline("abc\nDEF\nghi");
        box.setCaretPosition(1, 2);
        ReadlineEngine engine = new ReadlineEngine(box, box::handleKeyStroke, () -> {});

        engine.killToStart();

        assertEquals("abc\nF\nghi", box.getText());
        assertEquals(1, box.getCaretPosition().getRow());
        assertEquals(0, box.getCaretPosition().getColumn());
    }

    @Test
    void killAtLogicalLineBoundary_removesOnlyTheNewline() {
        TextBox box = multiline("abc\nDEF");
        box.setCaretPosition(1, 0);
        ReadlineEngine engine = new ReadlineEngine(box, box::handleKeyStroke, () -> {});

        engine.killToStart();

        assertEquals("abcDEF", box.getText());
        assertEquals(3, TextBoxOffsetAdapter.offset(box));
    }

    // ── prevWordBoundary (Alt+B / Ctrl+Left) ────────────────────────────────

    @Test
    void prevWord_fromEndOfWord_landsAtWordStart() {
        // "foo bar|" → caret 7 → back over "bar" → 4
        assertEquals(4, ReadlineEngine.prevWordBoundary("foo bar", 7));
    }

    @Test
    void prevWord_skipsTrailingSeparators_thenWord() {
        // "foo   |" caret at 6 → skip spaces → over "foo" → 0
        assertEquals(0, ReadlineEngine.prevWordBoundary("foo   ", 6));
    }

    @Test
    void prevWord_atStart_isZero() {
        assertEquals(0, ReadlineEngine.prevWordBoundary("foo", 0));
    }

    @Test
    void prevWord_clampsOutOfRangeCaret() {
        assertEquals(0, ReadlineEngine.prevWordBoundary("hi", 99));
    }

    // ── nextWordBoundary (Alt+F / Ctrl+Right) ───────────────────────────────

    @Test
    void nextWord_fromStart_landsAfterFirstWord() {
        // "|foo bar" → over "foo" → 3
        assertEquals(3, ReadlineEngine.nextWordBoundary("foo bar", 0));
    }

    @Test
    void nextWord_skipsLeadingSeparators_thenWord() {
        // "foo| bar" caret 3 → skip space → over "bar" → 7
        assertEquals(7, ReadlineEngine.nextWordBoundary("foo bar", 3));
    }

    @Test
    void nextWord_atEnd_isLength() {
        assertEquals(3, ReadlineEngine.nextWordBoundary("foo", 3));
    }

    // ── wordStartBefore (Ctrl+W / Alt+Backspace) ───────────────────────────
    // NOTE: kill-word uses WHITESPACE boundaries (not isLetterOrDigit), so
    // punctuation is part of a "word" here — distinct from prev/nextWord.
    // The deleted span is [wordStartBefore(text, caret), caret).

    @Test
    void wordStartBefore_deletesWordAndTrailingSpaces() {
        // "foo bar |" caret 8 → skip 1 space → over "bar" → start 4
        assertEquals(4, ReadlineEngine.wordStartBefore("foo bar ", 8));
    }

    @Test
    void wordStartBefore_keepsPunctuationAsWord() {
        // "a.b.c|" caret 5 → no ws → back over "a.b.c" → 0
        assertEquals(0, ReadlineEngine.wordStartBefore("a.b.c", 5));
    }

    @Test
    void wordStartBefore_atStart_isZero() {
        assertEquals(0, ReadlineEngine.wordStartBefore("foo", 0));
    }

    // ── wordEndAfter (Alt+D / Alt+Delete) ───────────────────────────────────
    // The deleted span is [caret, wordEndAfter(text, caret)).

    @Test
    void wordEndAfter_deletesLeadingSpacesAndWord() {
        // "foo bar" caret 3 → skip space → over "bar" → end 7
        assertEquals(7, ReadlineEngine.wordEndAfter("foo bar", 3));
    }

    @Test
    void wordEndAfter_atEnd_isCaret() {
        assertEquals(3, ReadlineEngine.wordEndAfter("foo", 3));
    }

    // ── isKillKey ───────────────────────────────────────────────────────────

    @Test
    void isKillKey_ctrlKUW_true() {
        assertTrue(ReadlineEngine.isKillKey(new KeyStroke((char) 11, false, false)), "Ctrl+K");
        assertTrue(ReadlineEngine.isKillKey(new KeyStroke((char) 21, false, false)), "Ctrl+U");
        assertTrue(ReadlineEngine.isKillKey(new KeyStroke((char) 23, false, false)), "Ctrl+W");
    }

    @Test
    void isKillKey_altBackspaceAndDelete_true() {
        assertTrue(ReadlineEngine.isKillKey(new KeyStroke(KeyType.BACKSPACE, false, true)));
        assertTrue(ReadlineEngine.isKillKey(new KeyStroke(KeyType.DELETE, false, true)));
    }

    @Test
    void isKillKey_plainBackspace_false() {
        assertFalse(ReadlineEngine.isKillKey(new KeyStroke(KeyType.BACKSPACE, false, false)));
    }

    @Test
    void isKillKey_plainChar_false() {
        assertFalse(ReadlineEngine.isKillKey(new KeyStroke('a', false, false)));
    }

    // ── isYankKey ───────────────────────────────────────────────────────────

    @Test
    void isYankKey_ctrlY_true() {
        assertTrue(ReadlineEngine.isYankKey(new KeyStroke((char) 25, false, false)));
    }

    @Test
    void isYankKey_altY_true() {
        assertTrue(ReadlineEngine.isYankKey(new KeyStroke('y', false, true)));
        assertTrue(ReadlineEngine.isYankKey(new KeyStroke('Y', false, true)));
    }

    @Test
    void isYankKey_plainY_false() {
        assertFalse(ReadlineEngine.isYankKey(new KeyStroke('y', false, false)));
    }

    private static TextBox multiline(String text) {
        TextBox box = new TextBox(new TerminalSize(20, 4), TextBox.Style.MULTI_LINE);
        box.setText(text);
        return box;
    }
}
