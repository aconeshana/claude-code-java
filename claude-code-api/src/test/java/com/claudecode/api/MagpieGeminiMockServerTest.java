/*
 * Gemini protocol unit tests ported from yetone/magpie (https://github.com/yetone/magpie),
 * Copyright (c) 2026 yetone, licensed under the MIT License:
 *   - internal/provider/gemini_api_test.go
 *   - internal/gateway/gemini_bare_host_test.go
 *   - internal/gateway/gemini_level_test.go
 *   - internal/gateway/gemini_bool_schema_test.go
 *   - internal/gateway/gemini_first_turn_test.go
 *   - internal/gateway/gemini_tools_test.go
 *   - internal/gateway/gemini_function_parts_test.go
 *   - internal/gateway/gemini_image_test.go
 *   - internal/gateway/opencode_gemini_test.go
 */
package com.claudecode.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Acceptance suite exercising {@link GeminiClient} and {@link ProtocolWireCodec} against
 * {@link MockGeminiServer}'s strict Google AI Studio / Vertex AI wire validator.
 */
class MagpieGeminiMockServerTest {

    /** Ported from {@code gemini_api_test.go:TestGeminiBase}, {@code TestGeminiAsked} & {@code gemini_bare_host_test.go}. */
    @Test
    void bareHostRelayPrefixAndPastedActionUrlsNormalizeCleanly() throws Exception {
        assertThat(GeminiClient.normalizeBasePrefix("generativelanguage.googleapis.com"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(GeminiClient.normalizeBasePrefix("https://generativelanguage.googleapis.com/"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(GeminiClient.normalizeBasePrefix(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(GeminiClient.normalizeBasePrefix("https://relay.example/proxy/v1beta/models"))
            .isEqualTo("https://relay.example/proxy/v1beta");
        assertThat(GeminiClient.normalizeBasePrefix("https://relay.example/gemini"))
            .isEqualTo("https://relay.example/gemini");
        assertThat(GeminiClient.normalizeBasePrefix("https://relay.example/proxy/v1"))
            .isEqualTo("https://relay.example/proxy/v1");

        // Verify live HTTP routing against MockGeminiServer under bare host vs custom /gemini prefix
        for (String prefix : List.of("", "/", "/v1beta", "/gemini", "/proxy/v1beta")) {
            String serverPrefix = (Strings.CS.equals("/gemini", prefix)
                || Strings.CS.equals("/proxy/v1beta", prefix))
                ? prefix : "/v1beta";
            try (MockGeminiServer mock = MockGeminiServer.builder()
                    .pathPrefix(serverPrefix)
                    .expectedApiKey("AIza-test")
                    .start()) {
                mock.enqueueText("flash ok");
                String configuredUrl = mock.hostUrl() + prefix;
                GeminiClient client = new GeminiClient(
                    new ApiConfig.OpenAiConfig("AIza-test", "gemini-3.8-flash", configuredUrl),
                    HttpClientFactory.shared());

                List<StreamEvent> events = drain(client.createMessageStream(CreateMessageRequest.builder()
                    .model("gemini-3.8-flash")
                    .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
                    .stream(true)
                    .build()));

                assertThat(collectText(events)).isEqualTo("flash ok");
                assertThat(mock.lastRequest().requestUri())
                    .isEqualTo(serverPrefix + "/models/gemini-3.8-flash:streamGenerateContent?alt=sse");
                assertThat(mock.lastRequest().googApiKey()).isEqualTo("AIza-test");
                assertThat(mock.lastRequest().authorization()).isNull();
            }
        }
    }

    /** Ported from {@code gemini_level_test.go:TestGeminiLaterGenerationsToldALevel} & {@code TestGeminiGeneration} (#2083). */
    @Test
    void geminiGenerationAndThinkingLevelVsBudgetMatchGoogleRules() throws Exception {
        assertThat(GeminiClient.geminiGeneration("gemini-4-argon")).isEqualTo(4);
        assertThat(GeminiClient.geminiGeneration("models/gemini-4-argon")).isEqualTo(4);
        assertThat(GeminiClient.geminiGeneration("gemini-3.8-flash")).isEqualTo(3);
        assertThat(GeminiClient.geminiGeneration("gemini-2.5-pro")).isEqualTo(2);
        assertThat(GeminiClient.geminiGeneration("gemini-10-pro")).isEqualTo(10);
        assertThat(GeminiClient.geminiGeneration("gemini-flash-latest")).isZero();
        assertThat(GeminiClient.geminiGeneration("argon-gemini-3")).isZero();
        assertThat(GeminiClient.geminiGeneration("gemma-3-27b-it")).isZero();

        record Case(String model, String effort, String expectedLevel, Integer expectedBudget) {}
        List<Case> cases = List.of(
            new Case("gemini-4-argon", "low", "low", null),
            new Case("gemini-4-argon", "medium", "medium", null),
            new Case("gemini-4-argon", "high", "high", null),
            new Case("gemini-3.8-flash", "low", "low", null),
            new Case("gemini-3.8-flash", "medium", "medium", null),
            new Case("gemini-3.8-flash", "high", "high", null),
            new Case("gemini-3-pro-preview", "medium", "high", null),
            new Case("gemini-2.5-flash", "high", null, 24_576)
        );

        try (MockGeminiServer mock = MockGeminiServer.start()) {
            GeminiClient client = new GeminiClient(
                new ApiConfig.OpenAiConfig("AIza-level", "gemini-4-argon", mock.baseUrl()),
                HttpClientFactory.shared());

            for (Case c : cases) {
                mock.enqueueText("pong");
                ApiMessage msg = client.createMessage(CreateMessageRequest.builder()
                    .model(c.model())
                    .effort(c.effort())
                    .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
                    .build());
                assertThat(textOf(msg)).isEqualTo("pong");
                JsonNode tc = mock.lastRequest().thinkingConfig();
                assertThat(tc.path("includeThoughts").asBoolean()).isTrue();
                if (c.expectedLevel() != null) {
                    assertThat(tc.path("thinkingLevel").asText())
                        .as("model=%s effort=%s", c.model(), c.effort())
                        .isEqualTo(c.expectedLevel());
                    assertThat(tc.has("thinkingBudget")).isFalse();
                } else {
                    assertThat(tc.path("thinkingBudget").asInt()).isPositive();
                    assertThat(tc.has("thinkingLevel")).isFalse();
                }
            }
        }
    }

    /** Ported from {@code gemini_bool_schema_test.go} & {@code gemini_first_turn_test.go}. */
    @Test
    void booleanSchemasXKeywordsAndModelFirstHistoryPassStrictGoogleValidator() throws Exception {
        try (MockGeminiServer mock = MockGeminiServer.start()) {
            mock.enqueueText("OK");
            GeminiClient client = new GeminiClient(
                new ApiConfig.OpenAiConfig("AIza-schema", "gemini-3.8-flash", mock.baseUrl()),
                HttpClientFactory.shared());

            JsonNode complexSchema = JsonUtils.parseTree("""
                {
                  "type": "object",
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "x-root": 1,
                  "$defs": {
                    "Any": true,
                    "None": false,
                    "Cell": {"type": "array", "items": true, "x-def": "d"}
                  },
                  "properties": {
                    "spreadsheet": {"type": "string"},
                    "x-region": {"type": "string", "x-mcp-header": "Region"},
                    "rows": {"type": "array", "items": {"type": "array", "items": true}},
                    "gone": false,
                    "refCell": {"$ref": "#/$defs/Cell"}
                  },
                  "required": ["spreadsheet", "gone", "x-region"],
                  "additionalProperties": false
                }
                """);

            // History intentionally opens with assistant ("model") turn like post-compaction context
            ApiMessage reply = client.createMessage(CreateMessageRequest.builder()
                .model("gemini-3.8-flash")
                .systemPrompt("be brief")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("assistant", "I looked at the repo earlier."),
                    new CreateMessageRequest.RequestMessage("user", "go on")))
                .tools(List.of(new CreateMessageRequest.ToolDefinition(
                    "mcp__sheets__write_rows", "write rows", complexSchema)))
                .build());

            assertThat(textOf(reply)).isEqualTo("OK");
            JsonNode contents = mock.lastRequest().contents();
            assertThat(contents.get(0).path("role").asText()).isEqualTo("user");
            assertThat(contents.get(0).path("parts").get(0).path("text").asText())
                .isEqualTo("[Continuing from previous AI thoughts...]");
            assertThat(contents.get(1).path("role").asText()).isEqualTo("model");

            JsonNode sentSchema = mock.lastRequest().tools().get(0)
                .path("functionDeclarations").get(0).path("parametersJsonSchema");
            assertThat(sentSchema.path("properties").path("x-region").path("type").asText())
                .isEqualTo("string");
            assertThat(sentSchema.path("properties").has("gone")).isFalse();
            assertThat(sentSchema.path("properties").path("rows").path("items").path("items").isObject())
                .isTrue();
        }
    }

    /** Ported from {@code gemini_tools_test.go:TestParseGeminiAllowedFunctionNames}. */
    @Test
    void parseGeminiAllowedFunctionNamesFiltersToolsAcrossAllTargetProtocols() throws Exception {
        record Case(String mode, List<String> allowed, List<String> expectedTools, String expectedChoiceType, String expectedToolName) {}
        List<Case> cases = List.of(
            new Case("ANY", List.of("safeA", "safeB"), List.of("safeA", "safeB"), "any", null),
            new Case("ANY", List.of("safeB"), List.of("safeB"), "tool", "safeB"),
            new Case("ANY", List.of(), List.of("safeA", "forbidden", "safeB"), "any", null),
            new Case("VALIDATED", List.of("safeA", "safeB"), List.of("safeA", "safeB"), "auto", null),
            new Case("VALIDATED", List.of("safeB"), List.of("safeB"), "auto", null),
            new Case("AUTO", List.of("safeA"), List.of("safeA", "forbidden", "safeB"), "auto", null),
            new Case("NONE", List.of("safeA"), List.of("safeA", "forbidden", "safeB"), "none", null)
        );

        for (Case c : cases) {
            ObjectNode req = (ObjectNode) JsonUtils.parseTree("""
                {
                  "contents": [{"role": "user", "parts": [{"text": "run"}]}],
                  "tools": [
                    {"functionDeclarations": [
                      {"name": "safeA", "parameters": {"type": "object"}},
                      {"name": "forbidden", "parameters": {"type": "object"}}
                    ]},
                    {"functionDeclarations": [{"name": "safeB"}]}
                  ]
                }
                """);
            ObjectNode fcc = req.putObject("toolConfig").putObject("functionCallingConfig");
            fcc.put("mode", c.mode());
            if (!c.allowed().isEmpty()) {
                var arr = fcc.putArray("allowedFunctionNames");
                c.allowed().forEach(arr::add);
            }

            CreateMessageRequest decoded = ProtocolWireCodec.parseRequest(
                ModelApiProtocol.GEMINI, req.toString());
            assertThat(decoded.tools()).extracting(CreateMessageRequest.ToolDefinition::name)
                .as("mode=%s allowed=%s", c.mode(), c.allowed())
                .containsExactlyElementsOf(c.expectedTools());
            assertThat(decoded.toolChoice().type()).isEqualTo(c.expectedChoiceType());
            if (c.expectedToolName() != null) {
                assertThat(decoded.toolChoice().name()).isEqualTo(c.expectedToolName());
            }

            for (ModelApiProtocol target : List.of(
                    ModelApiProtocol.OPENAI_CHAT,
                    ModelApiProtocol.OPENAI_RESPONSES,
                    ModelApiProtocol.ANTHROPIC)) {
                JsonNode encoded = JsonUtils.parseTree(
                    ProtocolWireCodec.encodeRequest(target, decoded, "upstream", false));
                List<String> names = new ArrayList<>();
                for (JsonNode t : encoded.path("tools")) {
                    JsonNode fn = t.has("function") ? t.get("function") : t;
                    names.add(fn.path("name").asText());
                }
                assertThat(names).containsExactlyElementsOf(c.expectedTools());
            }
        }
    }

    /** Ported from {@code gemini_function_parts_test.go:TestGeminiFunctionResponseImage} & {@code TestGeminiFunctionResponseFile} (#1038). */
    @Test
    void multimodalFunctionResponseInCamelAndSnakeCasePreservesImagesAndTrailingFiles() throws Exception {
        String jpegData = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8U";
        for (String role : List.of("model", "user")) {
            for (boolean snake : List.of(false, true)) {
                String frPart = snake
                    ? """
                      {"function_response":{"id":"call_462432","name":"view_file",
                        "response":{"output":"complete file content"},
                        "parts":[
                          {"inline_data":{"mime_type":"image/jpeg","data":"%s"}},
                          {"inline_data":{"mime_type":"application/pdf","data":"UERG"}}
                        ]}}""".formatted(jpegData)
                    : """
                      {"functionResponse":{"id":"call_462432","name":"view_file",
                        "response":{"output":"complete file content"},
                        "parts":[
                          {"inlineData":{"mimeType":"image/jpeg","data":"%s"}},
                          {"inlineData":{"mimeType":"application/pdf","data":"UERG"}}
                        ]}}""".formatted(jpegData);
                String payload = """
                    {
                      "contents": [
                        {"role": "user", "parts": [{"text": "describe image"}]},
                        {"role": "model", "parts": [{"functionCall": {"id": "call_462432", "name": "view_file", "args": {"path": "a.jpg"}}}]},
                        {"role": "%s", "parts": [%s]}
                      ],
                      "generation_config": {"top_k": 40}
                    }
                    """.formatted(role, frPart);

                CreateMessageRequest parsed = ProtocolWireCodec.parseRequest(
                    ModelApiProtocol.GEMINI, payload);
                assertThat(parsed.topK()).isEqualTo(40);
                CreateMessageRequest.RequestMessage lastMsg = parsed.messages().getLast();
                assertThat(lastMsg.role()).isEqualTo("user");
                @SuppressWarnings("unchecked")
                List<ContentBlock> blocks = (List<ContentBlock>) lastMsg.content();
                assertThat(blocks).hasSize(2);
                assertThat(blocks.getFirst()).isInstanceOf(ToolResultBlock.class);
                ToolResultBlock tr = (ToolResultBlock) blocks.getFirst();
                assertThat(tr.content()).anyMatch(ImageBlock.class::isInstance);
                assertThat(blocks.get(1)).isInstanceOf(DocumentBlock.class);

                for (ModelApiProtocol target : List.of(
                        ModelApiProtocol.ANTHROPIC,
                        ModelApiProtocol.OPENAI_CHAT,
                        ModelApiProtocol.OPENAI_RESPONSES,
                        ModelApiProtocol.GEMINI)) {
                    String wire = ProtocolWireCodec.encodeRequest(target, parsed, "m", false).toString();
                    assertThat(wire).as("role=%s snake=%s target=%s", role, snake, target)
                        .contains(jpegData);
                }
            }
        }
    }

    /** Ported from {@code opencode_gemini_test.go:35} & {@code gemini_image_test.go:TestGeminiImageModelReturnsItsImage} (#620). */
    @Test
    void trailingSignatureChunkAndDraftThoughtImageFilterWorkInUnaryAndStreaming() throws Exception {
        try (MockGeminiServer mock = MockGeminiServer.start()) {
            GeminiClient client = new GeminiClient(
                new ApiConfig.OpenAiConfig("AIza-sig", "gemini-3-flash", mock.baseUrl()),
                HttpClientFactory.shared());

            // 1. OpenCode Zen trailing empty-text part carrying thoughtSignature
            mock.enqueue(MockGeminiServer.Reply.openCodeTrailingSignature(
                "Hi.", "AY89a18MP3lS4K3OArBbSNxbDAHYyTJ2", 5, 2, 67));
            List<StreamEvent> streamed = drain(client.createMessageStream(CreateMessageRequest.builder()
                .model("gemini-3-flash")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "Say hi")))
                .stream(true)
                .build()));
            assertThat(collectText(streamed)).isEqualTo("Hi.");
            assertThat(streamed).anyMatch(ev -> ev instanceof StreamEvent.ContentBlockDelta(
                _, Delta.SignatureDelta(String s), _)
                && Strings.CS.equals("AY89a18MP3lS4K3OArBbSNxbDAHYyTJ2", s));

            // 2. Gemini image generation: draft thought image ("ZHJhZnQ=") filtered out, final image ("aGVsbG8=") kept
            mock.enqueue(MockGeminiServer.Reply.imageWithDraftThought(
                "ZHJhZnQ=", "Here it is.", "aGVsbG8=", "c2ln"));
            ApiMessage imgMsg = client.createMessage(CreateMessageRequest.builder()
                .model("gemini-3.1-pro-preview")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "draw a cat")))
                .build());
            assertThat(textOf(imgMsg)).isEqualTo("Here it is.");
            List<ImageBlock> images = imgMsg.content().stream()
                .filter(ImageBlock.class::isInstance)
                .map(ImageBlock.class::cast)
                .toList();
            assertThat(images).hasSize(1);
            assertThat(images.getFirst().source().path("data").asText()).isEqualTo("aGVsbG8=");
            assertThat(imgMsg.content().toString()).doesNotContain("ZHJhZnQ=");
        }
    }

    @Test
    void adaptiveThinkingNullBudgetAndParsedContentBlocksSerializeCleanlyForGeminiAndAnthropic()
            throws Exception {
        try (MockGeminiServer mock = MockGeminiServer.start()) {
            GeminiClient client = mock.newClient("AIza-adaptive", "glm-5.3-chat");
            mock.enqueueText("adaptive-ok");

            // Adaptive thinking has budgetTokens == null; non-Gemini3 model name takes the budget branch
            ApiMessage msg = client.createMessage(CreateMessageRequest.builder()
                .model("glm-5.3-chat")
                .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
                .build());
            assertThat(textOf(msg)).isEqualTo("adaptive-ok");
            assertThat(mock.lastRequest().thinkingConfig().path("thinkingBudget").asInt()).isPositive();
        }

        // Verify ProtocolWireCodec-parsed ContentBlock lists retain "type" when encoded for Anthropic
        CreateMessageRequest fromChat = ProtocolWireCodec.parseRequest(
            ModelApiProtocol.OPENAI_CHAT,
            "{\"model\":\"glm-5.3-anth\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}]}");
        assertThat(ProtocolWireCodec.hasContentBlockMessages(fromChat.messages())).isTrue();
        var anthropicMsgs = ProtocolWireCodec.encodeAnthropicMessagesNode(fromChat.messages());
        assertThat(anthropicMsgs.get(0).path("content").get(0).path("type").asText())
            .isEqualTo("text");
    }

    @Test
    void snakeCaseGeminiRequestNormalizesProtocolKeysWhilePreservingCallerOwnedFieldsAndExplicitCamelCase() {
        String rawGemini = """
            {
              "model": "models/gemini-2.5-pro",
              "system_instruction": { "parts": [{ "text": "sys-prompt" }] },
              "generation_config": {
                "max_output_tokens": 256,
                "maxOutputTokens": 1024
              },
              "tools": [{
                "function_declarations": [{
                  "name": "read_file",
                  "parameters_json_schema": {
                    "type": "object",
                    "properties": { "file_path": { "type": "string" } }
                  }
                }]
              }],
              "contents": [
                { "role": "user", "parts": [{ "text": "inspect" }] },
                {
                  "role": "model",
                  "parts": [{
                    "function_call": {
                      "id": "call_1",
                      "name": "read_file",
                      "args": { "file_path": "/tmp/a.txt", "line_offset": 10 }
                    }
                  }]
                }
              ]
            }
            """;
        CreateMessageRequest parsed = ProtocolWireCodec.parseRequest(ModelApiProtocol.GEMINI, rawGemini);
        assertThat(parsed.model()).isEqualTo("gemini-2.5-pro");
        assertThat(parsed.systemPrompt()).isEqualTo("sys-prompt");
        assertThat(parsed.maxTokens()).isEqualTo(1024);
        assertThat(parsed.tools()).hasSize(1);
        assertThat(parsed.tools().getFirst().inputSchema().path("properties").has("file_path")).isTrue();
        List<?> modelBlocks = (List<?>) parsed.messages().get(1).content();
        ToolUseBlock toolUse = (ToolUseBlock) modelBlocks.getFirst();
        assertThat(toolUse.input().path("file_path").asText()).isEqualTo("/tmp/a.txt");
        assertThat(toolUse.input().path("line_offset").asInt()).isEqualTo(10);
    }

    private static String textOf(ApiMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.content()) {
            if (b instanceof TextBlock(String t)) sb.append(t);
        }
        return sb.toString();
    }

    private static List<StreamEvent> drain(Iterator<StreamEvent> it) {
        List<StreamEvent> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static String collectText(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent ev : events) {
            if (ev instanceof StreamEvent.ContentBlockDelta(_, Delta.TextDelta(String t), _)) {
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
