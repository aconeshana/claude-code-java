package com.claudecode.api;

import okhttp3.OkHttpClient;

/**
 * Factory for creating LLM clients based on provider configuration.
 * Currently only AnthropicSdkClient has a full implementation.
 * Other providers are stubs that throw UnsupportedOperationException.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * Creates an LlmClient for the given configuration.
     */
    public static LlmClient create(ApiConfig config) {
        return switch (config.provider()) {
            case ANTHROPIC -> new AnthropicSdkClient(config.anthropic());
            case OPENAI_COMPAT -> switch (config.openai().protocol()) {
                case OPENAI_CHAT -> new OpenAiCompatClient(config.openai());
                case OPENAI_RESPONSES -> new OpenAiResponsesClient(config.openai());
                case ANTHROPIC -> throw new IllegalArgumentException(
                    "Anthropic protocol requires ApiProvider.ANTHROPIC");
            };
            case BEDROCK -> new BedrockClient(config.bedrock());
            case VERTEX -> new VertexClient(config.vertex());
        };
    }

    /**
     * Creates an LlmClient for the given configuration with a custom OkHttpClient.
     * Used for testing to point requests at a local test server (e.g.
     * {@code mockwebserver3} or a local {@code com.sun.net.httpserver.HttpServer})
     * instead of the shared {@link HttpClientFactory#shared} instance.
     */
    public static LlmClient create(ApiConfig config, OkHttpClient httpClient) {
        if (config.provider() != ApiConfig.ApiProvider.OPENAI_COMPAT) {
            throw new IllegalArgumentException("Custom HTTP client only supported for OPENAI_COMPAT");
        }
        return switch (config.openai().protocol()) {
            case OPENAI_CHAT -> new OpenAiCompatClient(config.openai(), httpClient);
            case OPENAI_RESPONSES -> new OpenAiResponsesClient(config.openai(), httpClient);
            case ANTHROPIC -> throw new IllegalArgumentException(
                "Anthropic protocol requires ApiProvider.ANTHROPIC");
        };
    }
}
