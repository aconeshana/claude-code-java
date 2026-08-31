package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two lines a user gets when a refused turn was quietly replayed somewhere
 * else.
 *
 * <ul>
 *   <li>the announcement body's
 *       three wordings, chosen by refusal category, and the closing feedback
 *       line they all share.</li>
 *   <li>the {@code model_refusal_fallback} system row the
 *       retry path yields, carrying the wire uuids it took back and the user
 *       turn that was refused.</li>
 * </ul>
 *
 * <p>Asserted whole rather than by fragment: this is the only notice the user
 * gets that their turn ran on a different model, so a drifted space is the whole
 * defect.
 */
class RefusalFallbackAnnouncementTest {

    private static final String FABLE = "claude-fable-5";
    private static final String OPUS = "claude-opus-4-5";
    private static final String FEEDBACK = "Send feedback with /feedback or learn more: "
        + RefusalErrorMessage.LEARN_MORE_URL;

    @Test
    void aBroadSafeguardCategoryExplainsWhyTheSafeguardsAreWideRightNow() {
        assertEquals("Fable 5's safeguards flagged this message. The safeguards are "
            + "intentionally broad right now and may flag safe and routine coding, "
            + "cybersecurity, or biology work. These measures let us bring you Mythos-level "
            + "capabilities sooner, and we're working to refine them. "
            + "Switched to Opus 4.5. " + FEEDBACK,
            RefusalFallbackAnnouncement.text(FABLE, OPUS, "cyber"));
    }

    @Test
    void aBiologyRefusalReadsTheSameAsACybersecurityOne() {
        assertEquals(RefusalFallbackAnnouncement.text(FABLE, OPUS, "cyber"),
            RefusalFallbackAnnouncement.text(FABLE, OPUS, "bio"));
    }

    @Test
    void aRoutineCategoryNamesTheModelButKeepsTheReassuranceShort() {
        assertEquals("Fable 5's safeguards flagged this message. This sometimes happens with "
            + "safe, normal conversations. Switched to Opus 4.5. " + FEEDBACK,
            RefusalFallbackAnnouncement.text(FABLE, OPUS, "frontier_llm"));
    }

    @Test
    void anUnknownCategoryDoesNotNameTheModelThatRefused() {
        assertEquals("This model's safeguards flagged this message. This sometimes happens "
            + "with safe, normal conversations. Switched to Opus 4.5. " + FEEDBACK,
            RefusalFallbackAnnouncement.text(FABLE, OPUS, "something_new"));
        assertEquals(RefusalFallbackAnnouncement.text(FABLE, OPUS, "something_new"),
            RefusalFallbackAnnouncement.text(FABLE, OPUS, null));
    }

    @Test
    void theRowCarriesWhatWasTakenBackAndWhichTurnWasRefused() {
        StopDetails stopDetails = new StopDetails("cyber", "Flagged by policy.");
        SystemMessage row = RefusalFallbackAnnouncement.row(
            "uuid-1", FABLE, OPUS, stopDetails, "req-197",
            List.of("wire-1", "wire-2"), "user-7");

        assertEquals("model_refusal_fallback", row.subtype());
        assertEquals("warning", row.level());
        assertEquals(RefusalFallbackAnnouncement.text(FABLE, OPUS, "cyber"), row.content());
        assertEquals(List.of("wire-1", "wire-2"), row.retractedMessageUuids());
        assertEquals("user-7", row.refusedUserMessageUuid());
        assertEquals("retry", row.direction());
        assertEquals("refusal", row.trigger());
        assertEquals(FABLE, row.originalModel());
        assertEquals(OPUS, row.fallbackModel());
        assertEquals("req-197", row.requestId());
        assertEquals("cyber", row.apiRefusalCategory());
        assertEquals("Flagged by policy.", row.apiRefusalExplanation());
        assertTrue(row.timestamp().isPresent(), "the transcript orders rows by timestamp");
    }

    @Test
    void noFallbackRowCarriesTheRefusalDiagnosticsWithoutRetryMetadata() {
        SystemMessage row = RefusalFallbackAnnouncement.noFallbackRow(
            "uuid-2", FABLE, new StopDetails("bio", "No compatible fallback."),
            "req-no-fallback", "user-9");

        assertEquals("model_refusal_no_fallback", row.subtype());
        assertEquals("", row.content());
        assertEquals(FABLE, row.originalModel());
        assertEquals("req-no-fallback", row.requestId());
        assertEquals("bio", row.apiRefusalCategory());
        assertEquals("No compatible fallback.", row.apiRefusalExplanation());
        assertEquals("user-9", row.refusedUserMessageUuid());
        assertNull(row.direction());
        assertNull(row.fallbackModel());
        assertNull(row.retractedMessageUuids());
    }

    @Test
    void aRetryThatTookNothingBackStillSaysSoRatherThanSayingNothing() {
        SystemMessage row = RefusalFallbackAnnouncement.row(
            "uuid-1", FABLE, OPUS, null, List.of(), null);

        assertEquals(List.of(), row.retractedMessageUuids(),
            "an empty list and a missing list are different on the wire");
        assertNull(row.refusedUserMessageUuid());
    }
}
