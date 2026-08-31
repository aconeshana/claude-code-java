package com.claudecode.cli;

import com.claudecode.api.AnthropicSdkClient;
import com.claudecode.api.BedrockClient;
import com.claudecode.api.LlmClient;
import com.claudecode.api.VertexClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ConfigLoader#createLlmClient}/{@link ConfigLoader#createStreamingClient}
 * route through {@link com.claudecode.api.LlmClientFactory} per the provider
 * resolved by {@link com.claudecode.api.ApiProviderResolver}, and that the
 * default (no env vars set) path is unchanged — still Anthropic/x-api-key.
 */
class ConfigLoaderTest {

/** Env-injecting subclass — matches the "Visible for testing" seam ConfigLoader already exposes. */
    private static ConfigLoader withEnv(Map<String, String> env) {
        return new ConfigLoader() {
            @Override
            String getEnvironmentVariable(String name) {
                return env.get(name);
            }

            @Override
            String getStoredApiKey() {
                return null;
            }
        };
    }

    @Test
    void missingAnthropicCredentialsRemainAbsentWithoutRejectingStartup() {
        ConfigLoader.Credentials credentials = withEnv(Map.of()).resolveCredentials(null);

        assertNull(credentials.apiKey());
        assertNull(credentials.authToken());
    }

    @Test
    void defaultProvider_isAnthropic_whenNoEnvSet() {
        ConfigLoader loader = withEnv(Map.of());
        LlmClient client = loader.createLlmClient("sk-test", null, "claude-sonnet-4-6", null);
        assertInstanceOf(AnthropicSdkClient.class, client);
    }

    @Test
    void bedrockEnv_selectsBedrockClient() {
        ConfigLoader loader = withEnv(Map.of("CLAUDE_CODE_USE_BEDROCK", "1"));
        LlmClient client = loader.createLlmClient(null, null, "claude-sonnet-4-6", null);
        assertInstanceOf(BedrockClient.class, client);
    }

    @Test
    void bedrockEnv_regionFallsBackToAwsDefaultRegion_thenUsEast1() {
        ConfigLoader loaderNoRegion = withEnv(Map.of("CLAUDE_CODE_USE_BEDROCK", "1"));
        LlmClient client = loaderNoRegion.createLlmClient(null, null, "m", null);
        assertEquals("us-east-1", ((BedrockClient) client).getRegion());

        ConfigLoader loaderWithDefault = withEnv(Map.of(
            "CLAUDE_CODE_USE_BEDROCK", "1", "AWS_DEFAULT_REGION", "eu-west-1"));
        LlmClient client2 = loaderWithDefault.createLlmClient(null, null, "m", null);
        assertEquals("eu-west-1", ((BedrockClient) client2).getRegion());

        ConfigLoader loaderWithRegion = withEnv(Map.of(
            "CLAUDE_CODE_USE_BEDROCK", "1", "AWS_REGION", "ap-south-1",
            "AWS_DEFAULT_REGION", "eu-west-1"));
        LlmClient client3 = loaderWithRegion.createLlmClient(null, null, "m", null);
        assertEquals("ap-south-1", ((BedrockClient) client3).getRegion());
    }

    @Test
    void vertexEnv_selectsVertexClient_whenBedrockUnset() {
        ConfigLoader loader = withEnv(Map.of("CLAUDE_CODE_USE_VERTEX", "1"));
        LlmClient client = loader.createLlmClient(null, null, "claude-sonnet-4-6", null);
        assertInstanceOf(VertexClient.class, client);
    }

    @Test
    void vertexEnv_regionDefaultsToUsEast5() {
        ConfigLoader loader = withEnv(Map.of("CLAUDE_CODE_USE_VERTEX", "1"));
        LlmClient client = loader.createLlmClient(null, null, "m", null);
        assertEquals("us-east5", ((VertexClient) client).getLocation());
    }

    @Test
    void bedrockTakesPrecedenceOverVertex_whenBothSet() {
        ConfigLoader loader = withEnv(Map.of(
            "CLAUDE_CODE_USE_BEDROCK", "1", "CLAUDE_CODE_USE_VERTEX", "1"));
        LlmClient client = loader.createLlmClient(null, null, "m", null);
        assertInstanceOf(BedrockClient.class, client);
    }

    @Test
    void createStreamingClient_defaultPath_doesNotThrow() {
        ConfigLoader loader = withEnv(Map.of());
        assertNotNull(loader.createStreamingClient("sk-test", null, "claude-sonnet-4-6", null));
    }

    @Test
    void createStreamingClient_bedrockPath_doesNotThrow() {
        ConfigLoader loader = withEnv(Map.of("CLAUDE_CODE_USE_BEDROCK", "1"));
        assertNotNull(loader.createStreamingClient(null, null, "claude-sonnet-4-6", null));
    }
}
