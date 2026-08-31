package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


class ExtraMetadataTest {

    @Test
    void parsesJsonObjectPayload() {
        ObjectNode parsed = ExtraMetadata.parse("{\"team\":\"atlas\",\"region\":\"us\"}");

        assertNotNull(parsed);
        assertEquals("atlas", parsed.get("team").asText());
        assertEquals("us", parsed.get("region").asText());
    }

    @Test
    void rejectsNonObjectPayloads() {
        assertNull(ExtraMetadata.parse(null));
        assertNull(ExtraMetadata.parse("  "));
        assertNull(ExtraMetadata.parse("[1,2]"), "TS rejects arrays explicitly");
        assertNull(ExtraMetadata.parse("\"just a string\""));
        assertNull(ExtraMetadata.parse("not json at all"));
    }

    @Test
    void parseReturnsCloneSoCallersCannotPoisonTheParseCache() {
        String raw = "{\"team\":\"atlas\"}";
        ExtraMetadata.parse(raw).put("team", "other");

        assertEquals("atlas", ExtraMetadata.parse(raw).get("team").asText());
    }

    @Test
    void spreadBeneathIdentityKeysSoIdentityAlwaysWins() {
// matches requestMetadata's setAll(extra) followed by the three.put
        // calls: extra's own values appear, but identity keys override them.
        ObjectNode userId = JsonUtils.getMapper().createObjectNode();
        ObjectNode extra = ExtraMetadata.parse("{\"device_id\":\"EVIL\",\"team\":\"atlas\"}");
        if (extra != null) userId.setAll(extra);
        userId.put("device_id", "REAL_DEVICE");
        userId.put("account_uuid", "");
        userId.put("session_id", "sess-1");

        assertEquals("REAL_DEVICE", userId.get("device_id").asText(),
            "identity key must override a spoofed extra.device_id");
        assertEquals("atlas", userId.get("team").asText(),
            "non-identity extra keys still flow through");
        assertEquals("", userId.get("account_uuid").asText());
        assertEquals("sess-1", userId.get("session_id").asText());
    }
}
