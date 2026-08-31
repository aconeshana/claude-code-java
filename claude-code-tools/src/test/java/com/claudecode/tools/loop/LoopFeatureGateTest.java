package com.claudecode.tools.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopFeatureGateTest {

    @Test
    void readsAll197LoopFlagsFromGrowthBookCache() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{
              "tengu_kairos_loop_dynamic":true,
              "tengu_kairos_loop_prompt":true,
              "tengu_kairos_loop_keepalive":true,
              "tengu_kairos_loop_persistent":true
            }}
            """);

        LoopFeatureGate gate = LoopFeatureGate.evaluate(Map.of(), root);

        assertTrue(gate.dynamicEnabled());
        assertTrue(gate.defaultPromptEnabled());
        assertTrue(gate.keepaliveEnabled());
        assertTrue(gate.persistentEnabled());
    }

    @Test
    void privacyAndProviderModesDisableCachedFlagsByPresence() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_kairos_loop_dynamic":true}}
            """);

        assertFalse(LoopFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "0"), root).dynamicEnabled());
        assertFalse(LoopFeatureGate.evaluate(
            Map.of("DISABLE_TELEMETRY", "0"), root).dynamicEnabled());
        assertFalse(LoopFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_USE_BEDROCK", "1"), root).dynamicEnabled());
    }

    @Test
    void keepaliveAndPersistentEnvironmentOverridesRemainAvailable() {
        LoopFeatureGate gate = LoopFeatureGate.evaluate(Map.of(
            "CLAUDE_CODE_LOOP_KEEPALIVE", "1",
            "CLAUDE_CODE_LOOP_PERSISTENT", "true"), null);

        assertFalse(gate.dynamicEnabled());
        assertTrue(gate.keepaliveEnabled());
        assertTrue(gate.persistentEnabled());
    }
}
