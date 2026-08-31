package com.claudecode.ui.vim;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VimStateMachineTest {

    private VimStateMachine vim;

    @BeforeEach
    void setUp() {
        vim = new VimStateMachine();
    }

    @Test
    void startsInInsertMode() {
        assertEquals(VimMode.INSERT, vim.getMode());
    }

    @Test
    void escSwitchesToNormalMode() {
        VimAction action = vim.processKey((char) 27); // ESC
        assertEquals(VimMode.NORMAL, vim.getMode());
        assertEquals(VimAction.Type.MODE_CHANGE, action.type());
    }

    @Test
    void iSwitchesToInsertMode() {
        vim.processKey((char) 27); // ESC -> NORMAL
        VimAction action = vim.processKey('i');
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals(VimAction.Type.MODE_CHANGE, action.type());
    }

    @Test
    void insertModeTypesCharacters() {
        vim.processKey('h');
        vim.processKey('e');
        vim.processKey('l');
        vim.processKey('l');
        vim.processKey('o');
        assertEquals("hello", vim.getBuffer());
        assertEquals(5, vim.getCursor());
    }

    @Test
    void motionH_movesLeft() {
        vim.setBuffer("hello");
        vim.processKey((char) 27); // ESC -> NORMAL
        // cursor should be at end-1 after ESC
        int startCursor = vim.getCursor();
        vim.processKey('h');
        assertTrue(vim.getCursor() < startCursor || startCursor == 0);
    }

    @Test
    void motionL_movesRight() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0'); // go to start
        assertEquals(0, vim.getCursor());
        vim.processKey('l');
        assertEquals(1, vim.getCursor());
    }

    @Test
    void motionW_movesToNextWord() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('w');
        assertEquals(6, vim.getCursor()); // 'w' in "world"
    }

    @Test
    void motionB_movesToPrevWord() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('$'); // end
        vim.processKey('b');
        assertEquals(6, vim.getCursor()); // 'w' in "world"
    }

    @Test
    void motion0_movesToLineStart() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0');
        assertEquals(0, vim.getCursor());
    }

    @Test
    void motionDollar_movesToLineEnd() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('$');
        assertEquals(4, vim.getCursor());
    }

    @Test
    void operatorDD_deletesWholeLine() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('d');
        vim.processKey('d');
        assertEquals("", vim.getBuffer());
        assertEquals("hello\n", vim.getYankRegister()); // linewise register ends with '\n'
    }

    @Test
    void operatorYY_yanksWholeLine() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('y');
        vim.processKey('y');
        assertEquals("hello", vim.getBuffer()); // unchanged
        assertEquals("hello\n", vim.getYankRegister()); // linewise register ends with '\n'
    }

    @Test
    void operatorYY_movesCursorToLogicalLineStart() {
        vim.setBuffer("first\nsecond");
        vim.processKey((char) 27);
        vim.setCursor(9);

        vim.processKey('y');
        vim.processKey('y');

        assertEquals("first\nsecond", vim.getBuffer());
        assertEquals(6, vim.getCursor());
        assertEquals("second\n", vim.getYankRegister());
    }

    @Test
    void operatorDD_middleLineDeletesExactlyThatLine() {
        vim.setBuffer("first\nsecond\nthird");
        vim.processKey((char) 27);
        vim.setCursor(8);

        vim.processKey('d');
        vim.processKey('d');

        assertEquals("first\nthird", vim.getBuffer());
        assertEquals(6, vim.getCursor());
        assertEquals("second\n", vim.getYankRegister());
    }

    @Test
    void operatorCC_singleLineClearsBufferAndEntersInsertMode() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);

        vim.processKey('c');
        vim.processKey('c');

        assertEquals("", vim.getBuffer());
        assertEquals(0, vim.getCursor());
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals("hello\n", vim.getYankRegister());
    }

    @Test
    void operatorCC_middleLineReplacesItWithEmptyLineAndEntersInsertMode() {
        vim.setBuffer("first\nsecond\nthird");
        vim.processKey((char) 27);
        vim.setCursor(8);

        vim.processKey('c');
        vim.processKey('c');

        assertEquals("first\n\nthird", vim.getBuffer());
        assertEquals(6, vim.getCursor());
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals("second\n", vim.getYankRegister());
    }

    @Test
    void operatorCC_lastLineLeavesEmptyLastLineAndInsertCursorAtEnd() {
        vim.setBuffer("first\nsecond");
        vim.processKey((char) 27);
        vim.setCursor(8);

        vim.processKey('c');
        vim.processKey('c');

        assertEquals("first\n", vim.getBuffer());
        assertEquals(6, vim.getCursor());
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals("second\n", vim.getYankRegister());
    }

    @Test
    void operatorDW_deletesToNextWord() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('d');
        vim.processKey('w');
        // Should delete "hello " or "hello" + motion
        assertTrue(vim.getBuffer().length() < 11);
    }

    @Test
    void xDeletesCharUnderCursor() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('x');
        assertEquals("ello", vim.getBuffer());
    }

    @Test
    void pPastesAfterCursor() {
        vim.setBuffer("hllo");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('x'); // delete 'h', yank it
        vim.processKey('p'); // paste after cursor
        assertTrue(Strings.CS.contains(vim.getBuffer(), "h"));
    }

    @Test
    void aSwitchesToInsertAfterCursor() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0');
        int cursorBefore = vim.getCursor();
        vim.processKey('a');
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals(cursorBefore + 1, vim.getCursor());
    }

    @Test
    void capitalA_movesToEndAndInserts() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('A');
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals(5, vim.getCursor());
    }

    @Test
    void findF_movesToCharForward() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        VimAction action = vim.processKey('f');
        assertEquals(VimAction.Type.WAITING_FOR_CHAR, action.type());
        vim.processFindChar('w');
        assertEquals(6, vim.getCursor());
    }

    @Test
    void findT_movesBeforeCharForward() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('t');
        vim.processFindChar('w');
        assertEquals(5, vim.getCursor());
    }

    @Test
    void reset_clearsState() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.reset();
        assertEquals(VimMode.INSERT, vim.getMode());
        assertEquals("", vim.getBuffer());
        assertEquals(0, vim.getCursor());
    }

    @Test
    void motionCaret_movesToFirstNonBlank() {
        vim.setBuffer("  hello");
        vim.processKey((char) 27);
        vim.processKey('^');
        assertEquals(2, vim.getCursor());
    }

    @Test
    void changeCW_deletesWordAndEntersInsert() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('c');
        vim.processKey('w');
        assertEquals(VimMode.INSERT, vim.getMode());
        assertTrue(vim.getBuffer().length() < 11);
    }

    @Test
    void wordMotionHelpers() {
        assertEquals(6, VimStateMachine.nextWordStart("hello world", 0));
        assertEquals(0, VimStateMachine.prevWordStart("hello world", 6));
        assertEquals(4, VimStateMachine.wordEnd("hello", 0));
        assertEquals(4, VimStateMachine.wordEnd("a !!!", 0));
        assertTrue(VimStateMachine.isWordChar('a'));
        assertTrue(VimStateMachine.isWordChar('_'));
        assertFalse(VimStateMachine.isWordChar(' '));
    }

    // ---- Text object (i/a) end-to-end via the WAITING_FOR_CHAR handshake ----

    @Test
    void textObjectInnerQuote_di() {
        vim.setBuffer("say \"hi\" there");
        vim.processKey((char) 27);      // -> NORMAL
        vim.setCursor(6);               // inside the quotes
        vim.processKey('d');            // operator pending
        vim.processKey('i');            // scope (waiting for target)
        vim.processKey('"');            // target -> deletes inner content
        assertEquals("say \"\" there", vim.getBuffer());
        assertEquals("hi", vim.getYankRegister());
        assertEquals(VimMode.NORMAL, vim.getMode());
    }

    @Test
    void textObjectAroundParen_da() {
        vim.setBuffer("foo(bar)baz");
        vim.processKey((char) 27);
        vim.setCursor(4);               // inside the parens
        vim.processKey('d');            // operator pending
        vim.processKey('a');            // scope (waiting for target)
        vim.processKey('(');            // target -> deletes parens + content
        assertEquals("foobaz", vim.getBuffer());
        assertEquals("(bar)", vim.getYankRegister());
    }

    @Test
    void textObjectWord_di() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.setCursor(2);               // inside the word
        vim.processKey('d');            // operator pending
        vim.processKey('i');            // scope
        vim.processKey('w');            // target -> deletes the word
        assertEquals(" world", vim.getBuffer());
        assertEquals("hello", vim.getYankRegister());
    }

    @Test
    void textObjectCountDoesNotLeakToNextCommand() {
        // Regression for double-check Bug 2: a count prefix on a text-object
        // command (d2iw) must not leak pendingCount into the following command.
        vim.setBuffer("one two three four");
        vim.processKey((char) 27);
        vim.setCursor(1);               // inside "one"
        vim.processKey('2');            // count prefix (leak candidate)
        vim.processKey('d');            // operator pending
        vim.processKey('i');            // scope
        vim.processKey('w');            // target -> deletes "one" (count ignored)
        assertEquals(" two three four", vim.getBuffer());
        int afterDeleteCursor = vim.getCursor();
        // Next command is a plain `l` (right). With the leak it would run as `2l`.
        vim.processKey('l');
        assertEquals(afterDeleteCursor + 1, vim.getCursor());
    }

    // ---- Find (f/t) + repeat (;) end-to-end via the handshake ----

    @Test
    void findRepeatSemicolon() {
        vim.setBuffer("a b a b");
        vim.processKey((char) 27);
        vim.processKey('0');
        VimAction first = vim.processKey('f');   // waiting for char
        assertEquals(VimAction.Type.WAITING_FOR_CHAR, first.type());
        vim.processKey('b');                      // -> cursor 2
        assertEquals(2, vim.getCursor());
        vim.processKey(';');                      // repeat find -> cursor 6
        assertEquals(6, vim.getCursor());
    }

    @Test
    void findCancelWithEsc() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('f');                       // pending find
        VimAction cancel = vim.processKey((char) 27); // ESC cancels
        assertEquals(VimAction.Type.NONE, cancel.type());
        assertEquals(VimMode.NORMAL, vim.getMode());
        assertEquals("hello", vim.getBuffer());
        assertEquals(0, vim.getCursor());
    }

    @Test
    void textObjectCancelWithEsc() {
        vim.setBuffer("hello");
        vim.processKey((char) 27);
        vim.processKey('d');                       // operator pending
        vim.processKey('i');                       // scope (waiting for target)
        VimAction cancel = vim.processKey((char) 27); // ESC cancels
        assertEquals(VimAction.Type.NONE, cancel.type());
        assertEquals(VimMode.NORMAL, vim.getMode());
        // Operator must not be stuck: a following motion leaves the buffer intact.
        vim.processKey('w');
        assertEquals("hello", vim.getBuffer());
    }

    // ---- operator + find (d f x / c t " / y F x) ----

    @Test
    void operatorFind_df_deletesToChar() {
        vim.setBuffer("abcXdef");
        vim.processKey((char) 27);
        vim.processKey('d');            // operator pending
        vim.processKey('f');            // find
        vim.processKey('X');            // target -> delete [0,4) inclusive
        assertEquals("def", vim.getBuffer());
        assertEquals("abcX", vim.getYankRegister());
    }

    @Test
    void operatorFind_cf_changesToChar() {
        vim.setBuffer("abcXdef");
        vim.processKey((char) 27);
        vim.processKey('c');
        vim.processKey('f');
        vim.processKey('X');
        assertEquals("def", vim.getBuffer());
        assertEquals(VimMode.INSERT, vim.getMode());
    }

    @Test
    void operatorFind_yf_yanksToChar() {
        vim.setBuffer("abcXdef");
        vim.processKey((char) 27);
        vim.processKey('y');
        vim.processKey('f');
        vim.processKey('X');
        assertEquals("abcXdef", vim.getBuffer());
        assertEquals("abcX", vim.getYankRegister());
    }

    // ---- nested delimiter matching (depth-aware) ----

    @Test
    void textObjectNestedParen_di_innerMost() {
        vim.setBuffer("foo(bar(baz)qux)");
        vim.processKey((char) 27);
        vim.setCursor(9);               // inside "baz"
        vim.processKey('d');
        vim.processKey('i');
        vim.processKey('(');            // should match inner (baz), not (bar(baz)qux)
        assertEquals("foo(bar()qux)", vim.getBuffer());
    }

    // ---- punctuation / whitespace word objects ----

    @Test
    void textObjectPunctuation_di() {
        vim.setBuffer("foo(bar)baz");
        vim.processKey((char) 27);
        vim.setCursor(3);               // on '('
        vim.processKey('d');
        vim.processKey('i');
        vim.processKey('w');            // selects the punctuation run "("
        assertEquals("foobar)baz", vim.getBuffer());
    }

    @Test
    void textObjectWhitespace_di() {
        vim.setBuffer("foo   bar");
        vim.processKey((char) 27);
        vim.setCursor(4);               // on a space
        vim.processKey('d');
        vim.processKey('i');
        vim.processKey('w');            // selects the whitespace run
        assertEquals("foobar", vim.getBuffer());
    }

    // ---- additional text-object targets (big word / backtick / angle) ----

    @Test
    void textObjectBigWord_di() {
        vim.setBuffer("foo bar(baz)");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('d');
        vim.processKey('i');
        vim.processKey('W');            // WORD = non-whitespace run
        assertEquals(" bar(baz)", vim.getBuffer());
    }

    @Test
    void textObjectBacktick_di() {
        vim.setBuffer("say `hi` there");
        vim.processKey((char) 27);
        vim.setCursor(6);               // inside backticks
        vim.processKey('d');
        vim.processKey('i');
        vim.processKey('`');
        assertEquals("say `` there", vim.getBuffer());
        assertEquals("hi", vim.getYankRegister());
    }

    @Test
    void textObjectAngle_da() {
        vim.setBuffer("foo<bar>baz");
        vim.processKey((char) 27);
        vim.setCursor(5);               // inside angle
        vim.processKey('d');
        vim.processKey('a');
        vim.processKey('<');
        assertEquals("foobaz", vim.getBuffer());
    }

    // ---- count prefix on operator (d3w / 3dw / d12w) ----

    @Test
    void operatorCount_d3w() {
        vim.setBuffer("one two three four");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('d');
        vim.processKey('3');
        vim.processKey('w');
        assertEquals("four", vim.getBuffer());
    }

    @Test
    void operatorCount_3dw() {
        vim.setBuffer("one two three four");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('3');
        vim.processKey('d');
        vim.processKey('w');
        assertEquals("four", vim.getBuffer());
    }

    // ---- '0' is line-start, not a count prefix ----

    @Test
    void zeroIsLineStartNotCount() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.setCursor(6);
        vim.processKey('0');            // should jump to line start
        assertEquals(0, vim.getCursor());
    }



    @Test
    void motionW_movesToNextBigWord() {
        vim.setBuffer("foo bar(baz)");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('W');
        assertEquals(4, vim.getCursor()); // start of "bar(baz)"
    }

    @Test
    void motionB_movesToPrevBigWord() {
        vim.setBuffer("foo bar(baz)");
        vim.processKey((char) 27);
        vim.processKey('$');
        vim.processKey('B');
        assertEquals(4, vim.getCursor()); // start of "bar(baz)" (the big word under cursor)
    }

    @Test
    void motionE_movesToEndOfBigWord() {
        vim.setBuffer("foo bar");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('E');
        assertEquals(2, vim.getCursor()); // end of "foo"
    }



    @Test
    void motionG_movesToLastLine() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('G');
        assertEquals(4, vim.getCursor()); // start of "c"
    }

    @Test
    void motionGG_movesToFirstLine() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.setCursor(5);                 // on last line
        vim.processKey("gg".toCharArray()[0]); // 'g'
        vim.processKey("gg".toCharArray()[1]); // 'g' -> first line
        assertEquals(0, vim.getCursor());
    }

    @Test
    void motionJ_movesDownAcrossLines() {
        vim.setBuffer("abc\ndef");
        vim.processKey((char) 27);
        vim.processKey('0');             // col 0, line 0
        vim.processKey('j');
        assertEquals(4, vim.getCursor()); // col 0, line 1
    }



    @Test
    void operatorDJ_deletesTwoLines() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('d');
        vim.processKey('j');             // linewise: deletes lines 0 and 1
        assertEquals("c", vim.getBuffer());
        assertEquals("a\nb\n", vim.getYankRegister());
    }

    @Test
    void operatorDG_deletesToEndOfFileLinewise() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.setCursor(0);                 // line 0
        vim.processKey('d');
        vim.processKey('G');             // linewise to last line
        assertEquals("", vim.getBuffer());
        assertEquals("a\nb\nc\n", vim.getYankRegister()); // linewise yank appends trailing '\n'
    }

    // ---- cw special case: change to end of word, not next word ----

    @Test
    void operatorCW_changesToEndOfWord() {
        vim.setBuffer("hello world");
        vim.processKey((char) 27);
        vim.processKey('0');
        vim.processKey('c');
        vim.processKey('w');
        assertEquals(" world", vim.getBuffer());
        assertEquals(VimMode.INSERT, vim.getMode());
    }

    // ---- linewise paste (p / P) ----

    @Test
    void yankLineAndPasteLinewise_p() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('y');
        vim.processKey('y');             // yank line 0 (linewise)
        vim.processKey('p');             // paste after current line
        assertEquals("a\na\nb\nc", vim.getBuffer());
    }

    @Test
    void yankLineAndPasteLinewise_P() {
        vim.setBuffer("a\nb\nc");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('y');
        vim.processKey('y');
        vim.processKey('P');             // paste before current line
        assertEquals("a\na\nb\nc", vim.getBuffer());
    }

    // ---- 0 / ^ / $ are line-aware in a multi-line buffer ----

    @Test
    void motion0_isLineStartNotBufferStart() {
        vim.setBuffer("aa\nbb");
        vim.processKey((char) 27);
        vim.setCursor(4);                // on line 1 ('b')
        vim.processKey('0');
        assertEquals(3, vim.getCursor()); // start of line 1
    }

    @Test
    void motionDollar_isLineEndNotBufferEnd() {
        vim.setBuffer("aa\nbb");
        vim.processKey((char) 27);
        vim.setCursor(3);                // start of line 1
        vim.processKey('$');
        assertEquals(4, vim.getCursor()); // last char of line 1
    }

    // ---- punctuation-run word motions (w/b/e traverse the whole run) ----

    @Test
    void wordMotionWPunctuation_skipsWholeRun() {
        // "a((b)": w from inside the "((" run must land on 'b' (offset 3).
        vim.setBuffer("a((b)");
        vim.processKey((char) 27);
        vim.setCursor(1);                // on the first '('
        vim.processKey('w');
        assertEquals(3, vim.getCursor());
    }

    @Test
    void wordMotionBPunctuation_landsOnRunStart() {
        // b from 'b' (offset 3) must land on the start of the "((" run (offset 1).
        vim.setBuffer("a((b)");
        vim.processKey((char) 27);
        vim.setCursor(3);                // on 'b'
        vim.processKey('b');
        assertEquals(1, vim.getCursor());
    }

    @Test
    void wordMotionEPunctuation_endsOnRunEnd() {
        // e from 'a' (offset 0) lands on the last '(' of the "((" run (offset 2).
        vim.setBuffer("a((b)");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('e');
        assertEquals(2, vim.getCursor());
    }

    // ---- x with count deletes `count` graphemes ----

    @Test
    void xWithCount_deletesMultipleChars() {
        vim.setBuffer("abcde");
        vim.processKey((char) 27);
        vim.setCursor(0);
        vim.processKey('3');
        vim.processKey('x');             // delete "abc"
        assertEquals("de", vim.getBuffer());
        assertEquals("abc", vim.getYankRegister());
        assertEquals(0, vim.getCursor());
    }

    // ---- linewise dG from a non-column-0 cursor (EOF pulls in newline) ----

    @Test
    void operatorDG_fromNonZeroColumn_eofBehavior() {
        vim.setBuffer("aa\nbb\ncc");
        vim.processKey((char) 27);
        vim.setCursor(1);                // col 1 on line 0
        vim.processKey('d');
        vim.processKey('G');
        assertEquals("a", vim.getBuffer());
        assertEquals("a\nbb\ncc\n", vim.getYankRegister());
    }
}
