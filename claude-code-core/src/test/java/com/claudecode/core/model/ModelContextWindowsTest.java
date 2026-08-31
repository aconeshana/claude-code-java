package com.claudecode.core.model;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelContextWindowsTest {

    @Test
    void gpt56FamilyUses372kAcrossBuiltInAndGatewayIds() {
        assertEquals(372_000L, ModelContextWindows.defaultContextWindow("gpt-5.6"));
        assertEquals(372_000L, ModelContextWindows.defaultContextWindow("gpt-5.6-sol"));
        assertEquals(372_000L,
            ModelContextWindows.defaultContextWindow("openai/GPT_5_6_preview"));
        assertTrue(ModelContextWindows.isGpt56("company-gpt-5.6-sol-proxy"));
    }

    @Test
    void nearbyModelVersionsDoNotAccidentallyMatch() {
        assertFalse(ModelContextWindows.isGpt56("gpt-5.5"));
        assertFalse(ModelContextWindows.isGpt56("gpt-5.60"));
        assertEquals(200_000L, ModelContextWindows.defaultContextWindow("gpt-5.60"));
    }

    @Test
    void explicitOneMillionSuffixWinsOverGpt56Default() {
        assertEquals(1_000_000L,
            ModelContextWindows.defaultContextWindow("gpt-5.6-sol[1m]"));
    }

    @Test
    void opusFiveUsesItsNativeOneMillionContextWindow() {
        assertEquals(1_000_000L,
            ModelContextWindows.defaultContextWindow("claude-opus-5"));
    }

    @Test
    void customModelUsesBuiltInDefaultOnlyWhenWindowIsAbsent() {
        CustomModelConfig implicit = new CustomModelConfig(
            "gpt-5.6-sol", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", null, Map.of());
        CustomModelConfig explicit = new CustomModelConfig(
            "gpt-5.6-sol", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", null, Map.of(), 400_000L);

        assertEquals(372_000L, implicit.effectiveContextWindow());
        assertEquals(400_000L, explicit.effectiveContextWindow());
    }
}
