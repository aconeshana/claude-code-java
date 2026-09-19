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

    @Test
    void comparisonsInProseDoNotSwallowTheKeyword() {
        // A "<" in prose used to pair with the next ">" anywhere later in the prompt, so an
        // ordinary comparison silently disabled the trigger with no diagnostic.
        assertTrue(UltracodeKeyword.mentionedIn(
            "if tokens<limit and ultracode is on, keep budget>0"));
        assertTrue(UltracodeKeyword.mentionedIn(
            "when n<10 ultracode the retry path, ensure k>2"));
        assertTrue(UltracodeKeyword.mentionedIn("if a<b and ultracode then c>d"));
        assertTrue(UltracodeKeyword.mentionedIn("i<j ultracode k<l m>n"));
        assertTrue(UltracodeKeyword.mentionedIn("3<4<5 ultracode"));
        assertTrue(UltracodeKeyword.mentionedIn("use x<y ultracode"));
    }

    @Test
    void genuineTagsStillSuppressAKeywordInsideThem() {
        assertFalse(UltracodeKeyword.mentionedIn("<ultracode/>"));
        assertFalse(UltracodeKeyword.mentionedIn("<a:ultracode>"));
        assertFalse(UltracodeKeyword.mentionedIn("<ultracode-v2>"));
        assertFalse(UltracodeKeyword.mentionedIn("<div class=\"ultracode\">"));
        assertFalse(UltracodeKeyword.mentionedIn("<foo a=\"1\" b=\"2\"> <x ultracode>"));
        // A keyword inside a type argument is code being quoted, not a request.
        assertFalse(UltracodeKeyword.mentionedIn("List<ultracode> is the type"));
    }

    @Test
    void tagContentsStillFire() {
        // Each tag closes its own span, so a keyword *between* tags is ordinary prose.
        assertTrue(UltracodeKeyword.mentionedIn("<thinking>ultracode</thinking>"));
        assertTrue(UltracodeKeyword.mentionedIn("the <br/>ultracode<br/> row"));
    }

    @Test
    void spansDoNotCrossALineBreak() {
        // An opener left dangling at end of line must not cover the following lines.
        assertTrue(UltracodeKeyword.mentionedIn("compare tokens<limit\nnow ultracode the parser"));
        assertTrue(UltracodeKeyword.mentionedIn("see `snippet\nultracode this please"));
        assertTrue(UltracodeKeyword.mentionedIn("<thinking>\nultracode the parser\n</thinking>"));
        // A span that opens and closes on one line still suppresses that line's mention.
        assertFalse(UltracodeKeyword.mentionedIn("line one\n`ultracode` means what?\nline three"));
    }

    @Test
    void unbalancedAngleBracketsDoNotDisableTheTrigger() {
        assertTrue(UltracodeKeyword.mentionedIn("tokens < limit, ultracode it"));
        assertTrue(UltracodeKeyword.mentionedIn("<not-a-tag ultracode"));
        assertTrue(UltracodeKeyword.mentionedIn("x<3 ultracode"));
        assertTrue(UltracodeKeyword.mentionedIn("<>ultracode"));
        assertTrue(UltracodeKeyword.mentionedIn("</>ultracode"));
        assertTrue(UltracodeKeyword.mentionedIn("< ultracode >"));
        assertTrue(UltracodeKeyword.mentionedIn("ultracode <"));
        // Too long to be a tag: the run is prose that happens to contain both brackets.
        assertTrue(UltracodeKeyword.mentionedIn(
            "a<very-long-identifier-name-that-goes-on-and-on-forever>b ultracode"));
    }
}
