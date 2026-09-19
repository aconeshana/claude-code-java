package com.claudecode.core.attachment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The keyword opts a turn into multi-agent orchestration, which is expensive — so it has to
 * fire on a genuine request and stay quiet when the word is merely being discussed.
 */
class UltracodeKeywordTest {

    @Test
    void firesOnAPlainMention() {
        assertTrue(UltracodeKeyword.mentionedIn("ultracode this refactor please"));
        assertTrue(UltracodeKeyword.mentionedIn("Please ULTRACODE the migration"));
        assertTrue(UltracodeKeyword.mentionedIn("audit the parser, ultracode"));
    }

    @Test
    void ignoresBlankAndAbsentInput() {
        assertFalse(UltracodeKeyword.mentionedIn(null));
        assertFalse(UltracodeKeyword.mentionedIn("   "));
        assertFalse(UltracodeKeyword.mentionedIn("just refactor the parser"));
    }

    @Test
    void requiresAWholeWord() {
        assertFalse(UltracodeKeyword.mentionedIn("run the ultracodex benchmark"));
        assertFalse(UltracodeKeyword.mentionedIn("see superultracode docs"));
    }

    @Test
    void slashCommandsNeverTrigger() {
        // /effort ultracode sets the level; it must not also fire the per-turn trigger.
        assertFalse(UltracodeKeyword.mentionedIn("/effort ultracode"));
    }

    @Test
    void mentionsInsideQuotedSpansAreDiscussion() {
        assertFalse(UltracodeKeyword.mentionedIn("what does `ultracode` do?"));
        assertFalse(UltracodeKeyword.mentionedIn("the docs say \"ultracode\" is a keyword"));
        assertFalse(UltracodeKeyword.mentionedIn("grep for <ultracode> in the bundle"));
        assertFalse(UltracodeKeyword.mentionedIn("check the [ultracode] entry"));
        assertFalse(UltracodeKeyword.mentionedIn("read the docs (ultracode section)"));
    }

    @Test
    void aQuotedMentionDoesNotSuppressAnUnquotedOne() {
        assertTrue(UltracodeKeyword.mentionedIn("`ultracode` is the keyword — ultracode this"));
    }

    @Test
    void anUnterminatedDelimiterDoesNotSwallowTheRest() {
        // A stray bracket must not silently disable the trigger for everything after it.
        assertTrue(UltracodeKeyword.mentionedIn("fix the [1 case — ultracode it"));
    }

    @Test
    void apostrophesInContractionsDoNotOpenAQuote() {
        assertTrue(UltracodeKeyword.mentionedIn("it doesn't matter, ultracode this"));
    }
}
