package com.claudecode.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one line a refused turn puts in front of the user, in each of the three shapes.
 */
class RefusalErrorMessageTest {

    private static final String OPUS = "claude-opus-4-5";
    private static final String AUP = "https://www.anthropic.com/legal/aup";
    private static final String HELP = "https://support.claude.com/en/articles/15363606";
    private static final String FEEDBACK =
        "Send feedback with /feedback or learn more: " + HELP;

    @Test
    void aRefusalOnAModelWithSomewhereToFallBackNamesThatModelAndHidesTheExplanation() {
        String text = RefusalErrorMessage.text(
            new StopDetails(null, "Internal reviewer note"), null, OPUS, true, false, true);

        assertEquals("API Error: Opus 4.5's safeguards flagged this message (" + AUP + "). "
            + "This sometimes happens with safe, normal conversations. "
            + "Claude Code can't respond to this request with Opus 4.5.\n" + FEEDBACK, text);
        assertFalse(Strings.CS.contains(text, "Internal reviewer note"),
            "the explanation belongs to the generic branch only");
    }

    @Test
    void aBroadSafeguardCategoryTradesTheShortLineForTheRefinementNotice() {
        String text = RefusalErrorMessage.text(
            new StopDetails("cyber", null), null, OPUS, true, false, true);

        assertEquals("API Error: Opus 4.5's safeguards flagged this message (" + AUP + "). "
            + "They may flag safe, normal content as well. These measures let us bring you "
            + "Mythos-level capabilities sooner, and we're working to refine them. "
            + "Claude Code can't respond to this request with Opus 4.5.\n" + FEEDBACK, text);
    }

    @Test
    void aCyberRefusalWithNowhereToFallBackOffersTheExemptionFormFromTheExplanation() {
        String text = RefusalErrorMessage.text(
            new StopDetails("cyber", "Apply at https://claude.com/form/abc-123."),
            null, OPUS, false, false, true);

        assertEquals("API Error: Opus 4.5's safeguards flagged this message for a "
            + "cybersecurity topic. If your work requires this access, you can apply for an "
            + "exemption: https://claude.com/form/abc-123\n" + FEEDBACK, text);
    }

    @Test
    void aCyberRefusalWhoseExplanationCarriesNoFormFallsBackToTheGenericForm() {
        String text = RefusalErrorMessage.text(
            new StopDetails("cyber", "No link here."), null, OPUS, false, false, true);

        assertTrue(Strings.CS.contains(text,
                "exemption: https://claude.com/form/cyber-use-case\n"),
            "the generic form is the fallback: " + text);
    }

    @Test
    void aCyberRefusalOnAThirdPartyProviderGetsTheGenericUsagePolicyLineInstead() {
        String text = RefusalErrorMessage.text(
            new StopDetails("cyber", "No link here."), null, OPUS, false, false, false);

        assertTrue(Strings.CS.startsWith(text,
                "API Error: Claude Code is unable to respond to this request"),
            "the exemption form is first-party only: " + text);
    }

    @Test
    void anOverlongExplanationIsCutToFourHundredCharactersAndEndedWithAnEllipsis() {
        String explanation = "x".repeat(500);

        String text = RefusalErrorMessage.text(
            new StopDetails(null, explanation), null, OPUS, false, false, true);

        String expectedClause = " " + "x".repeat(400) + "…";
        assertEquals("API Error: Claude Code is unable to respond to this request, which "
            + "appears to violate our Usage Policy (" + AUP + ")." + expectedClause
            + " Please double press esc to edit your last message or start a new session for "
            + "Claude Code to assist with a different task.", text);
    }

    @Test
    void anExplanationThatDoesNotEndASentenceGetsAPeriod() {
        String text = RefusalErrorMessage.text(
            new StopDetails(null, "blocked"), null, OPUS, false, false, true);

        assertEquals("API Error: Claude Code is unable to respond to this request, which "
            + "appears to violate our Usage Policy (" + AUP + "). blocked. "
            + "Please double press esc to edit your last message or start a new session for "
            + "Claude Code to assist with a different task.", text);
    }

    @Test
    void aHeadlessSessionCannotBeToldToPressEscapeOrToSendFeedback() {
        String withTarget = RefusalErrorMessage.text(
            new StopDetails(null, null), null, OPUS, true, true, true);
        String withoutTarget = RefusalErrorMessage.text(
            new StopDetails(null, null), null, OPUS, false, true, true);

        assertTrue(Strings.CS.endsWith(withTarget, "\nLearn more: " + HELP), withTarget);
        assertTrue(Strings.CS.endsWith(withoutTarget,
            "Try rephrasing the request in a new session or change your model."), withoutTarget);
    }

    @Test
    void aRequestIdIsAppendedOnItsOwnLine() {
        String text = RefusalErrorMessage.text(
            new StopDetails(null, null), "req_123", OPUS, false, true, true);

        assertTrue(Strings.CS.endsWith(text, "\nRequest ID: req_123"), text);
    }

    @Test
    void aRefusalWithoutDetailsStillProducesTheGenericLine() {
        String text = RefusalErrorMessage.text(null, null, null, false, false, true);

        assertEquals("API Error: Claude Code is unable to respond to this request, which "
            + "appears to violate our Usage Policy (" + AUP + "). "
            + "Please double press esc to edit your last message or start a new session for "
            + "Claude Code to assist with a different task.", text);
    }

    @Test
    void theRefusalRowKeepsItsStopDetailsAndRequestIdThroughTheTranscript() {
        StopDetails details = new StopDetails("cyber", "Flagged for review.");

        AssistantMessage message =
            MessageFactory.createRefusalErrorMessage("API Error: nope", "req_9", details);

        assertTrue(message.isApiErrorMessage(), "the row renders as an API error");
        assertEquals("invalid_request", message.error());
        assertNull(message.apiError(), "released passes only the error kind");

        JsonNode json = JsonUtils.getMapper().valueToTree(message);
        assertEquals("req_9", json.get("requestId").asText());
        assertEquals("refusal", json.get("message").get("stop_reason").asText());
        assertEquals("cyber", json.get("message").get("stop_details").get("category").asText());
        assertEquals("Flagged for review.",
            json.get("message").get("stop_details").get("explanation").asText());

        AssistantMessage restored =
            JsonUtils.getMapper().convertValue(json, AssistantMessage.class);
        assertEquals(details, restored.message().stopDetails());
        assertEquals("req_9", restored.requestId());
    }
}
