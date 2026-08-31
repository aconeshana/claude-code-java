package com.claudecode.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the model/provider gate for mid-conversation system messages. */
class MidConversationSystemSupportTest {

    @Test
    void releasedClaudeFamiliesKeepAttachmentsAsUserReminders() {
        assertFalse(MidConversationSystemSupport.isEnabled(
            "claude-sonnet-4-6", false, false, false, true));
        assertFalse(MidConversationSystemSupport.isEnabled(
            "claude-opus-4-6", false, false, false, true));
        assertFalse(MidConversationSystemSupport.isEnabled(
            "claude-haiku-4-5", false, false, false, true));
        assertFalse(MidConversationSystemSupport.isEnabled(
            "claude-3-7-sonnet", false, false, false, true));
    }

    @Test
    void unknownFirstPartyAliasUsesMidConversationSystem() {
        assertTrue(MidConversationSystemSupport.isEnabled(
            "claude-opus-5", false, false, false, true));
        assertTrue(MidConversationSystemSupport.isEnabled(
            "glm-5.2", false, false, false, true));
        assertTrue(MidConversationSystemSupport.isEnabled(
            "test-model", false, false, false, true));
    }

    @Test
    void bedrockAndVertexDoNotUseTheFirstPartyFallback() {
        assertFalse(MidConversationSystemSupport.isEnabled(
            "glm-5.2", true, false, false, true));
        assertFalse(MidConversationSystemSupport.isEnabled(
            "glm-5.2", false, true, false, true));
    }

    @Test
    void explicitForceMatchesTheReleasedEscapeHatch() {
        assertTrue(MidConversationSystemSupport.isEnabled(
            "claude-sonnet-4-6", false, false, true, false));
    }

    @Test
    void unknownAliasOnANonFirstPartyGatewayDoesNotUseMidConversationSystem() {

        // (e.g. "deepseek-v4-flash") routed through a third-party gateway
        // baseUrl must not take the first-party fallback branch, or the
        // request gets an interleaved role:"system" message the gateway
        // rejects as an invalid Anthropic Messages API shape.
        assertFalse(MidConversationSystemSupport.isEnabled(
            "deepseek-v4-flash", false, false, false, false));
    }

    @Test
    void publicOverloadDefaultsToFirstPartyWhenNoResolverIsConfigured() {
        MidConversationSystemSupport.configureBaseUrlResolver(null);
        assertTrue(MidConversationSystemSupport.isEnabled("glm-5.2"));
    }

    @Test
    void publicOverloadConsultsTheConfiguredBaseUrlResolverPerModel() {
        MidConversationSystemSupport.configureBaseUrlResolver(model ->
            "deepseek-v4-flash".equals(model)
                ? "https://gateway.example.com/anthropic/api"
                : null);
        try {
            assertFalse(MidConversationSystemSupport.isEnabled("deepseek-v4-flash"));
            assertTrue(MidConversationSystemSupport.isEnabled("glm-5.2"));
        } finally {
            MidConversationSystemSupport.configureBaseUrlResolver(null);
        }
    }
}
