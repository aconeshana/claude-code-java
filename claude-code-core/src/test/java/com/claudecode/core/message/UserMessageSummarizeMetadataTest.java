package com.claudecode.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class UserMessageSummarizeMetadataTest {

    @Test
    void partialCompactSummaryRoundTripsThe197MetadataShape() throws Exception {
        UserMessage message = new UserMessage(
            "summary", MessageContent.ofText("summary text"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, null, null, null, null, null, null,
            null, null, null, null,
            new SummarizeMetadata(7, "focus on the bug", "from"));

        JsonNode json = JsonUtils.getMapper().valueToTree(message);
        assertEquals(7, json.path("summarizeMetadata").path("messagesSummarized").asInt());
        assertEquals("focus on the bug",
            json.path("summarizeMetadata").path("userContext").asText());
        assertEquals("from", json.path("summarizeMetadata").path("direction").asText());
        assertFalse(json.has("isVisibleInTranscriptOnly"),
            "partial summaries with a kept segment are visible in the normal 197 UI");

        UserMessage restored = JsonUtils.getMapper().treeToValue(json, UserMessage.class);
        assertEquals(message.summarizeMetadata(), restored.summarizeMetadata());
    }

    @Test
    void ordinaryUserMessageDoesNotGainSummarizeMetadata() {
        UserMessage ordinary = new UserMessage("user", MessageContent.ofText("hello"));

        JsonNode json = JsonUtils.getMapper().valueToTree(ordinary);

        assertFalse(json.has("summarizeMetadata"));
        assertNull(ordinary.summarizeMetadata());
    }
}
