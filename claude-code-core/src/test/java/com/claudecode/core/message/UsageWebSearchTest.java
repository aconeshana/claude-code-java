package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the {@link Usage} record's provider extensions: Anthropic's nested
 * {@code server_tool_use} counts and OpenAI's {@code total_tokens} snapshot
 * must parse, survive their intended lifecycle, and remain backward compatible.
 */
class UsageWebSearchTest {

    @Test
    void parsesNestedServerToolUseFromApiJson() throws Exception {
        String json = """
            {
              "input_tokens": 100,
              "output_tokens": 50,
              "cache_creation_input_tokens": 10,
              "cache_read_input_tokens": 5,
              "server_tool_use": { "web_search_requests": 4, "web_fetch_requests": 2 }
            }
            """;
        Usage u = JsonUtils.getMapper().readValue(json, Usage.class);
        assertEquals(100, u.inputTokens());
        assertEquals(4, u.webSearchRequests());
        assertEquals(2, u.serverToolUse().webFetchRequests());
    }

    @Test
    void missingServerToolUseDefaultsToZero() throws Exception {
        String json = "{\"input_tokens\": 1, \"output_tokens\": 1}";
        Usage u = JsonUtils.getMapper().readValue(json, Usage.class);
        assertEquals(0, u.webSearchRequests());
        assertNotNull(u.serverToolUse(), "serverToolUse must never be null");
    }

    @Test
    void fourArgConstructor_stillWorks_zeroWebSearch() {
        Usage u = new Usage(10, 20, 5, 3);
        assertEquals(0, u.webSearchRequests());
        assertSame(Usage.ServerToolUse.ZERO, u.serverToolUse());
    }

    @Test
    void addSumsWebSearchCounts() {
        Usage a = new Usage(1, 1, 0, 0, new Usage.ServerToolUse(2, 1));
        Usage b = new Usage(1, 1, 0, 0, new Usage.ServerToolUse(3, 4));
        Usage sum = a.add(b);
        assertEquals(5, sum.webSearchRequests());
        assertEquals(5, sum.serverToolUse().webFetchRequests());
    }

    @Test
    void emptyHasZeroWebSearch() {
        assertEquals(0, Usage.EMPTY.webSearchRequests());
    }

    @Test
    void preservesOfficial197UsageEnvelopeAcrossFinalDelta() throws Exception {
        Usage start = JsonUtils.getMapper().readValue("""
            {
              "input_tokens": 100,
              "output_tokens": 0,
              "cache_creation_input_tokens": 7,
              "cache_read_input_tokens": 11,
              "server_tool_use": {"web_search_requests": 2, "web_fetch_requests": 1},
              "service_tier": "priority",
              "cache_creation": {
                "ephemeral_1h_input_tokens": 3,
                "ephemeral_5m_input_tokens": 4
              },
              "inference_geo": "us-west",
              "iterations": [{"type": "test"}],
              "speed": "fast"
            }
            """, Usage.class);
        Usage delta = JsonUtils.getMapper().readValue("""
            {
              "output_tokens": 12,
              "service_tier": "batch",
              "inference_geo": "eu-central"
            }
            """, Usage.class);

        Usage finalUsage = Usage.EMPTY.updateCumulative(start).updateCumulative(delta);

        assertEquals(100, finalUsage.inputTokens());
        assertEquals(12, finalUsage.outputTokens());
        assertEquals("batch", finalUsage.serviceTier());
        assertEquals(3, finalUsage.cacheCreation().ephemeral1hInputTokens());
        assertEquals("eu-central", finalUsage.inferenceGeo());
        assertEquals("test", finalUsage.iterations().getFirst().path("type").asText());
        assertEquals("fast", finalUsage.speed());
    }

    @Test
    void derivesCacheCreationTotalFromReleasedDetailFields() throws Exception {
        Usage start = new Usage(10, 0, 9, 0, Usage.ServerToolUse.ZERO,
            "standard", new Usage.CacheCreation(4, 5), "us", List.of(), "standard");
        Usage detailOnlyDelta = JsonUtils.getMapper().readValue("""
            {
              "output_tokens": 12,
              "cache_creation": {
                "ephemeral_1h_input_tokens": 30,
                "ephemeral_5m_input_tokens": 20
              }
            }
            """, Usage.class);

        Usage result = start.updateCumulative(detailOnlyDelta);

        assertEquals(50, result.cacheCreationInputTokens());
        assertEquals(30, result.cacheCreation().ephemeral1hInputTokens());
        assertEquals(20, result.cacheCreation().ephemeral5mInputTokens());
    }

    @Test
    void zeroCacheDetailsDoNotEraseMessageStartDetails() throws Exception {
        Usage start = new Usage(10, 0, 9, 0, Usage.ServerToolUse.ZERO,
            "standard", new Usage.CacheCreation(4, 5), "us", List.of(), "standard");
        Usage zeroDelta = JsonUtils.getMapper().readValue("""
            {"output_tokens": 2,
             "cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0}}
            """, Usage.class);

        Usage result = start.updateCumulative(zeroDelta);

        assertEquals(9, result.cacheCreationInputTokens());
        assertEquals(4, result.cacheCreation().ephemeral1hInputTokens());
        assertEquals(5, result.cacheCreation().ephemeral5mInputTokens());
    }

    @Test
    void emptyMetadataInLaterDeltaDoesNotEraseTheLatestUsefulValues() {
        Usage start = new Usage(10, 0, 0, 0, Usage.ServerToolUse.ZERO,
            "priority", Usage.CacheCreation.ZERO, "us-west",
            List.of(JsonUtils.parseTree("{\"type\":\"iteration\"}")), "fast");
        Usage emptyDelta = new Usage(0, 2, 0, 0, Usage.ServerToolUse.ZERO,
            "", Usage.CacheCreation.ZERO, "", List.of(), "");

        Usage result = start.updateCumulative(emptyDelta);

        assertEquals("priority", result.serviceTier());
        assertEquals("us-west", result.inferenceGeo());
        assertEquals("fast", result.speed());
        assertEquals("iteration", result.iterations().getFirst().path("type").asText());
    }

    @Test
    void openAiTotalTokensRoundTripsButAnthropicUsageOmitsIt() throws Exception {
        Usage openAi = new Usage(20, 8, 0, 12, 29L);
        String openAiJson = JsonUtils.getMapper().writeValueAsString(openAi);
        Usage restored = JsonUtils.getMapper().readValue(openAiJson, Usage.class);

        assertTrue(Strings.CS.contains(openAiJson, "\"total_tokens\":29"));
        assertEquals(29L, restored.reportedTotalTokens());

        String anthropicJson = JsonUtils.getMapper().writeValueAsString(
            new Usage(20, 8, 3, 12));
        assertFalse(Strings.CS.contains(anthropicJson, "total_tokens"));
    }
}
