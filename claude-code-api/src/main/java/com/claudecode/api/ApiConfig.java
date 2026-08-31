package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.ModelApiProtocol;

import java.util.Map;

/**
 * Configuration for API client creation.
 */
public record ApiConfig(
        ApiProvider provider,
        AnthropicConfig anthropic,
        OpenAiConfig openai,
        BedrockConfig bedrock,
        VertexConfig vertex
) {

    public enum ApiProvider {
        ANTHROPIC,
        OPENAI_COMPAT,
        BEDROCK,
        VERTEX
    }

    /**
     * Creates a config for the Anthropic provider.
     */
    public static ApiConfig anthropic(String apiKey, String model) {
        return new ApiConfig(
                ApiProvider.ANTHROPIC,
                new AnthropicConfig(apiKey, null, model, null),
                null, null, null
        );
    }

    /**
     * Anthropic provider config. {@code apiKey} and {@code authToken} are
     * independent, both-optional credentials — Preserves the compatibility rule where the
     * {@code x-api-key} header comes from {@code getAnthropicApiKey} and the
     * {@code Authorization: Bearer} header comes separately from
     * {@code ANTHROPIC_AUTH_TOKEN} (
     * {@code configureApiKeyHeaders}). Either or both may be present.
     */
    public record AnthropicConfig(
            String apiKey,
            String authToken,
            String model,
            String baseUrl,
            Map<String, String> headers
    ) {
        public AnthropicConfig(String apiKey, String authToken, String model, String baseUrl) {
            this(apiKey, authToken, model, baseUrl, Map.of());
        }

        public AnthropicConfig {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    @Explanation("Adds Chat/Responses protocol selection and custom headers")
    public record OpenAiConfig(
            String apiKey,
            String model,
            String baseUrl,
            ModelApiProtocol protocol,
            Map<String, String> headers
    ) {
        public OpenAiConfig(String apiKey, String model, String baseUrl) {
            this(apiKey, model, baseUrl, ModelApiProtocol.OPENAI_CHAT, Map.of());
        }

        public OpenAiConfig {
            protocol = protocol == null ? ModelApiProtocol.OPENAI_CHAT : protocol;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        @Override
        public String toString() {
            return "OpenAiConfig[apiKey=<redacted>, model=" + model + ", baseUrl=" + baseUrl
                + ", protocol=" + protocol + ", headers=" + headers.keySet() + "]";
        }
    }

    public record BedrockConfig(
            String region,
            String model
    ) {}

    public record VertexConfig(
            String projectId,
            String location,
            String model
    ) {}
}
