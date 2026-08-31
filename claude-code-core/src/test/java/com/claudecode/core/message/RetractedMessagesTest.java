package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The retracted-message filter behind {@code /resume}: a refusal-fallback
 * announcement lists the wire uuids of messages that were streamed and then
 * taken back, and every entry sharing their 24-character prefix must not be
 * restored into the conversation.
 */
class RetractedMessagesTest {

    /** A real logical uuid; its first 24 chars are the shard prefix. */
    private static final String LOGICAL = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    private static final String PREFIX = "f47ac10b-58cc-4372-a567-";
    /** What the announcement actually stores: prefix + 12 hex shard digits. */
    private static final String WIRE = PREFIX + "000000000001";
    private static final String OTHER = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d";

    private static UserMessage user(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private static AssistantMessage assistant(String uuid, String text) {
        return new AssistantMessage(uuid,
            AssistantContent.of(List.<ContentBlock>of(new TextBlock(text))));
    }

    private static SystemMessage announcement(String uuid, List<String> retracted) {
        return new SystemMessage(uuid, "model_refusal_fallback", "warning",
            "Claude Opus 4.5 declined; retrying with Sonnet",
            null, Instant.EPOCH, null, null, null, retracted);
    }

    @Test
    void aChainWithoutAnyAnnouncementIsReturnedUnchanged() {
        List<Message> msgs = List.of(user(LOGICAL, "hi"), assistant(OTHER, "hello"));
        assertSame(msgs, RetractedMessages.filter(msgs),
            "no announcement means no work — TS returns the very same array");
    }

    @Test
    void anAnnouncementWithoutUuidsIsAlsoANoOp() {
        List<Message> msgs = List.of(announcement(OTHER, null), user(LOGICAL, "hi"));
        assertSame(msgs, RetractedMessages.filter(msgs));
    }

    @Test
    void aWireUuidDropsTheLogicalMessageItWasShardedFrom() {
        List<Message> msgs = List.of(
            user(OTHER, "kept"),
            assistant(LOGICAL, "retracted reply"),
            announcement("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", List.of(WIRE)));

        List<Message> kept = RetractedMessages.filter(msgs);

        assertEquals(2, kept.size(), "only the retracted assistant message may go: " + kept);
        assertEquals(OTHER, kept.getFirst().uuid());
        assertInstanceOf(SystemMessage.class, kept.get(1), "the announcement itself survives");
    }

    @Test
    void systemEntriesAreKeptEvenWhenTheirUuidMatches() {
        // The announcement's own uuid shares the retracted prefix here, and so

        SystemMessage collateral = new SystemMessage(LOGICAL, "informational", "warning", "note");
        List<Message> msgs = List.of(
            collateral,
            user(LOGICAL, "dropped"),
            announcement(WIRE, List.of(WIRE)));

        List<Message> kept = RetractedMessages.filter(msgs);

        assertEquals(2, kept.size(), "both system rows stay, only the user message goes: " + kept);
        assertSame(collateral, kept.getFirst());
    }

    @Test
    void onlyRefusalFallbackAnnouncementsContributeUuids() {
        SystemMessage lookalike = new SystemMessage("cccccccc-dddd-eeee-ffff-000000000000",
            "model_refusal_no_fallback", "warning", "declined",
            null, Instant.EPOCH, null, null, null, List.of(WIRE));
        List<Message> msgs = List.of(lookalike, user(LOGICAL, "kept"));

        assertSame(msgs, RetractedMessages.filter(msgs),
            "the no-fallback sibling carries no retraction");
    }

    @Test
    void messagesWithoutAUuidSurviveInsteadOfCrashing() {
        List<Message> msgs = List.of(
            user(null, "no uuid"),
            announcement(OTHER, List.of(WIRE)));

        List<Message> kept = RetractedMessages.filter(msgs);

        assertEquals(2, kept.size(), "a null uuid cannot match a prefix: " + kept);
    }

    @Test
    void uuidsShorterThanThePrefixCompareWhole() {
        String shortId = "f47ac10b";
        List<Message> msgs = List.of(
            user(shortId, "dropped"),
            user("f47ac10c", "kept"),
            announcement(OTHER, List.of(shortId)));

        List<Message> kept = RetractedMessages.filter(msgs);

        assertEquals(2, kept.size(), "JS slice() on a short string yields the whole string: " + kept);
        assertEquals("f47ac10c", kept.getFirst().uuid());
    }

    @Test
    void filteringTwiceChangesNothingFurther() {
        List<Message> msgs = List.of(
            assistant(LOGICAL, "retracted"),
            user(OTHER, "kept"),
            announcement("11111111-2222-3333-4444-555555555555", List.of(WIRE)));

        List<Message> once = RetractedMessages.filter(msgs);
        List<Message> twice = RetractedMessages.filter(once);

        // The resume path runs the filter before AND inside the recovery
        // pipeline, so a second pass must be inert.
        assertEquals(once, twice);
    }

    @Test
    void nullAndEmptyInputAreHandled() {
        assertEquals(List.of(), RetractedMessages.filter(null));
        List<Message> empty = new ArrayList<>();
        assertSame(empty, RetractedMessages.filter(empty));
    }
}
