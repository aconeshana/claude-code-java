package com.claudecode.ui.lanterna.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the mid-input slash-command completion to 2.1.236's {@code kms} / {@code xDc}; the
 * expected values were observed against the released binary in tmux.
 */
class InlineCommandCompletionTest {

    @Test
    void whitespacePrecededSlashBeforeTheCaretIsAToken() {
        assertEquals(new InlineCommandCompletion.Token(6, "comp"),
            InlineCommandCompletion.locate("hello /comp", "hello /comp".length()));
    }

    @Test
    void fullWidthPunctuationAlsoOpensAToken() {
        for (String lead : List.of("？", "！", "。", "、")) {
            String text = "看" + lead + "/comp";
            assertEquals(new InlineCommandCompletion.Token(2, "comp"),
                InlineCommandCompletion.locate(text, text.length()), lead);
        }
    }

    @Test
    void ideographicSpaceCountsAsWhitespaceLikeJavaScriptBackslashS() {
        String text = "看\u3000/comp";
        assertEquals(new InlineCommandCompletion.Token(2, "comp"),
            InlineCommandCompletion.locate(text, text.length()));
    }

    @Test
    void columnZeroAndGluedSlashesAreNotMidInputTokens() {
        assertNull(InlineCommandCompletion.locate("/comp", 5));
        assertNull(InlineCommandCompletion.locate("a/comp", 6));
        assertNull(InlineCommandCompletion.locate("x ，/comp", 8),
            "only 。、？！ are accepted besides whitespace");
    }

    @Test
    void theTokenSpansTheWholeCommandNameEvenWhenTheCaretIsInsideIt() {
        String text = "x /comp thing";
        assertEquals(new InlineCommandCompletion.Token(2, "comp"),
            InlineCommandCompletion.locate(text, "x /co".length()));
    }

    @Test
    void caretAfterTheCommandNameEndsTheToken() {
        assertNull(InlineCommandCompletion.locate("x /comp thing", "x /comp ".length()));
        assertNull(InlineCommandCompletion.locate("x /comp", 1),
            "a caret before the slash has no token to complete");
    }

    @Test
    void argumentTakingLeadingCommandsSuppressMidInputTokens() {
        for (String lead : List.of("add-dir", "cd", "resume", "plugin", "plugins", "marketplace")) {
            String text = "/" + lead + " foo /co";
            assertNull(InlineCommandCompletion.locate(text, text.length()), lead);
        }
        String other = "/model foo /co";
        assertEquals(new InlineCommandCompletion.Token(11, "co"),
            InlineCommandCompletion.locate(other, other.length()));
        String midInputResume = "x /res";
        assertEquals(new InlineCommandCompletion.Token(2, "res"),
            InlineCommandCompletion.locate(midInputResume, midInputResume.length()),
            "only a leading excluded command suppresses; typing /res mid-input still completes");
    }

    @Test
    void ghostIsTheFirstRankedNameExtendingTheTypedPrefix() {
        var token = new InlineCommandCompletion.Token(2, "co");
        assertEquals(new InlineCommandCompletion.Ghost("mpact", "compact", 5),
            InlineCommandCompletion.complete(token, List.of("clear", "compact", "config")));
    }

    @Test
    void ghostMatchesCaseInsensitivelyAndCompletesToTheCanonicalName() {
        var token = new InlineCommandCompletion.Token(2, "COMP");
        assertEquals(new InlineCommandCompletion.Ghost("act", "compact", 7),
            InlineCommandCompletion.complete(token, List.of("compact")));
    }

    @Test
    void anExactOrEmptyCommandHasNoGhost() {
        assertNull(InlineCommandCompletion.complete(
            new InlineCommandCompletion.Token(2, "compact"), List.of("compact")));
        assertNull(InlineCommandCompletion.complete(
            new InlineCommandCompletion.Token(2, ""), List.of("compact")));
    }

    @Test
    void anExactMatchFallsThroughToALongerNameWithTheSamePrefix() {
        var token = new InlineCommandCompletion.Token(2, "plugin");
        assertEquals(new InlineCommandCompletion.Ghost("s", "plugins", 9),
            InlineCommandCompletion.complete(token, List.of("plugin", "plugins")));
    }

    @Test
    void acceptSplicesTheCommandAndASpaceBeforeTheUntouchedTail() {
        var ghost = new InlineCommandCompletion.Ghost("act", "compact", 7);
        String text = "x /comp thing";

        assertEquals(new InlineCommandCompletion.Accepted("x /compact  thing", "x /compact ".length()),
            InlineCommandCompletion.accept(text, "x /co".length(), ghost));
        assertEquals(new InlineCommandCompletion.Accepted("hello /compact ", "hello /compact ".length()),
            InlineCommandCompletion.accept("hello /comp", "hello /comp".length(),
                new InlineCommandCompletion.Ghost("act", "compact", 11)));
    }

    @Test
    void acceptRefusesAGhostThatNoLongerMatchesTheText() {
        var ghost = new InlineCommandCompletion.Ghost("act", "compact", 7);
        assertNull(InlineCommandCompletion.accept("x /co", 5, ghost));
        assertNull(InlineCommandCompletion.accept("x /comp ", 8, ghost));
    }
}
