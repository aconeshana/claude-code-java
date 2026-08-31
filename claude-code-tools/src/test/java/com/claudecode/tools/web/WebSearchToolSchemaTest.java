package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolSchemaTest {

    @Test
    void schema_hasCanonicalTsFieldNames() {
        var schema = new WebSearchTool(null).inputSchema();
        var props = schema.get("properties");

        assertTrue(props.has("query"),           "must have query");
        assertTrue(props.has("allowed_domains"), "must have allowed_domains (TS canonical)");
        assertTrue(props.has("blocked_domains"), "must have blocked_domains (TS canonical)");
        // old deny_domains must NOT appear (replaced by blocked_domains)
        assertFalse(props.has("deny_domains"),   "deny_domains removed — use blocked_domains");
    }

    @Test
    void schema_queryMinLength2() {
        var queryProp = new WebSearchTool(null).inputSchema().get("properties").get("query");
        assertEquals(2, queryProp.get("minLength").asInt());
    }

    @Test
    void schema_allowedAndBlockedAreArrays() {
        var props = new WebSearchTool(null).inputSchema().get("properties");
        assertEquals("array", props.get("allowed_domains").get("type").asText());
        assertEquals("array", props.get("blocked_domains").get("type").asText());
    }

    @Test
    void schema_onlyQueryRequired() {
        var required = new WebSearchTool(null).inputSchema().get("required");
        assertEquals(1, required.size());
        assertEquals("query", required.get(0).asText());
    }

    @Test
    void schema_rejectsUnknownFieldsLikeTsStrictObject() {
        assertFalse(new WebSearchTool(null).inputSchema()
            .path("additionalProperties").asBoolean(true));
    }

    @Test
    void description_includesCriticalSections() {
        String d = new WebSearchTool(null).description();
        assertTrue(Strings.CS.contains(d, "Sources:"),
            "must include the Sources: mandatory note (197 wording)");
        assertTrue(Strings.CS.contains(d, "Domain filtering is supported"), d);
        assertTrue(Strings.CS.contains(d, "The current month is"), "month line from the 197 capture");
    }
}
