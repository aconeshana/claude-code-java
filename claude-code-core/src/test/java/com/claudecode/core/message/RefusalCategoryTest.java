package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal-category classifier and the {@code stop_details} envelope that carries it.
 */
class RefusalCategoryTest {

    @Test
    void onlyCyberAndBioGetTheBroadSafeguardCopy() {
        assertTrue(RefusalCategory.usesBroadSafeguardCopy("cyber"));
        assertTrue(RefusalCategory.usesBroadSafeguardCopy("bio"));
        assertFalse(RefusalCategory.usesBroadSafeguardCopy("frontier_llm"));
        assertFalse(RefusalCategory.usesBroadSafeguardCopy(null));
    }

    @Test
    void onlyFrontierLlmAndReasoningExtractionGetTheRoutineCopy() {
        assertTrue(RefusalCategory.usesRoutineConversationCopy("frontier_llm"));
        assertTrue(RefusalCategory.usesRoutineConversationCopy("reasoning_extraction"));
        assertFalse(RefusalCategory.usesRoutineConversationCopy("cyber"));
        assertFalse(RefusalCategory.usesRoutineConversationCopy(null));
    }

    @Test
    void telemetryCollapsesEveryUnknownCategoryIntoOther() {
        assertEquals("cyber", RefusalCategory.normalizeForTelemetry("cyber"));
        assertEquals("bio", RefusalCategory.normalizeForTelemetry("bio"));
        assertEquals("frontier_llm", RefusalCategory.normalizeForTelemetry("frontier_llm"));
        assertEquals("reasoning_extraction",
            RefusalCategory.normalizeForTelemetry("reasoning_extraction"));
        assertEquals("other", RefusalCategory.normalizeForTelemetry("something_new"));
        assertEquals("other", RefusalCategory.normalizeForTelemetry(null));
    }

    @Test
    void stopDetailsRoundTripsThroughTheWireShape() {
        StopDetails details = JsonUtils.fromJson(
            "{\"category\":\"cyber\",\"explanation\":\"flagged\"}", StopDetails.class);
        assertEquals("cyber", details.category());
        assertEquals("flagged", details.explanation());
        assertEquals("{\"category\":\"cyber\",\"explanation\":\"flagged\"}",
            JsonUtils.toJson(details));
    }

    @Test
    void assistantContentWithoutRefusalDetailsKeepsItsExistingJsonShape() {
        AssistantContent content = AssistantContent.apiResponse(
            "msg-1", List.of(new TextBlock("hi")), Usage.EMPTY,
            "claude-opus-5", "end_turn", null);

        assertNull(content.stopDetails());
        assertFalse(Strings.CS.contains(JsonUtils.toJson(content), "stop_details"),
            "a null envelope must not add a key to existing transcripts");
    }

    @Test
    void assistantContentCarriesRefusalDetailsWhenPresent() {
        AssistantContent content = AssistantContent.apiResponse(
            "msg-1", List.of(), Usage.EMPTY, "claude-opus-5", "refusal", null,
            new StopDetails("bio", "safeguards"));

        assertEquals("bio", content.stopDetails().category());
        assertTrue(Strings.CS.contains(JsonUtils.toJson(content), "\"stop_details\""));
    }

    @Test
    void finalDeltaOverlaysRefusalDetailsWithoutClearingThem() {
        AssistantContent streamed = AssistantContent.apiResponse(
            "msg-1", List.of(), Usage.EMPTY, "claude-opus-5", null, null,
            new StopDetails("cyber", "from message_start"));

        assertEquals("cyber",
            streamed.withFinalDelta(null, "refusal", null, null).stopDetails().category(),
            "a delta without stop_details must not erase what message_start carried");
        assertEquals("bio",
            streamed.withFinalDelta(null, "refusal", null, new StopDetails("bio", null))
                .stopDetails().category());
    }
}
