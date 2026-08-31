package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExtraBodyParamsTest {

    @Test
    void parsesJsonObjectPayload() {
        ObjectNode parsed = ExtraBodyParams.parse("{\"speed\":\"fast\",\"top_k\":5}");

        assertNotNull(parsed);
        assertEquals("fast", parsed.get("speed").asText());
        assertEquals(5, parsed.get("top_k").asInt());
    }

    @Test
    void rejectsNonObjectPayloads() {
        assertNull(ExtraBodyParams.parse(null));
        assertNull(ExtraBodyParams.parse("  "));
        assertNull(ExtraBodyParams.parse("[1,2]"), "TS rejects arrays explicitly");
        assertNull(ExtraBodyParams.parse("\"just a string\""));
        assertNull(ExtraBodyParams.parse("not json at all"));
    }

    @Test
    void parseReturnsCloneSoCallersCannotPoisonTheParseCache() {
        String raw = "{\"speed\":\"fast\"}";
        ExtraBodyParams.parse(raw).put("speed", "slow");

        assertEquals("fast", ExtraBodyParams.parse(raw).get("speed").asText());
    }

    @Test
    void escapeHatchOverridesComputedBodyFields() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("model", "claude-opus-4-6");
        root.put("max_tokens", 32_000);

        AnthropicSdkClient.applyExtraBodyParams(
            root, ExtraBodyParams.parse("{\"max_tokens\":1234,\"speed\":\"fast\"}"));

        assertEquals(1234, root.get("max_tokens").asInt(),
            "TS spreads extraBodyParams after every computed field");
        assertEquals("fast", root.get("speed").asText());
        assertEquals("claude-opus-4-6", root.get("model").asText());
    }

    @Test
    void extraBodyModelOverrideCannotRestoreInternalContextTag() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("model", "claude-sonnet-5");

        AnthropicSdkClient.applyExtraBodyParams(
            root, ExtraBodyParams.parse("{\"model\":\"gateway-sonnet[1m]\"}"));
        LlmWireBodyFinalizer.finalizeForApi(root);

        assertEquals("gateway-sonnet", root.get("model").asText());
    }

    @Test
    void outputConfigMergesPerKeyInsteadOfBeingReplaced() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        ObjectNode computed = root.putObject("output_config");
        computed.put("effort", "high");

        AnthropicSdkClient.applyExtraBodyParams(
            root, ExtraBodyParams.parse("{\"output_config\":{\"effort\":\"low\",\"format\":{}}}"));


        // its own assignment when the key is already present — user wins per key,
        // computed keys the user did not set survive.
        assertEquals("low", root.get("output_config").get("effort").asText());
        assertTrue(root.get("output_config").has("format"));
    }

    @Test
    void outputConfigSeedSurvivesWhenNothingWasComputed() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();

        AnthropicSdkClient.applyExtraBodyParams(
            root, ExtraBodyParams.parse("{\"output_config\":{\"effort\":\"max\"}}"));

        assertEquals("max", root.get("output_config").get("effort").asText());
    }

    @Test
    void streamStaysTransportOwned() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("stream", true);

        AnthropicSdkClient.applyExtraBodyParams(root, ExtraBodyParams.parse("{\"stream\":false}"));

        assertTrue(root.get("stream").asBoolean(),
            "TS adds stream after the spread, so the escape hatch cannot flip transport mode");
    }
}
