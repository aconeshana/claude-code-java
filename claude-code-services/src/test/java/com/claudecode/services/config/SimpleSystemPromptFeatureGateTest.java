package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.serialization.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleSystemPromptFeatureGateTest {

    @Test
    void readsReleasedGrowthBookModelList() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_velvet_cascade":{
              "models":["glm-5", "experimental-model", ""]
            }}}
            """);

        assertEquals(List.of("glm-5", "experimental-model"),
            SimpleSystemPromptFeatureGate.evaluate(Map.of(), root));
    }

    @Test
    void malformedOrMissingFeatureReturnsEmptyList() throws Exception {
        assertEquals(List.of(), SimpleSystemPromptFeatureGate.evaluate(Map.of(), null));
        assertEquals(List.of(), SimpleSystemPromptFeatureGate.evaluate(Map.of(),
            JsonUtils.getMapper().readTree("""
                {"cachedGrowthBookFeatures":{"tengu_velvet_cascade":true}}
                """)));
    }

    @Test
    void privacyAndCloudProviderSignalsDisableCachedRollout() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_velvet_cascade":{
              "models":["glm"]
            }}}
            """);

        assertEquals(List.of(), SimpleSystemPromptFeatureGate.evaluate(
            Map.of("DISABLE_TELEMETRY", "0"), root));
        assertEquals(List.of(), SimpleSystemPromptFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_USE_BEDROCK", "true"), root));
        assertEquals(List.of(), SimpleSystemPromptFeatureGate.evaluate(
            Map.of("NODE_ENV", "test"), root));
    }
}
