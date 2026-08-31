package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which user message a refusal was a refusal <em>of</em>.
 *
 * <ul>
 *   <li>the search for the last unanswered turn the user
 *       actually typed, recorded on the refusal-fallback announcement, and the
 *       compact-boundary slice that stops the search from reaching back past a
 *       summarized stretch of conversation.</li>
 *   <li>the predicate that separates a typed
 *       turn from the machinery that also arrives as a user message: tool
 *       results, meta rows, compact summaries, synthetic interrupts and the
 *       xml-wrapped bodies commands and hooks inject.</li>
 * </ul>
 */
class HumanTurnsTest {

    private static UserMessage typed(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private static UserMessage meta(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text), true, false, null,
            MessageOrigin.USER, null, Instant.now(), null, null);
    }

    private static AssistantMessage answered(String uuid) {
        return new AssistantMessage(uuid,
            AssistantContent.of(List.of(new TextBlock("Sure"))));
    }

    @Test
    void theLastTurnTypedAfterTheLastAnswerIsTheOneThatWasRefused() {
        List<Message> chain = List.of(
            typed("u-1", "first question"),
            answered("a-1"),
            typed("u-2", "second question"),
            typed("u-3", "actually, this one"));

        assertEquals("u-3", HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void aTurnThatWasAlreadyAnsweredIsNotTheRefusedOne() {
        List<Message> chain = List.of(typed("u-1", "question"), answered("a-1"));

        assertNull(HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void aCompactBoundaryHidesEverythingBeforeIt() {
        List<Message> chain = List.of(
            typed("u-1", "before the compaction"),
            new SystemMessage("s-1", "compact_boundary", "info", ""));

        assertNull(HumanTurns.lastUnansweredHumanTurnUuid(chain),
            "the pre-compaction turn is no longer part of this conversation");
    }

    @Test
    void toolResultsMetaRowsAndSyntheticInterruptsAreNotTypedTurns() {
        List<Message> chain = List.of(
            typed("u-1", "the real question"),
            new UserMessage("u-2", MessageContent.ofToolResult("toolu_1", List.of(), false)),
            meta("u-3", "a reminder nobody typed"),
            typed("u-4", MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE));

        assertEquals("u-1", HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void anXmlWrappedBodyIsMachineryEvenWhenItIsNotFlaggedAsMeta() {
        List<Message> chain = List.of(
            typed("u-1", "the real question"),
            typed("u-2", "<local-command-stdout>Set model to opus</local-command-stdout>"),
            typed("u-3", "<task-notification>agent finished</task-notification>"),
            typed("u-4", "<bash-stdout>ok</bash-stdout>"));

        assertEquals("u-1", HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void aTeammateMessageIsNotATypedTurnEvenBehindItsPreamble() {
        List<Message> chain = List.of(
            typed("u-1", "the real question"),
            typed("u-2", """
                Another Claude session sent a message while you were working:
                <teammate-message from="scout">done</teammate-message>"""));

        assertEquals("u-1", HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void aTurnFromAnythingButTheKeyboardIsNotATypedTurn() {
        List<Message> chain = List.of(
            typed("u-1", "the real question"),
            new UserMessage("u-2", MessageContent.ofText("hook output"), false, false, null,
                MessageOrigin.HOOK, null, Instant.now(), null, null));

        assertEquals("u-1", HumanTurns.lastUnansweredHumanTurnUuid(chain));
    }

    @Test
    void typedTurnClassificationUsesAllJoinedTextBlocksLike197() {
        UserMessage machineWrapped = new UserMessage("u-1", MessageContent.ofBlocks(List.of(
            new TextBlock("<task-notification>done</task-notification>"),
            new TextBlock("visible follow-up text"))));
        UserMessage typed = new UserMessage("u-2", MessageContent.ofBlocks(List.of(
            new TextBlock("first line"), new TextBlock("second line"))));

        assertFalse(HumanTurns.isTypedTurn(machineWrapped));
        assertTrue(HumanTurns.isTypedTurn(typed));
    }

    @Test
    void rewindTailPredicateReadsTheFinalConversationMessages() {
        UserMessage target = typed("u-1", "question");
        AssistantMessage blank = new AssistantMessage("a-blank",
            AssistantContent.of(List.of(new TextBlock("   "))));
        AssistantMessage answer = answered("a-answer");

        assertTrue(HumanTurns.messagesAfterAreOnlySynthetic(
            List.of(target, meta("m", "reminder"), blank), 0));
        assertFalse(HumanTurns.messagesAfterAreOnlySynthetic(
            List.of(target, answer), 0));
    }

    @Test
    void anEmptyChainHasNoRefusedTurn() {
        assertNull(HumanTurns.lastUnansweredHumanTurnUuid(List.of()));
        assertNull(HumanTurns.lastUnansweredHumanTurnUuid(null));
    }
}
