package com.claudecode.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;


class TurnDurationMessageTest {

    @Test
    void factoryCarriesDurationAndPreSentinelMessageCountWithoutSyntheticText() {
        SystemMessage message = MessageFactory.createTurnDurationMessage(155L, 8);

        assertEquals("turn_duration", message.subtype());
        assertEquals(155L, message.durationMs());
        assertEquals(8, message.messageCount());
        assertNull(message.level());
        assertNull(message.content());

        JsonNode json = JsonUtils.getMapper().valueToTree(message);
        assertEquals(155L, json.path("durationMs").asLong());
        assertEquals(8, json.path("messageCount").asInt());
        assertFalse(json.has("level"));
        assertFalse(json.has("content"));
    }

    @Test
    void releasedOptionalBudgetAndBriefFieldsRoundTrip() throws Exception {
        SystemMessage message = MessageFactory.createTurnDurationMessage(
            31_000L, 8, 2, 1, 12_500L, 20_000L, 2, 3);

        JsonNode json = JsonUtils.getMapper().valueToTree(message);
        assertEquals(12_500L, json.path("budgetTokens").asLong());
        assertEquals(20_000L, json.path("budgetLimit").asLong());
        assertEquals(2, json.path("budgetNudges").asInt());
        assertEquals(3, json.path("briefHiddenCount").asInt());

        SystemMessage restored = JsonUtils.getMapper().treeToValue(json, SystemMessage.class);
        assertEquals(message, restored);
    }
}
