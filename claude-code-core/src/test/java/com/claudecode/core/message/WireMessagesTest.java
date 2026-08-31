package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The uuids a retraction has to name, which are not the uuids the transcript
 * stores.
 *
 * <ul>
 *   <li>the wire expansion of a logical message
 *       into one message per content block, the sharded uuid that identifies
 *       each of them, and the emptiness test that keeps a placeholder block out
 *       of the retraction list.</li>
 * </ul>
 *
 * <p>The fixture uuid is chosen so its 24-character prefix ends on a separator:
 * a shard uuid is then readable at a glance and an off-by-one in the prefix
 * length cannot pass unnoticed.
 */
class WireMessagesTest {

    /** Exactly 24 characters up to and including the last dash. */
    private static final String UUID_A = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String PREFIX_A = "aaaaaaaa-bbbb-cccc-dddd-";

    private static AssistantMessage assistant(String uuid, ContentBlock... blocks) {
        return new AssistantMessage(uuid, AssistantContent.of(List.of(blocks)));
    }

    @Test
    void anAssistantWithSeveralBlocksGetsOneShardedUuidPerBlock() {
        AssistantMessage message = assistant(UUID_A,
            new TextBlock("first"), new TextBlock("second"), new TextBlock("third"));

        assertEquals(List.of(
                PREFIX_A + "000000000000",
                PREFIX_A + "000000000001",
                PREFIX_A + "000000000002"),
            WireMessages.retractedUuids(List.of(message)));
    }

    @Test
    void theShardIndexIsHexadecimalSoTheTenthBlockIsNotTheTenthDigit() {
        assertEquals(PREFIX_A + "00000000000a", WireMessages.shardUuid(UUID_A, 10));
    }

    @Test
    void aSingleBlockKeepsTheLogicalUuid() {
        AssistantMessage message = assistant(UUID_A, new TextBlock("only one"));

        assertEquals(List.of(UUID_A), WireMessages.retractedUuids(List.of(message)));
    }

    @Test
    void blankAndPlaceholderTextBlocksAreNotRetracted() {
        AssistantMessage message = assistant(UUID_A,
            new TextBlock("   "),
            new TextBlock(MessageConstants.NO_CONTENT_MESSAGE),
            new TextBlock(MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE),
            new TextBlock("the real answer"));

        assertEquals(List.of(PREFIX_A + "000000000003"),
            WireMessages.retractedUuids(List.of(message)),
            "only the fourth shard carries content, but it still shards");
    }

    @Test
    void aNonTextBlockAlwaysCountsAsContent() {
        AssistantMessage message = assistant(UUID_A,
            new ToolUseBlock("toolu_1", "Read", null));

        assertEquals(List.of(UUID_A), WireMessages.retractedUuids(List.of(message)));
    }

    @Test
    void anAssistantWithNothingInItContributesNoUuid() {
        AssistantMessage message = new AssistantMessage(UUID_A, AssistantContent.of(List.of()));

        assertTrue(WireMessages.retractedUuids(List.of(message)).isEmpty());
    }

    @Test
    void aUserStringTurnIsRetractedWholeAndABlankOneIsNot() {
        UserMessage said = new UserMessage(UUID_A, MessageContent.ofText("Hi there"));
        UserMessage blank = new UserMessage(
            "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", MessageContent.ofText("  \n "));

        assertEquals(List.of(UUID_A), WireMessages.retractedUuids(List.of(said, blank)),
            "a string turn is one wire message, never sharded");
    }

    @Test
    void aUserTurnWithSeveralBlocksShardsLikeAnAssistantOne() {
        UserMessage message = new UserMessage(UUID_A, MessageContent.ofBlocks(List.of(
            new TextBlock("look at this"), new ToolUseBlock("toolu_1", "Read", null))));

        assertEquals(List.of(PREFIX_A + "000000000000", PREFIX_A + "000000000001"),
            WireMessages.retractedUuids(List.of(message)));
    }

    @Test
    void theRetractionListIsExactlyWhatTheResumeFilterLooksFor() {
        AssistantMessage refused = assistant(UUID_A,
            new TextBlock("I can't help"), new TextBlock("with that"));
        SystemMessage announcement = RefusalFallbackAnnouncement.row(
            "uuid-announcement", "claude-fable-5", "claude-opus-4-5", "cyber",
            WireMessages.retractedUuids(List.of(refused)), null);

        List<Message> kept = RetractedMessages.filter(List.of(refused, announcement));

        assertEquals(List.of(announcement), kept,
            "the announcement's own uuids must take the refused row back out");
    }
}
