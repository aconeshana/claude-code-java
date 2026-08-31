package com.claudecode.core.text;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StringUtilsTest {

    @Test
    void padEndPreservesWideStringsAndAddsOnlyMissingSpaces() {
        assertEquals("abc", StringUtils.padEnd("abc", 2));
        assertEquals("abc", StringUtils.padEnd("abc", 3));
        assertEquals("abc  ", StringUtils.padEnd("abc", 5));
    }

    @Test
    void truncateWithSuffixLimitsBodyBeforeAppendingSuffix() {
        assertEquals("", StringUtils.truncateWithSuffix(null, 3, "..."));
        assertEquals("abc", StringUtils.truncateWithSuffix("abc", 3, "..."));
        assertEquals("abc...", StringUtils.truncateWithSuffix("abcdef", 3, "..."));
    }

    /** Counts newline characters — for windows terminated by a trailing '\n',
     *  this equals the line count, which is what {@code progressTail} keys on. */
    private static int nl(String s) {
        return (int) s.chars().filter(c -> c == '\n').count();
    }

    @Test
    void progressTail_emptyWindow_returnsTwoEmpty() {
        var t = StringUtils.progressTail("");
        assertEquals("", t.last5());
        assertEquals("", t.last100());

        var tNull = StringUtils.progressTail(null);
        assertEquals("", tNull.last5());
        assertEquals("", tNull.last100());
    }

    @Test
    void progressTail_fewerThanFiveLines_returnsWholeWindowForBoth() {
        // 3 lines, each terminated by '\n'
        String window = "a\nb\nc\n";
        var t = StringUtils.progressTail(window);
        assertEquals(window, t.last5());
        assertEquals(window, t.last100());
        assertEquals(3, nl(t.last5()));
    }

    @Test
    void progressTail_singleLineNoNewline_returnsWholeForBoth() {
        var t = StringUtils.progressTail("hello");
        assertEquals("hello", t.last5());
        assertEquals("hello", t.last100());
    }

    @Test
    void progressTail_moreThanFiveLines_distinctBoundedTails() {
        // 10 lines "a\n".."j\n" (each line terminated by '\n').
        StringBuilder sb = new StringBuilder();
        for (char c = 'a'; c <= 'j'; c++) sb.append(c).append('\n');
        String window = sb.toString();

        var t = StringUtils.progressTail(window);
        // fullOutput keeps the last 100 lines -> here the whole window (10 lines).
        assertEquals(10, nl(t.last100()));

        // newline that TERMINATES a line, so for a 10-line (trailing-newline)
        // window the 5th newline from the end lands one line earlier than a
        // naive "last 5 lines" — i.e. 4 lines here. We assert the faithful,
        // bounded result: <= 5 lines and a strict suffix of fullOutput.
        assertEquals(4, nl(t.last5()));
        assertTrue(Strings.CS.endsWith(t.last100(), t.last5()));
        assertNotEquals(t.last5(), t.last100());
        assertTrue(t.last100().length() > t.last5().length());
    }

    @Test
    void progressTail_overHundredLines_fullOutputCappedAt100() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) sb.append("line").append(i).append('\n');
        String window = sb.toString();

        var t = StringUtils.progressTail(window);



        assertEquals(99, nl(t.last100()));
        assertTrue(nl(t.last5()) <= 5);
        assertNotEquals(t.last5(), t.last100());
    }

    @Test
    void progressTailConstantMatchesTaskOutputTailBytes() {
        // Documents the single source of truth shared by BashTool (in-memory)
        // and LocalShellTask (file tail).
        assertEquals(4096, StringUtils.PROGRESS_TAIL_CHARS);
    }
}
