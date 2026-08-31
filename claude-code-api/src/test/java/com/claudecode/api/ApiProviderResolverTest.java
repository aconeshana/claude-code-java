package com.claudecode.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


class ApiProviderResolverTest {

    @Test
    void defaultsToAnthropic_whenNoEnvSet() {
        assertEquals(ApiConfig.ApiProvider.ANTHROPIC, ApiProviderResolver.resolve(null, null));
    }

    @Test
    void bedrockEnvTruthy_selectsBedrock() {
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("1", null));
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("true", null));
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("TRUE", null));
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("yes", null));
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("on", null));
    }

    @Test
    void vertexEnvTruthy_selectsVertex_whenBedrockUnset() {
        assertEquals(ApiConfig.ApiProvider.VERTEX, ApiProviderResolver.resolve(null, "1"));
        assertEquals(ApiConfig.ApiProvider.VERTEX, ApiProviderResolver.resolve("", "true"));
    }

    @Test
    void bedrockTakesPrecedenceOverVertex_whenBothTruthy() {
        assertEquals(ApiConfig.ApiProvider.BEDROCK, ApiProviderResolver.resolve("1", "1"));
    }

    @Test
    void blankOrFalsyEnvValues_fallBackToAnthropic() {
        assertEquals(ApiConfig.ApiProvider.ANTHROPIC, ApiProviderResolver.resolve("", ""));
        assertEquals(ApiConfig.ApiProvider.ANTHROPIC, ApiProviderResolver.resolve("0", "false"));
        assertEquals(ApiConfig.ApiProvider.ANTHROPIC, ApiProviderResolver.resolve("off", "no"));
    }
}
