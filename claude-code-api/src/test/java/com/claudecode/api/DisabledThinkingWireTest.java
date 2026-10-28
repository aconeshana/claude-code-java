package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The first-party wire only carries {@code thinking:{type:"disabled"}} for models that
 * 2.1.236 {@code KDr} lets disable thinking; everything else omits the field.
 */
class DisabledThinkingWireTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "claude-opus-4-0", "claude-opus-4-20250514", "claude-opus-4-1", "claude-opus-4-5",
        "claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5",
        "anthropic.claude-opus-5-5", "claude-sonnet-4-20250514", "claude-sonnet-4-5",
        "claude-sonnet-4-6", "claude-sonnet-5", "anthropic.claude-sonnet-5-5",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0"})
    void knownFamiliesStillSendDisabledThinking(String model) throws IOException {
        JsonNode body = serialize(model, null);

        assertEquals("disabled", body.path("thinking").path("type").asText(), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "anthropic.claude-haiku-5-5", "claude-fable-5", "claude-mythos-5",
        "deepseek-v4-flash", "glm-5.3", "claude-3-5-haiku-20241022"})
    void otherModelsOmitDisabledThinking(String model) throws IOException {
        JsonNode body = serialize(model, null);

        assertFalse(body.has("thinking"), model);
    }

    @Test
    void omittedThinkingDemotesAForcedToolChoiceLikeActiveThinking() throws IOException {
        JsonNode body = serialize("anthropic.claude-haiku-5-5",
            CreateMessageRequest.ToolChoice.forTool("classify"));

        assertEquals("auto", body.path("tool_choice").path("type").asText());
        assertFalse(body.path("tool_choice").has("name"));
    }

    @Test
    void sentDisabledThinkingKeepsTheForcedToolChoice() throws IOException {
        JsonNode body = serialize("claude-sonnet-4-6",
            CreateMessageRequest.ToolChoice.forTool("classify"));

        assertEquals("tool", body.path("tool_choice").path("type").asText());
        assertEquals("classify", body.path("tool_choice").path("name").asText());
    }

    @Test
    void modelsWithoutThinkingKeepTheForcedToolChoice() throws IOException {
        JsonNode body = serialize("claude-3-5-haiku-20241022",
            CreateMessageRequest.ToolChoice.forTool("classify"));

        assertFalse(body.has("thinking"));
        assertEquals("tool", body.path("tool_choice").path("type").asText());
    }

    @Test
    void adaptiveThinkingIsUntouched() throws IOException {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("anthropic.claude-haiku-5-5")
            .maxTokens(1024)
            .messages(List.of())
            .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
            .build();

        JsonNode body = JsonUtils.parseTree(AnthropicSdkClient.serializeWithCacheControl(request));

        assertTrue(body.has("thinking"));
        assertEquals("adaptive", body.path("thinking").path("type").asText());
    }

    private static JsonNode serialize(String model, CreateMessageRequest.ToolChoice toolChoice)
            throws IOException {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model(model)
            .maxTokens(1024)
            .messages(List.of())
            .toolChoice(toolChoice)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .build();
        return JsonUtils.parseTree(AnthropicSdkClient.serializeWithCacheControl(request));
    }
}
