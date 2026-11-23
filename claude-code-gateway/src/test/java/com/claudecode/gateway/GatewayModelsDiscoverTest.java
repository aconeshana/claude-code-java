package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayModelsDiscoverTest {

    @Test
    void resolveDiscoverUrlsHandlesOpenAiAnthropicAndGeminiShapes() {
        assertEquals(
            List.of("https://api.deepseek.com/v1/models"),
            GatewayModelsHandler.resolveDiscoverUrls("https://api.deepseek.com/v1/", "chat"));
        assertEquals(
            List.of("https://api.example.com/models", "https://api.example.com/v1/models"),
            GatewayModelsHandler.resolveDiscoverUrls("https://api.example.com", "chat"));
        assertEquals(
            List.of("https://api.anthropic.com/v1/models", "https://api.anthropic.com/models"),
            GatewayModelsHandler.resolveDiscoverUrls("https://api.anthropic.com/", "anthropic"));
        assertEquals(
            List.of("https://generativelanguage.googleapis.com/v1beta/models",
                "https://generativelanguage.googleapis.com/models"),
            GatewayModelsHandler.resolveDiscoverUrls(
                "https://generativelanguage.googleapis.com", "gemini"));
        assertEquals(
            List.of("https://relay.example.com/custom/models"),
            GatewayModelsHandler.resolveDiscoverUrls("https://relay.example.com/custom/models", "chat"));
        assertThrows(IllegalArgumentException.class,
            () -> GatewayModelsHandler.resolveDiscoverUrls("ftp://bad.example.com", "chat"));
    }

    @Test
    void parseDiscoveredModelsSupportsOpenAiAndGeminiPayloads() throws Exception {
        JsonNode openAi = JsonUtils.getMapper().readTree("""
            {
              "object": "list",
              "data": [
                { "id": "deepseek-chat", "owned_by": "deepseek", "context_length": 128000 },
                { "id": "deepseek-reasoner", "display_name": "DeepSeek R1", "context_window": 65536 },
                { "id": "deepseek-chat" }
              ]
            }
            """);
        var parsedOpenAi = GatewayModelsHandler.parseDiscoveredModels(openAi);
        assertEquals(2, parsedOpenAi.size());
        assertEquals("deepseek-chat", parsedOpenAi.getFirst().id());
        assertEquals(128000L, parsedOpenAi.getFirst().contextWindow());
        assertEquals("DeepSeek R1", parsedOpenAi.get(1).displayName());

        JsonNode gemini = JsonUtils.getMapper().readTree("""
            {
              "models": [
                { "name": "models/gemini-2.5-pro", "displayName": "Gemini 2.5 Pro", "inputTokenLimit": 1048576 }
              ]
            }
            """);
        var parsedGemini = GatewayModelsHandler.parseDiscoveredModels(gemini);
        assertEquals(1, parsedGemini.size());
        assertEquals("gemini-2.5-pro", parsedGemini.getFirst().id());
        assertEquals("Gemini 2.5 Pro", parsedGemini.getFirst().displayName());
        assertEquals(1048576L, parsedGemini.getFirst().contextWindow());
        assertTrue(GatewayModelsHandler.parseDiscoveredModels(null).isEmpty());
    }
}
