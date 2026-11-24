package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.LlmRequestTraceCollector;
import com.claudecode.core.metrics.LlmRequestTraceRecord;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FourProtocolTrajectoryTraceTest {

    @Test
    void extractsDisjointReasoningTokensAcrossGeminiChatResponsesAndAnthropic() throws Exception {
        // 1. Google Gemini: output = candidates + thoughts, reasoningTokens = thoughts
        JsonNode geminiMeta = JsonUtils.getMapper().readTree("""
            {
              "promptTokenCount": 1200,
              "cachedContentTokenCount": 900,
              "candidatesTokenCount": 180,
              "thoughtsTokenCount": 420,
              "totalTokenCount": 1800
            }
            """);
        Usage geminiUsage = GeminiClient.parseGeminiUsage(geminiMeta);
        assertEquals(300L, geminiUsage.inputTokens());
        assertEquals(900L, geminiUsage.cacheReadInputTokens());
        assertEquals(600L, geminiUsage.outputTokens());
        assertEquals(420L, geminiUsage.reasoningTokens());

        // 2. OpenAI Chat Completions: completion_tokens_details.reasoning_tokens
        JsonNode chatUsageNode = JsonUtils.getMapper().readTree("""
            {
              "prompt_tokens": 1000,
              "completion_tokens": 500,
              "prompt_tokens_details": { "cached_tokens": 600 },
              "completion_tokens_details": { "reasoning_tokens": 350 },
              "total_tokens": 1500
            }
            """);
        Usage chatUsage = ReasoningNormalizer.parseChatUsage(chatUsageNode);
        assertEquals(400L, chatUsage.inputTokens());
        assertEquals(600L, chatUsage.cacheReadInputTokens());
        assertEquals(500L, chatUsage.outputTokens());
        assertEquals(350L, chatUsage.reasoningTokens());

        // 3. OpenAI Responses: output_tokens_details.reasoning_tokens
        ApiMessage responsesMsg = OpenAiResponsesClient.parseResponsesBodyStatic("""
            {
              "id": "resp_1",
              "status": "completed",
              "model": "gpt-5.4",
              "output": [
                {
                  "type": "message",
                  "content": [{ "type": "output_text", "text": "Done" }]
                }
              ],
              "usage": {
                "input_tokens": 800,
                "input_tokens_details": { "cached_tokens": 500 },
                "output_tokens": 260,
                "output_tokens_details": { "reasoning_tokens": 190 },
                "total_tokens": 1060
              }
            }
            """, "gpt-5.4", Set.of());
        assertEquals(300L, responsesMsg.usage().inputTokens());
        assertEquals(500L, responsesMsg.usage().cacheReadInputTokens());
        assertEquals(260L, responsesMsg.usage().outputTokens());
        assertEquals(190L, responsesMsg.usage().reasoningTokens());

        // 4. Anthropic standard usage (no separate reasoning_tokens) leaves reasoningTokens null
        Usage anthropicUsage = JsonUtils.getMapper().readValue("""
            {
              "input_tokens": 250,
              "cache_read_input_tokens": 1000,
              "cache_creation_input_tokens": 100,
              "output_tokens": 320
            }
            """, Usage.class);
        assertNull(anthropicUsage.reasoningTokens());
    }

    @Test
    void recordsAdaptiveWrongEndpointProtocolFallbackAndSanitizedHost() throws Exception {
        CustomModelConfig chatCfg = new CustomModelConfig(
            "gpt-5-codex",
            ModelApiProtocol.OPENAI_CHAT,
            "https://gateway.example.com:8443/v1?secret=should_not_leak",
            "sk-secret",
            Map.of(),
            200_000L,
            true);

        CustomModelRoutingClient router = new CustomModelRoutingClient(
            new StubClient("claude-sonnet-4-6", null),
            name -> Strings.equalsName(name, "gpt-5-codex") ? Optional.of(chatCfg) : Optional.empty(),
            cfg -> {
                if (cfg.protocol() == ModelApiProtocol.OPENAI_CHAT) {
                    return new StubClient(cfg.modelName(),
                        new ApiException("This model is only supported in v1/responses and not in v1/chat/completions", 404));
                }
                return new StubClient(cfg.modelName(), null) {
                    @Override
                    public ApiMessage createMessage(CreateMessageRequest request) {
                        LlmRequestTraceCollector active = LlmRequestTraceCollector.current();
                        if (active != null) {
                            var opts = JsonUtils.getMapper().createObjectNode();
                            opts.put("protocol", "responses");
                            opts.put("endpoint", "/v1/responses");
                            opts.put("store", false);
                            active.recordWireDispatch("responses", cfg.baseUrl(), cfg.modelName(), opts);
                        }
                        return super.createMessage(request);
                    }
                };
            });

        LlmRequestTraceCollector collector = new LlmRequestTraceCollector(1000L);
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("gpt-5-codex")
            .maxTokens(2048)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .build();

        ApiMessage ans = LlmRequestTraceCollector.observe(collector, () -> router.createMessage(req));
        assertNotNull(ans);

        LlmRequestTraceRecord trace = collector.toRecord(
            "sess-1", 1L, 1L, "assistant", 1450L, ans.model(), "abc123", "completed", null, null);
        assertEquals("chat", trace.configuredProtocol());
        assertEquals("responses", trace.effectiveProtocol());
        assertEquals("responses · gateway.example.com:8443", trace.providerLabel());
        assertFalse(org.apache.commons.lang3.Strings.CS.contains(trace.providerLabel(), "secret"));
        JsonNode opts = JsonUtils.getMapper().readTree(trace.optionsJson());
        assertEquals("chat", opts.path("configured_protocol").asText());
        assertEquals("wrong_endpoint_protocol", opts.path("attempts").get(0).path("kind").asText());
    }

    private static final class Strings {
        static boolean equalsName(String a, String b) {
            return org.apache.commons.lang3.Strings.CS.equals(a, b);
        }
    }

    private static class StubClient implements LlmClient {
        private final String model;
        private final ApiException failure;

        StubClient(String model, ApiException failure) {
            this.model = model;
            this.failure = failure;
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            if (failure != null) throw failure;
            return new ApiMessage("msg_1", "message", "assistant",
                List.of(new TextBlock("ok")), model, "end_turn", null,
                new Usage(100, 50, 0, 20));
        }

        @Override
        public String getModel() {
            return model;
        }
    }
}
