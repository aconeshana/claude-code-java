package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpSkillsFeatureGateTest {

    @Test
    void cachedGrowthBookBooleanEnablesReleasedGate() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_mcp_skills":true}}
            """);

        assertTrue(McpSkillsFeatureGate.evaluate(Map.of(), root));
    }

    @Test
    void analyticsPrivacyAndCloudProviderSignalsForceGateOff() throws Exception {
        var root = JsonUtils.getMapper().readTree("""
            {"cachedGrowthBookFeatures":{"tengu_mcp_skills":true}}
            """);

        assertFalse(McpSkillsFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1"), root));
        assertFalse(McpSkillsFeatureGate.evaluate(Map.of("DISABLE_TELEMETRY", "0"), root));
        assertFalse(McpSkillsFeatureGate.evaluate(Map.of("CLAUDE_CODE_USE_BEDROCK", "true"), root));
        assertFalse(McpSkillsFeatureGate.evaluate(Map.of("NODE_ENV", "test"), root));
    }

    @Test
    void missingOrNonBooleanCacheValueKeepsExternalDefaultOff() throws Exception {
        assertFalse(McpSkillsFeatureGate.evaluate(Map.of(), null));
        assertFalse(McpSkillsFeatureGate.evaluate(Map.of(),
            JsonUtils.getMapper().readTree("""
                {"cachedGrowthBookFeatures":{"tengu_mcp_skills":"true"}}
                """)));
    }
}
