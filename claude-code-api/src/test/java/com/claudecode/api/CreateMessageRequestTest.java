package com.claudecode.api;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.feature.FeatureGate;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CreateMessageRequest serialization and builder.
 */
class CreateMessageRequestTest {

    @Test
    void builderCreatesValidRequest() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .systemPrompt("You are a helpful assistant.")
                .messages(List.of(
                        new CreateMessageRequest.RequestMessage("user", "Hello")))
                .stream(true)
                .build();

        assertEquals("claude-sonnet-4-20250514", request.model());
        assertEquals(1024, request.maxTokens());
        assertEquals("You are a helpful assistant.", request.systemPrompt());
        assertEquals(1, request.messages().size());
        assertTrue(request.stream());
    }

    @Test
    void serializesToCorrectJsonFormat() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(4096)
                .systemPrompt("System prompt")
                .messages(List.of(
                        new CreateMessageRequest.RequestMessage("user", "Hello, Claude!")))
                .stream(true)
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertEquals("claude-sonnet-4-20250514", node.get("model").asText());
        assertEquals(4096, node.get("max_tokens").asInt());
        assertEquals("System prompt", node.get("system").asText());
        assertTrue(node.get("stream").asBoolean());
        assertTrue(node.get("messages").isArray());
        assertEquals(1, node.get("messages").size());
        assertEquals("user", node.get("messages").get(0).get("role").asText());
        assertEquals("Hello, Claude!", node.get("messages").get(0).get("content").asText());
    }

    @Test
    void nullFieldsAreOmittedInJson() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .messages(List.of())
                .stream(true)
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        // Null fields should not appear
        assertFalse(node.has("system") && !node.get("system").isNull(),
                "system should be null or absent");
        assertFalse(node.has("tools") && !node.get("tools").isNull(),
                "tools should be null or absent");
        assertFalse(node.has("temperature") && !node.get("temperature").isNull(),
                "temperature should be null or absent");
    }

    @Test
    void toolDefinitionSerializesCorrectly() {
        JsonNode schema = JsonUtils.parseTree("""
                {"type": "object", "properties": {"command": {"type": "string"}}}
                """);

        CreateMessageRequest.ToolDefinition tool = new CreateMessageRequest.ToolDefinition(
                "bash", "Execute a shell command", schema);

        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .messages(List.of())
                .tools(List.of(tool))
                .stream(true)
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertTrue(node.has("tools"));
        assertEquals(1, node.get("tools").size());
        JsonNode toolNode = node.get("tools").get(0);
        assertEquals("bash", toolNode.get("name").asText());
        assertEquals("Execute a shell command", toolNode.get("description").asText());
        assertTrue(toolNode.has("input_schema"));
    }

    @Test
    void serverToolSerializesAnthropicWireFieldsWithoutInputSchema() {
        var tool = CreateMessageRequest.ToolDefinition.serverTool(
            "web_search_20250305", "web_search", 8,
            List.of("docs.example.com"), List.of("blocked.example.com"));

        JsonNode node = JsonUtils.parseTree(JsonUtils.toJson(tool));

        assertEquals("web_search_20250305", node.path("type").asText());
        assertEquals("web_search", node.path("name").asText());
        assertEquals(8, node.path("max_uses").asInt());
        assertEquals("docs.example.com", node.path("allowed_domains").get(0).asText());
        assertEquals("blocked.example.com", node.path("blocked_domains").get(0).asText());
        assertFalse(node.has("input_schema"));
    }

    @Test
    void strictToolMarkerIsModelAndFeatureGated() {
        assertFalse(CreateMessageRequest.supportsStructuredOutputs("claude-sonnet-4-20250514"));
        assertTrue(CreateMessageRequest.supportsStructuredOutputs("claude-sonnet-4-6"));
        assertFalse(CreateMessageRequest.strictToolEnabled("claude-sonnet-4-6", true));

        FeatureGate.withFlags(() -> {
            assertTrue(CreateMessageRequest.strictToolEnabled("claude-sonnet-4-6", true));
            var tool = new CreateMessageRequest.ToolDefinition(
                "Bash", "shell", JsonUtils.parseTree("{\"type\":\"object\"}"),
                null, null, null, null, null, null, Boolean.TRUE);
            JsonNode node = JsonUtils.parseTree(JsonUtils.toJson(tool));
            assertTrue(node.path("strict").asBoolean());
        }, FeatureGate.Flag.STRICT_TOOLS);
    }

    @Test
    void eagerInputStreamingIsOmittedUnlessExplicitlySet() {
        var ordinary = new CreateMessageRequest.ToolDefinition("Bash", "shell", null);
        JsonNode ordinaryJson = JsonUtils.parseTree(JsonUtils.toJson(ordinary));
        assertFalse(ordinaryJson.has("eager_input_streaming"));

        var eager = new CreateMessageRequest.ToolDefinition(
            "Bash", "shell", JsonUtils.parseTree("{\"type\":\"object\"}"),
            null, null, null, null, null, null, null, Boolean.TRUE);
        JsonNode eagerJson = JsonUtils.parseTree(JsonUtils.toJson(eager));
        assertTrue(eagerJson.path("eager_input_streaming").asBoolean());
    }

    @Test
    void builderDefaultValues() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("test-model")
                .build();

        assertEquals(4096, request.maxTokens());
        assertTrue(request.stream());
        assertNotNull(request.messages());
    }

    @Test
    void thinkingConfigEnabledSerializesCorrectly() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(16000)
                .messages(List.of())
                .thinking(CreateMessageRequest.ThinkingConfig.enabled(10000))
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertTrue(node.has("thinking"), "thinking field must be present");
        JsonNode thinking = node.get("thinking");
        assertEquals("enabled", thinking.get("type").asText());
        assertEquals(10000, thinking.get("budget_tokens").asInt());
    }

    @Test
    void thinkingConfigDisabledSerializesCorrectly() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(4096)
                .messages(List.of())
                .thinking(CreateMessageRequest.ThinkingConfig.disabled())
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertTrue(node.has("thinking"));
        assertEquals("disabled", node.get("thinking").get("type").asText());
        assertFalse(node.get("thinking").has("budget_tokens"),
            "budget_tokens must be absent when disabled");
    }

    @Test
    void interleavedThinkingBetaConstantHasCorrectValue() {
        // Pin the exact header string so a typo can't silently break API calls.
        assertEquals("interleaved-thinking-2025-05-14", AnthropicSdkClient.INTERLEAVED_THINKING_BETA);
        assertEquals("effort-2025-11-24", AnthropicSdkClient.EFFORT_BETA);
    }

    @Test
    void multipleMessagesSerializeCorrectly() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .messages(List.of(
                        new CreateMessageRequest.RequestMessage("user", "Hello"),
                        new CreateMessageRequest.RequestMessage("assistant", "Hi there!"),
                        new CreateMessageRequest.RequestMessage("user", "How are you?")))
                .stream(true)
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertEquals(3, node.get("messages").size());
        assertEquals("user", node.get("messages").get(0).get("role").asText());
        assertEquals("assistant", node.get("messages").get(1).get("role").asText());
        assertEquals("user", node.get("messages").get(2).get("role").asText());
    }

    // ── adaptive thinking (newer models, e.g. Sonnet 5+) ────────────────────

    @Test
    void supportsAdaptiveThinking_twoPartVersionAtThreshold() {
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("claude-sonnet-4-6"));
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("claude-opus-4-6"));
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("claude-opus-4-8"));
    }

    @Test
    void supportsAdaptiveThinking_twoPartVersionBelowThreshold() {
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking("claude-sonnet-4-5"));
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking("claude-haiku-4-5"));
    }

    @Test
    void supportsAdaptiveThinking_onePartVersionIsNewMajorGeneration() {
        // "claude-sonnet-5" — no "4-x" segment, a bare trailing major version
        // one generation past the "4-x" naming scheme entirely.
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("claude-sonnet-5"));
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("claude-opus-5"));
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("anthropic.claude-sonnet-5"));
        assertTrue(CreateMessageRequest.supportsStructuredOutputs("claude-opus-5"));
    }

    @Test
    void supportsAdaptiveThinking_noTrailingVersionOrNull() {
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking("claude-sonnet"));
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking(null));
    }

    @Test
    void supportsAdaptiveThinking_unknownFirstPartyGatewayModel() {

        // adaptive-capable. A custom ANTHROPIC_BASE_URL does not change the
        // provider classification, so glm-5.2 must use adaptive + output_config.
        assertTrue(CreateMessageRequest.supportsAdaptiveThinking("glm-5.2"));
    }

    @Test
    void supportsAdaptiveThinking_datedModelIdsDontMistakeDateForVersion() {
        // Regression: a trailing 8-digit release date (as in every other test
        // fixture in this file, e.g. "claude-sonnet-4-20250514") must not be
        // parsed as a minor-version number — 20250514 >= 6 would otherwise
        // false-positive every dated Sonnet 4 model id in this test suite.
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking("claude-sonnet-4-20250514"));
        assertFalse(CreateMessageRequest.supportsAdaptiveThinking("claude-haiku-4-5-20251001"));
    }

    @Test
    void thinkingConfigAdaptiveSerializesCorrectly() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096)
                .messages(List.of())
                .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertEquals("adaptive", node.get("thinking").get("type").asText());
        assertFalse(node.get("thinking").has("budget_tokens"),
            "adaptive thinking has no token budget");
    }

    @Test
    void outputConfigEffortSerializesNestedInsteadOfTopLevel() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096)
                .messages(List.of())
                .outputConfig(new CreateMessageRequest.OutputConfig("high"))
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        assertTrue(node.has("output_config"));
        assertEquals("high", node.get("output_config").get("effort").asText());
        assertFalse(node.has("effort"), "legacy top-level effort must stay absent");
    }

    // ── context management (clear_thinking_20251015) ────────────────────────

    @Test
    void supportsContextManagement_excludesClaude3Generation() {
        assertFalse(CreateMessageRequest.supportsContextManagement("claude-3-opus-20240229"));
        assertFalse(CreateMessageRequest.supportsContextManagement("claude-3-5-sonnet-20241022"));
        assertFalse(CreateMessageRequest.supportsContextManagement("claude-3-7-sonnet-20250219"));
    }

    @Test
    void supportsContextManagement_includesClaude4PlusAndNull() {
        assertTrue(CreateMessageRequest.supportsContextManagement("claude-sonnet-4-20250514"));
        assertTrue(CreateMessageRequest.supportsContextManagement("claude-opus-4-6"));
        assertTrue(CreateMessageRequest.supportsContextManagement("claude-sonnet-5"));
        assertFalse(CreateMessageRequest.supportsContextManagement(null));
    }

    @Test
    void contextManagementSerializesClearThinkingEdit() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096)
                .messages(List.of())
                .contextManagement(new CreateMessageRequest.ContextManagementConfig(
                    List.of(CreateMessageRequest.ContextEditStrategy.clearThinkingKeepAll())))
                .build();

        String json = JsonUtils.toJson(request);
        JsonNode node = JsonUtils.parseTree(json);

        JsonNode edits = node.get("context_management").get("edits");
        assertEquals(1, edits.size());
        assertEquals("clear_thinking_20251015", edits.get(0).get("type").asText());
        assertEquals("all", edits.get(0).get("keep").asText());
    }

    @Test
    void contextManagementSerializesLastThinkingTurnObject() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096)
                .messages(List.of())
                .contextManagement(new CreateMessageRequest.ContextManagementConfig(
                    List.of(CreateMessageRequest.ContextEditStrategy.clearThinkingKeepLastTurn())))
                .build();

        JsonNode keep = JsonUtils.parseTree(JsonUtils.toJson(request))
            .get("context_management").get("edits").get(0).get("keep");

        assertTrue(keep.isObject());
        assertEquals("thinking_turns", keep.get("type").asText());
        assertEquals(1, keep.get("value").asInt());
    }

    @Test
    void fastModeSerializesSpeedOnlyWhenEnabled() {
        CreateMessageRequest fast = CreateMessageRequest.builder()
            .model("claude-opus-4-8")
            .messages(List.of())
            .speed("fast")
            .build();
        CreateMessageRequest ordinary = CreateMessageRequest.builder()
            .model("claude-opus-4-8")
            .messages(List.of())
            .build();

        assertEquals("fast", JsonUtils.parseTree(JsonUtils.toJson(fast)).path("speed").asText());
        assertFalse(JsonUtils.parseTree(JsonUtils.toJson(ordinary)).has("speed"));
    }

    @Test
    void contextManagementAbsentWhenNotSet() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-5")
                .maxTokens(4096)
                .messages(List.of())
                .build();

        JsonNode node = JsonUtils.parseTree(JsonUtils.toJson(request));
        assertFalse(node.has("context_management"));
    }
}
