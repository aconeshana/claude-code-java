package com.claudecode.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Consumer-owned boundary for the gateway's custom model catalogue.
 *
 * <p>The gateway must not depend on {@code claude-code-api} (where
 * {@code CustomModelJsonStore} lives) or bind to {@code claude-code-core}'s
 * {@code ModelApiProtocol} enum directly, so this port speaks only strings
 * and JSON: {@code protocol} is one of {@code "anthropic"|"chat"|"responses"}.
 * The CLI composition root implements this port against the same
 * {@code CustomModelCatalog} instance backing the {@code /model} command, so
 * writes from either surface are visible to the other.
 */
public interface GatewayModelsPort {

    /** One custom model row, projected from {@code CustomModelConfig}. Never carries the API key itself. */
    record ModelEntry(
        String modelName,
        String protocol,
        String baseUrl,
        boolean hasApiKey,
        Map<String, String> headers,
        Long contextWindow,
        /** {@code null} = unconfigured (assume multimodal); {@code false} = text-only. */
        Boolean multimodal) {

        /** Pre-multimodal projection — defaults the flag to unconfigured. */
        public ModelEntry(
                String modelName, String protocol, String baseUrl, boolean hasApiKey,
                Map<String, String> headers, Long contextWindow) {
            this(modelName, protocol, baseUrl, hasApiKey, headers, contextWindow, null);
        }
    }

    /** Every custom model known to this process. */
    default List<ModelEntry> list() { return List.of(); }

    /**
     * Adds or updates one custom model. {@code apiKey} carries a three-way
     * signal: a missing node keeps the existing key (edit without touching
     * credentials), an explicit JSON {@code null} clears it, and a text node
     * sets a new value.
     */
    default void save(String modelName, String protocol, String baseUrl,
            JsonNode apiKey, Map<String, String> headers, Long contextWindow) {
        save(modelName, protocol, baseUrl, apiKey, headers, contextWindow, null);
    }

    /**
     * {@link #save} with the multimodal flag: {@code null} leaves it
     * unconfigured (assume multimodal), {@code false} marks the endpoint
     * text-only so image content routes to the image model.
     */
    default void save(String modelName, String protocol, String baseUrl,
            JsonNode apiKey, Map<String, String> headers, Long contextWindow,
            Boolean multimodal) {
        throw new UnsupportedOperationException("custom models are not configured");
    }

    /** Removes the model named {@code modelName}; false when absent. */
    default boolean remove(String modelName) { return false; }
}
