package com.claudecode.services.model;

import com.claudecode.api.ApiConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalContextWindowPolicyTest {

    @Test
    void explicitOneMillionTagWinsForUnknownModels() {
        assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
            "glm-5.2[1m]", ApiConfig.ApiProvider.ANTHROPIC,
            "https://custom.example", false));
    }

    @Test
    void disableEnvironmentOverridesExplicitAndNativeOneMillionModels() {
        assertEquals(200_000L, GoalContextWindowPolicy.contextWindow(
            "claude-sonnet-5[1m]", ApiConfig.ApiProvider.ANTHROPIC,
            "https://api.anthropic.com", true));
    }

    @Test
    void directAnthropicNativeModelsUseOneMillionWithoutTag() {
        for (String model : new String[]{
                "claude-sonnet-5", "claude-opus-5", "claude-opus-4-7", "claude-opus-4-8",
                "claude-fable-5", "claude-mythos-5", "claude-mythos-preview"}) {
            assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
                model, ApiConfig.ApiProvider.ANTHROPIC,
                "https://api.anthropic.com", false), model);
        }
    }

    @Test
    void customAnthropicBaseUrlDoesNotAssumeNativeOneMillion() {
        assertEquals(200_000L, GoalContextWindowPolicy.contextWindow(
            "claude-opus-4-8", ApiConfig.ApiProvider.ANTHROPIC,
            "https://gateway.example", false));
        assertEquals(200_000L, GoalContextWindowPolicy.contextWindow(
            "glm-5.2", ApiConfig.ApiProvider.ANTHROPIC,
            "https://gateway.example", false));
    }

    @Test
    void generationFiveModelsAreNativeOneMillionOnSupportedJavaThirdPartyProviders() {
        assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
            "us.anthropic.claude-sonnet-5", ApiConfig.ApiProvider.BEDROCK,
            null, false));
        assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
            "claude-sonnet-5", ApiConfig.ApiProvider.VERTEX,
            null, false));
        assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
            "anthropic.claude-opus-5", ApiConfig.ApiProvider.BEDROCK,
            null, false));
        assertEquals(1_000_000L, GoalContextWindowPolicy.contextWindow(
            "claude-opus-5", ApiConfig.ApiProvider.VERTEX,
            null, false));
        assertEquals(200_000L, GoalContextWindowPolicy.contextWindow(
            "us.anthropic.claude-opus-4-8", ApiConfig.ApiProvider.BEDROCK,
            null, false));
    }

    @Test
    void gpt56Uses372kRegardlessOfOpenAiGatewayAndOneMillionDisableFlag() {
        assertEquals(372_000L, GoalContextWindowPolicy.contextWindow(
            "gpt-5.6-sol", ApiConfig.ApiProvider.OPENAI_COMPAT,
            "https://gateway.example/v1", false));
        assertEquals(372_000L, GoalContextWindowPolicy.contextWindow(
            "gpt-5.6-sol", ApiConfig.ApiProvider.OPENAI_COMPAT,
            "https://gateway.example/v1", true));
    }
}
