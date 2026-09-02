package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;

class SearchInputTest {

    private static SearchInput input() {
        return new SearchInput(new SearchInput.Listener() {
            @Override public void onExit() {}
            @Override public void onChange() {}
        });
    }

    @Test
    void singleLinePasteKeepsOnlyTheFirstLogicalLineLikeReleasedEH() {
        SearchInput input = input();

        input.handleKey(new PasteKeyStroke("first\nsecond"));

        assertEquals("first", input.query());
    }

    @Test
    void metaWordMotionUsesWordLikeSegmentsInsteadOfWholeWhitespaceTokens() {
        SearchInput input = input();
        input.reset("foo-bar baz");

        input.handleKey(new KeyStroke('b', false, true));
        assertEquals(8, input.cursorOffset());
        input.handleKey(new KeyStroke('b', false, true));
        assertEquals(4, input.cursorOffset(),
            "released Cursor.prevWord treats foo and bar as separate word-like segments");
    }

    @Test
    void unicodeWordSegmentationKeepsAnApostropheWordTogether() {
        SearchInput input = input();
        input.reset("don't stop");

        input.handleKey(new KeyStroke('b', false, true));
        assertEquals(6, input.cursorOffset());
        input.handleKey(new KeyStroke('b', false, true));

        assertEquals(0, input.cursorOffset(),
            "released Cursor.prevWord uses Intl.Segmenter rather than splitting don't at punctuation");
    }

    @Test
    void metaYReplacesThePreviousYankWithTheNextKillRingEntry() {
        KillRing.INSTANCE.push("oldest", KillRing.Direction.APPEND);
        KillRing.INSTANCE.resetAccumulation();
        KillRing.INSTANCE.push("older", KillRing.Direction.APPEND);
        KillRing.INSTANCE.resetAccumulation();
        KillRing.INSTANCE.push("newer", KillRing.Direction.APPEND);
        SearchInput input = input();

        input.handleKey(new KeyStroke('y', true, false));
        assertEquals("newer", input.query());
        input.handleKey(new KeyStroke('y', false, true));
        assertEquals("older", input.query());
        input.handleKey(new KeyStroke('y', false, true));
        assertEquals("oldest", input.query());
    }

    @Test
    void cursorMovementAndDeletionDoNotSplitEmojiGraphemes() {
        SearchInput input = input();
        input.reset("A👨‍👩‍👧‍👦B");

        input.handleKey(new KeyStroke(KeyType.ARROW_LEFT));
        input.handleKey(new KeyStroke(KeyType.ARROW_LEFT));
        assertEquals(1, input.cursorOffset());
        input.handleKey(new KeyStroke('d', true, false));

        assertEquals("AB", input.query());
    }
}
