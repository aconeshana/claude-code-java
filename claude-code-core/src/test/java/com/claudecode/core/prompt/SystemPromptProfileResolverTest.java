package com.claudecode.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemPromptProfileResolverTest {

    @Test
    void releasedClaudeFamiliesUseLongProfile() {
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "claude-sonnet-4-6", "firstParty", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "claude-opus-4-7", "gateway", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "claude-haiku-4-5-20251001", "anthropicAws", Map.of(), List.of());
    }

    @Test
    void unknownFirstPartyModelUsesHarnessButCloudNamespaceKeepsLongFallback() {
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "glm-5.2", "firstParty", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "glm-5.2", "bedrock", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "glm-5.2", "vertex", Map.of(), List.of());
    }

    @Test
    void leanPromptCapabilitySelectsHarnessAcrossProviderSpecificIds() {
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "anthropic.claude-opus-5", "bedrock", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "us.anthropic.claude-opus-4-8-v1:0", "bedrock", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "publishers/anthropic/models/claude-fable-5@20260801", "vertex",
            Map.of(), List.of());

        assertTrue(SystemPromptProfileResolver.hasCapability(
            "claude-opus-5", "lean_prompt"));
        assertTrue(SystemPromptProfileResolver.hasCapability(
            "claude-opus-4-8", "lean_prompt"));
        assertTrue(SystemPromptProfileResolver.hasCapability(
            "claude-fable-5", "lean_prompt"));
        assertTrue(SystemPromptProfileResolver.hasCapability(
            "claude-fable-5", "fable_5_mitigations"));
        assertFalse(SystemPromptProfileResolver.hasCapability(
            "claude-opus-4-7", "lean_prompt"));
        assertTrue(SystemPromptProfileResolver.usesFable5Mitigations("claude-fable-5"));
        assertTrue(SystemPromptProfileResolver.usesFable5Mitigations("claude-mythos-5"));
        assertFalse(SystemPromptProfileResolver.usesFable5Mitigations("claude-opus-4-8"));
    }

    @Test
    void mythosAndEapIdsAlwaysUseHarnessWithoutAnEnvOverride() {
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "claude-mythos-5", "bedrock", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "claude-sonnet-4-6-eap", "firstParty", Map.of(), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "claude-sonnet-4-6-eap[1m]", "firstParty", Map.of(), List.of());
    }

    @Test
    void explicitEnvironmentOverrideWinsBeforeCapabilityAndFamilyChecks() {
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "claude-sonnet-4-6", "firstParty",
            Map.of("CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT", "1"), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "glm-5.2", "firstParty",
            Map.of("CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT", "false"), List.of());
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "claude-opus-4-8", "firstParty",
            Map.of("CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT", "0"), List.of());
    }

    @Test
    void rolloutPatternsUseCanonicalModelSubstringMatching() {
        assertProfile(SystemPromptProfileResolver.Profile.HARNESS,
            "claude-sonnet-4-6-20260801", "firstParty", Map.of(),
            List.of("sonnet-4-6"));
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            "claude-sonnet-4-6-20260801", "firstParty", Map.of(),
            List.of("opus-4-7"));
    }

    @Test
    void absentModelKeepsReleasedLongDefault() {
        assertProfile(SystemPromptProfileResolver.Profile.LONG,
            null, "firstParty",
            Map.of("CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT", "1"), List.of());
    }

    private static void assertProfile(SystemPromptProfileResolver.Profile expected,
            String model, String provider, Map<String, String> env,
            List<String> rolloutPatterns) {
        assertEquals(expected, SystemPromptProfileResolver.resolve(
            model, provider, env::get, rolloutPatterns));
    }
}
