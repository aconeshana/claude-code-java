package com.claudecode.ui.lanterna.input;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class BtwTriggersTest {

    @Test
    void plainBtwAtStart() {
        List<BtwTriggers.Trigger> hits = BtwTriggers.find("/btw what is React?");
        assertEquals(1, hits.size());
        BtwTriggers.Trigger t = hits.getFirst();
        assertEquals("/btw", t.word());
        assertEquals(0, t.start());
        assertEquals(4, t.end());
    }

    @Test
    void btwOnly() {
        List<BtwTriggers.Trigger> hits = BtwTriggers.find("/btw");
        assertEquals(1, hits.size());
        assertEquals(0, hits.getFirst().start());
        assertEquals(4, hits.getFirst().end());
    }

    @Test
    void caseInsensitive() {

        assertEquals(1, BtwTriggers.find("/BTW question").size());
        assertEquals(1, BtwTriggers.find("/Btw question").size());
        assertEquals("/BTW", BtwTriggers.find("/BTW question").getFirst().word());
    }

    @Test
    void wordBoundaryBlocksBtweet() {

        // Without \b, the prompt would yellow-flash on every prefix typed
        // before the user committed to /btw vs /btweet vs anything else.
        assertTrue(BtwTriggers.find("/btweet hello").isEmpty(),
            "/btweet must not be recognized as /btw");
        assertTrue(BtwTriggers.find("/btwoooo").isEmpty(),
            "/btwoooo must not match");
    }

    @Test
    void anchoredToStart() {
        // ^ anchor — /btw mid-line doesn't count.
        assertTrue(BtwTriggers.find("hello /btw there").isEmpty(),
            "/btw not at start of input must not be highlighted");
        assertTrue(BtwTriggers.find(" /btw").isEmpty(),
            "leading whitespace blocks the start anchor");
    }

    @Test
    void emptyAndNull() {
        assertTrue(BtwTriggers.find("").isEmpty());
        assertTrue(BtwTriggers.find(null).isEmpty());
    }

    @Test
    void notSlashCommand() {
        assertTrue(BtwTriggers.find("btw what is React?").isEmpty(),
            "btw without leading slash isn't a command trigger");
    }

    @Test
    void btwWithPunctuationBoundary() {
        // \b matches between word char and non-word char. Hits stop at the
        // boundary, so the trigger covers exactly "/btw" — punctuation after

        List<BtwTriggers.Trigger> hits = BtwTriggers.find("/btw, hello");
        assertEquals(1, hits.size());
        assertEquals("/btw", hits.getFirst().word());
        assertEquals(4, hits.getFirst().end());
    }
}
