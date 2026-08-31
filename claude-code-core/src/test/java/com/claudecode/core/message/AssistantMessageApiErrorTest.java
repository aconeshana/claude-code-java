package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AssistantMessageApiErrorTest {

    @Test
    void apiErrorMetadataIsSerializedButOrdinaryMessagesOmitNullFields() {
        AssistantMessage apiError = MessageFactory.createAssistantAPIErrorMessage(
            "API Error: test", "max_output_tokens", "max_output_tokens");
        AssistantMessage ordinary = MessageFactory.createAssistantMessage("ok");

        var errorJson = JsonUtils.getMapper().valueToTree(apiError);
        var ordinaryJson = JsonUtils.getMapper().valueToTree(ordinary);

        assertEquals("max_output_tokens", errorJson.path("apiError").asText());
        assertEquals("max_output_tokens", errorJson.path("error").asText());
        assertEquals("<synthetic>", errorJson.path("message").path("model").asText());
        assertEquals("stop_sequence",
            errorJson.path("message").path("stop_reason").asText());
        assertEquals("", errorJson.path("message").path("stop_sequence").asText());
        assertFalse(StringUtils.isBlank(errorJson.path("message").path("id").asText()));
        assertFalse(errorJson.path("message").path("usage").hasNonNull("service_tier"));
        assertFalse(ordinaryJson.has("apiError"));
        assertFalse(ordinaryJson.has("error"));
    }


    @Test
    void assistantEnvelopeFieldsRoundTripAndOmitWhenAbsent() throws Exception {
        AssistantMessage withMeta = new AssistantMessage(
            "a", AssistantContent.of(List.of(new TextBlock("one"))),
            false, null, null, null, null, null, null, null, null,
            true, "req-123", "claude-3-5-sonnet", true);

        String json = JsonUtils.getMapper().writeValueAsString(withMeta);
        AssistantMessage back = JsonUtils.getMapper().readValue(json, AssistantMessage.class);

        assertEquals(Boolean.TRUE, back.isVirtual());
        assertEquals("req-123", back.requestId());
        assertEquals("claude-3-5-sonnet", back.advisorModel());
        assertEquals(Boolean.TRUE, back.isMeta());

        AssistantMessage ordinary = MessageFactory.createAssistantMessage("ok");
        var ordinaryJson = JsonUtils.getMapper().valueToTree(ordinary);
        assertFalse(ordinaryJson.has("isVirtual"));
        assertFalse(ordinaryJson.has("requestId"));
        assertFalse(ordinaryJson.has("advisorModel"));
        assertFalse(ordinaryJson.has("isMeta"));
    }


    @Test
    void userEnvelopeFieldsRoundTripAndOmitWhenAbsent() throws Exception {
        UserMessage withMeta = new UserMessage(
            "u", MessageContent.ofText("hi"),
            false, false, null, MessageOrigin.USER, null, null, null, null, null, null, null,
            true, Map.of("_meta", Map.of("key", "v"), "structuredContent", Map.of("x", 1)),
            true);

        String json = JsonUtils.getMapper().writeValueAsString(withMeta);
        UserMessage back = JsonUtils.getMapper().readValue(json, UserMessage.class);

        assertEquals(Boolean.TRUE, back.isVirtual());
        assertEquals(Boolean.TRUE, back.isVisibleInTranscriptOnly());
        assertEquals("v", ((Map<?, ?>) back.mcpMeta().get("_meta")).get("key"));

        UserMessage ordinary = MessageFactory.createUserMessage("ok");
        var ordinaryJson = JsonUtils.getMapper().valueToTree(ordinary);
        assertFalse(ordinaryJson.has("isVirtual"));
        assertFalse(ordinaryJson.has("mcpMeta"));
        assertFalse(ordinaryJson.has("isVisibleInTranscriptOnly"));
    }
}
