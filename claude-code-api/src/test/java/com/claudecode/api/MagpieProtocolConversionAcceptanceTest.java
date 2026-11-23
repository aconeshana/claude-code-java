/*
 * Acceptance scenarios adapted from yetone/magpie (https://github.com/yetone/magpie),
 * Copyright (c) 2026 yetone, licensed under the MIT License.
 */
package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Full 4-protocol ({@code Anthropic Messages ↔ OpenAI Chat ↔ OpenAI Responses ↔ Google Gemini})
 * and high-order compatibility acceptance suite adapted from {@code yetone/magpie}
 * ({@code internal/gateway/*_test.go} and {@code internal/claudeseal}).
 */
class MagpieProtocolConversionAcceptanceTest {

    private static final String CALENDAR_SCHEMA = """
        {"type":"object","properties":{"name":{"title":"Name","type":"string"},\
        "date":{"title":"Date","type":"string"},\
        "participants":{"items":{"type":"string"},"title":"Participants","type":"array"}},\
        "required":["name","date","participants"],"title":"CalendarEvent","additionalProperties":false}""";

    /** Adapted from magpie {@code gateway_test.go:TestAnthropicClientChatUpstream}. */
    @Test
    void anthropicToChatStreamingRoundTripPreservesThinkingTextToolCallAndUsage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"c1","model":"m1","choices":[{"delta":{"role":"assistant","reasoning_content":"hmm"}}]}

                    data: {"id":"c1","choices":[{"delta":{"content":"Hi "}}]}

                    data: {"id":"c1","choices":[{"delta":{"content":"there"}}]}

                    data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":""}}]}}]}

                    data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":"}}]}}]}

                    data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.go\\"}"}}]}}]}

                    data: {"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: {"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                    data: [DONE]

                    """).build());
            server.start();

            CreateMessageRequest request = CreateMessageRequest.builder()
                .model("m1")
                .maxTokens(100)
                .stream(true)
                .systemPrompt("be brief")
                .effort("medium")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "read a.go"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("t0", "read", JsonUtils.parseTree("{\"path\":\"x\"}")))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("t0", List.of(new TextBlock("package x")), false)))))
                .tools(List.of(new CreateMessageRequest.ToolDefinition(
                    "read", "read a file",
                    JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"))))
                .build();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(request).forEachRemaining(events::add);

            var recorded = server.takeRequest();
            assertEquals("/v1/chat/completions", recorded.getUrl().encodedPath());
            JsonNode upstream = JsonUtils.parseTree(recorded.getBody().utf8());
            assertEquals(4, upstream.path("messages").size());
            assertEquals("system", upstream.path("messages").get(0).path("role").asText());
            assertEquals("tool", upstream.path("messages").get(3).path("role").asText());
            assertEquals("medium", upstream.path("reasoning_effort").asText());
            assertTrue(upstream.path("stream").asBoolean());

            List<String> anthropicSseTypes = new ArrayList<>();
            for (StreamEvent event : events) {
                StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(event);
                if (encoded != null) anthropicSseTypes.add(encoded.type());
            }
            assertEquals("message_start", anthropicSseTypes.getFirst());
            assertEquals("message_stop", anthropicSseTypes.getLast());

            StreamEvent.MessageDelta finalDelta = events.stream()
                .filter(StreamEvent.MessageDelta.class::isInstance)
                .map(StreamEvent.MessageDelta.class::cast)
                .findFirst().orElseThrow();
            assertEquals("tool_use", finalDelta.delta().stopReason());
            assertEquals(10, finalDelta.usage().inputTokens());
            assertEquals(5, finalDelta.usage().outputTokens());
        }
    }

    /** Adapted from magpie {@code format_test.go:TestStructuredOutputCrossesEveryAPI}. */
    @Test
    void structuredOutputSchemaMapsAcrossChatResponsesAndGemini() throws Exception {
        JsonNode schemaNode = JsonUtils.parseTree(CALENDAR_SCHEMA);
        var outputFormat = JsonUtils.getMapper().createObjectNode();
        outputFormat.put("type", "json_schema");
        outputFormat.put("name", "CalendarEvent");
        outputFormat.put("strict", true);
        outputFormat.set("schema", schemaNode);

        try (MockWebServer chatServer = okChatServer()) {
            chatClient(chatServer).createMessage(CreateMessageRequest.builder()
                .model("m1")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "extract")))
                .outputConfig(new CreateMessageRequest.OutputConfig(null, outputFormat))
                .stream(false)
                .build());
            JsonNode sent = JsonUtils.parseTree(chatServer.takeRequest().getBody().utf8());
            assertEquals("json_schema", sent.path("response_format").path("type").asText());
            assertEquals("CalendarEvent",
                sent.path("response_format").path("json_schema").path("name").asText());
            assertEquals("CalendarEvent",
                sent.path("response_format").path("json_schema").path("schema").path("title").asText());
        }

        try (MockWebServer respServer = okResponsesServer()) {
            responsesClient(respServer).createMessage(CreateMessageRequest.builder()
                .model("m1")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "extract")))
                .outputConfig(new CreateMessageRequest.OutputConfig(null, outputFormat))
                .stream(false)
                .build());
            JsonNode sent = JsonUtils.parseTree(respServer.takeRequest().getBody().utf8());
            assertEquals("json_schema", sent.path("text").path("format").path("type").asText());
            assertEquals("CalendarEvent", sent.path("text").path("format").path("name").asText());
            assertEquals("CalendarEvent",
                sent.path("text").path("format").path("schema").path("title").asText());
        }

        try (MockWebServer gemServer = okGeminiServer()) {
            geminiClient(gemServer).createMessage(CreateMessageRequest.builder()
                .model("gemini-2.5-flash")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "extract")))
                .outputConfig(new CreateMessageRequest.OutputConfig(null, outputFormat))
                .stream(false)
                .build());
            JsonNode sent = JsonUtils.parseTree(gemServer.takeRequest().getBody().utf8());
            assertEquals("application/json",
                sent.path("generationConfig").path("responseMimeType").asText());
            assertEquals("CalendarEvent",
                sent.path("generationConfig").path("responseJsonSchema").path("title").asText());
        }
    }

    /** Adapted from magpie {@code split_calls_test.go:TestResponsesSplitParallelCallsKeepTheirResults}. */
    @Test
    void responsesSplitParallelToolCallsAcrossAssistantMessagesKeepTheirResults() throws Exception {
        try (MockWebServer server = okResponsesServer()) {
            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-5.4")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "read a and b"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("toolu_a", "read", JsonUtils.parseTree("{\"path\":\"a\"}")))),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("toolu_b", "read", JsonUtils.parseTree("{\"path\":\"b\"}")))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("toolu_a", List.of(new TextBlock("A!")), false),
                        new ToolResultBlock("toolu_b", List.of(new TextBlock("B!")), false)))))
                .stream(false)
                .build());

            JsonNode input = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("input");
            List<String> trace = new ArrayList<>();
            for (JsonNode item : input) {
                String type = item.path("type").asText();
                if (item.hasNonNull("call_id")) {
                    String suffix = item.hasNonNull("output")
                        ? "=" + item.path("output").asText() : "";
                    trace.add(type + "(" + item.path("call_id").asText() + suffix + ")");
                } else {
                    trace.add(type);
                }
            }
            assertEquals(
                "message function_call(toolu_a) function_call(toolu_b) "
                    + "function_call_output(toolu_a=A!) function_call_output(toolu_b=B!)",
                String.join(" ", trace));
        }
    }

    /** Adapted from magpie {@code ir.go:effortOfBudget}. */
    @Test
    void anthropicThinkingBudgetMapsToChatReasoningEffortWhenEffortOmitted() throws Exception {
        try (MockWebServer server = okChatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("m1")
                .maxTokens(100)
                .thinking(CreateMessageRequest.ThinkingConfig.enabled(5000))
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
                .stream(false)
                .build());

            JsonNode sent = JsonUtils.parseTree(server.takeRequest().getBody().utf8());
            assertEquals("medium", sent.path("reasoning_effort").asText());
        }
    }

    /** Adapted from magpie {@code chat_tool_pairing_test.go:TestChatToolResultsLeadInCallOrder}. */
    @Test
    void chatToolResultsLeadInCallOrderAndPreserveSiblingUserText() throws Exception {
        try (MockWebServer server = okChatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("kimi-k3")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "run them"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        call("A"), call("B"), call("C"))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new TextBlock("note from an attachment"),
                        result("C", "3"),
                        result("A", "1"),
                        result("B", "2")))))
                .stream(false)
                .build());

            JsonNode msgs = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("user assistant(A,B,C) tool(A) tool(B) tool(C) user", chatTrace(msgs));
            assertTrue(Strings.CS.contains(msgs.get(5).path("content").toString(),
                "note from an attachment"));
        }
    }

    /** Adapted from magpie {@code chat_tool_pairing_test.go:TestChatUnansweredCallGetsSyntheticResult}. */
    @Test
    void chatUnansweredToolCallGetsSyntheticInterruptedResult() throws Exception {
        try (MockWebServer server = okChatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("kimi-k3")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("assistant", List.of(call("A"))),
                    new CreateMessageRequest.RequestMessage("user", "continue")))
                .stream(false)
                .build());

            JsonNode msgs = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("assistant(A) tool(A) user", chatTrace(msgs));
        }
    }

    /** Adapted from magpie {@code chat_tool_pairing_test.go:TestChatOrphanToolResultBecomesUserMessage}. */
    @Test
    void chatOrphanAndDuplicateToolResultsAreSanitizedForStrictUpstreams() throws Exception {
        try (MockWebServer server = okChatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("kimi-k3")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", List.of(result("orphan_id", "kept"))),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(call("A"))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        result("A", "first"), result("A", "duplicate")))))
                .stream(false)
                .build());

            JsonNode msgs = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("user assistant(A) tool(A)", chatTrace(msgs));
            assertEquals("first", msgs.get(2).path("content").asText());
        }
    }

    /** Adapted from magpie {@code split_calls_test.go:TestChatSplitParallelCallsKeepTheirResults}. */
    @Test
    void chatSplitParallelToolCallsAcrossConsecutiveAssistantMessagesJoinIntoOneExchange()
            throws Exception {
        try (MockWebServer server = okChatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("kimi-k3")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "read a and b"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(call("toolu_a"))),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(call("toolu_b"))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        result("toolu_a", "A!"), result("toolu_b", "B!")))))
                .stream(false)
                .build());

            JsonNode msgs = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("user assistant(toolu_a,toolu_b) tool(toolu_a) tool(toolu_b)", chatTrace(msgs));
        }
    }

    /** Adapted from magpie {@code chat_inbound_reasoning_test.go} & {@code chat.go:thoughtTags}. */
    @Test
    void chatInboundExtractsOpenRouterReasoningFieldAndLeadingThinkTagsWhileIgnoringCodeFences()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"c1","model":"openrouter/qwen3-coder",
                     "choices":[{"message":{"reasoning":"plan first","content":"done"},"finish_reason":"stop"}]}
                    """).build());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"c2","model":"minimax-m2",
                     "choices":[{"message":{"content":"<think>step by step</think>\\n\\n```xml\\n<think>literal example</think>\\n```"},"finish_reason":"stop"}]}
                    """).build());
            server.start();

            ApiMessage openRouterMsg = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("m1").stream(false).build());
            ThinkingBlock orThink = assertInstanceOf(ThinkingBlock.class, openRouterMsg.content().getFirst());
            assertEquals("plan first", orThink.thinking());

            ApiMessage miniMaxMsg = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("m1").stream(false).build());
            ThinkingBlock mmThink = assertInstanceOf(ThinkingBlock.class, miniMaxMsg.content().getFirst());
            assertEquals("step by step", mmThink.thinking());
            TextBlock mmText = assertInstanceOf(TextBlock.class, miniMaxMsg.content().get(1));
            assertTrue(Strings.CS.contains(mmText.text(), "<think>literal example</think>"),
                "fenced Markdown code block <think> example must remain untouched");
        }
    }

    /** Adapted from magpie {@code reasoning_state_claude_codex_test.go}. */
    @Test
    void chatHistorySkipsRedactedThinkingBlockInsteadOfThrowing() throws Exception {
        try (MockWebServer server = okChatServer()) {
            CreateMessageRequest request = CreateMessageRequest.builder()
                .model("m1")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "hello"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new RedactedThinkingBlock("encrypted_claude_payload"),
                        new TextBlock("visible reply"))),
                    new CreateMessageRequest.RequestMessage("user", "follow up")))
                .stream(false)
                .build();

            ApiMessage reply = assertDoesNotThrow(() -> chatClient(server).createMessage(request));
            assertNotNull(reply);
        }
    }

    /** Adapted from magpie {@code pdf_translation_test.go:TestAnthropicDocumentSurvivesTranslation}. */
    @Test
    void pdfDocumentBlockTranslatesToResponsesInputFileGeminiInlineDataAndChatAttachmentNote()
            throws Exception {
        var pdfSource = JsonUtils.getMapper().createObjectNode()
            .put("type", "base64")
            .put("media_type", "application/pdf")
            .put("data", "JVBERi0xLjQK");
        var pdfBlock = new DocumentBlock(pdfSource);

        try (MockWebServer respServer = okResponsesServer()) {
            responsesClient(respServer).createMessage(CreateMessageRequest.builder()
                .model("gpt-5")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", List.of(
                    new TextBlock("read it"), pdfBlock))))
                .stream(false)
                .build());
            String sent = respServer.takeRequest().getBody().utf8();
            assertTrue(Strings.CS.contains(sent, "\"input_file\""));
            assertTrue(Strings.CS.contains(sent, "JVBERi0xLjQK"));
        }

        try (MockWebServer gemServer = okGeminiServer()) {
            geminiClient(gemServer).createMessage(CreateMessageRequest.builder()
                .model("gemini-2.5-pro")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", List.of(
                    new TextBlock("read it"), pdfBlock))))
                .stream(false)
                .build());
            JsonNode sent = JsonUtils.parseTree(gemServer.takeRequest().getBody().utf8());
            JsonNode inline = sent.path("contents").get(0).path("parts").get(1).path("inlineData");
            assertEquals("application/pdf", inline.path("mimeType").asText());
            assertEquals("JVBERi0xLjQK", inline.path("data").asText());
        }

        try (MockWebServer chatServer = okChatServer()) {
            assertDoesNotThrow(() -> chatClient(chatServer).createMessage(
                CreateMessageRequest.builder()
                    .model("m1")
                    .messages(List.of(new CreateMessageRequest.RequestMessage("user", List.of(
                        new TextBlock("read it"), pdfBlock))))
                    .stream(false)
                    .build()));
        }
    }

    /** Adapted from magpie {@code chat.go:cUsage.usage()} (DeepSeek {@code prompt_cache_hit_tokens}). */
    @Test
    void chatUsageReadsDeepSeekPromptCacheHitTokens() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"ds_1","model":"deepseek-chat",
                     "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":100,"completion_tokens":20,
                              "prompt_cache_hit_tokens":80,"prompt_cache_miss_tokens":20}}
                    """).build());
            server.start();

            ApiMessage msg = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("deepseek-chat").stream(false).build());
            assertEquals(80, msg.usage().cacheReadInputTokens());
            assertEquals(20, msg.usage().inputTokens());
        }
    }

    // ── Google Gemini Protocol & Thought Signature Acceptance ─────────────────

    /**
     * Adapted from magpie {@code gemini_level_test.go}, {@code gemini_tools_test.go},
     * and {@code gemini_thought_tag_test.go}.
     */
    @Test
    void geminiClientGenerateContentAndSseStreamPreserveThoughtsSignedCallsAndUsage() throws Exception {
        String rawSig = "R2VtaW5pM1NpZ25hdHVyZVRva2Vu";
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"responseId":"gem_u1","modelVersion":"gemini-3-flash",
                     "candidates":[{"content":{"role":"model","parts":[
                       {"text":"checking","thought":true},
                       {"functionCall":{"id":"c1","name":"default_api:_x_1mcp-read","args":{"path":"main.java"}},
                        "thoughtSignature":"%s"}
                     ]},"finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":50,"cachedContentTokenCount":30,
                       "candidatesTokenCount":12,"thoughtsTokenCount":8,"totalTokenCount":70}}
                    """.formatted(rawSig)).build());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"responseId":"gem_s1","modelVersion":"gemini-3-flash","candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true}]}}]}

                    data: {"responseId":"gem_s1","modelVersion":"gemini-3-flash","candidates":[{"content":{"role":"model","parts":[{"text":"All good!"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":40,"cachedContentTokenCount":10,"candidatesTokenCount":6,"thoughtsTokenCount":4,"totalTokenCount":50}}

                    """).build());
            server.start();

            GeminiClient client = geminiClient(server);
            ApiMessage firstTurn = client.createMessage(CreateMessageRequest.builder()
                .model("models/gemini-3-flash")
                .systemPrompt("be concise")
                .effort("medium")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "inspect main.java")))
                .tools(List.of(new CreateMessageRequest.ToolDefinition(
                    "1mcp-read", "MCP read tool starting with digit",
                    JsonUtils.parseTree("{\"properties\":{\"path\":{}}}"))))
                .stream(false)
                .build());

            RecordedRequest req1 = server.takeRequest();
            assertEquals("/v1beta/models/gemini-3-flash:generateContent", req1.getUrl().encodedPath());
            assertEquals("k", req1.getHeaders().get("x-goog-api-key"));
            JsonNode sent1 = JsonUtils.parseTree(req1.getBody().utf8());
            assertEquals("be concise",
                sent1.path("systemInstruction").path("parts").get(0).path("text").asText());
            assertEquals("medium",
                sent1.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
            assertEquals("_x_1mcp-read", sent1.path("tools").get(0)
                .path("functionDeclarations").get(0).path("name").asText());
            assertEquals("string", sent1.path("tools").get(0)
                .path("functionDeclarations").get(0).path("parametersJsonSchema")
                .path("properties").path("path").path("type").asText());

            assertEquals("tool_use", firstTurn.stopReason());
            assertEquals(20, firstTurn.usage().inputTokens());
            assertEquals(30, firstTurn.usage().cacheReadInputTokens());
            assertEquals(20, firstTurn.usage().outputTokens());
            ToolUseBlock called = firstTurn.content().stream()
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
            assertEquals("1mcp-read", called.name());
            assertTrue(Strings.CS.contains(called.id(), "__ts"),
                "Gemini thoughtSignature must ride inside ToolUseBlock.id");

            // Second turn: streamGenerateContent sends thoughtSignature back on functionCall
            List<StreamEvent> streamEvents = new ArrayList<>();
            client.createMessageStream(CreateMessageRequest.builder()
                .model("gemini-3-flash")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "inspect main.java"),
                    new CreateMessageRequest.RequestMessage("assistant", firstTurn.content()),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("c1", List.of(new TextBlock("class Main {}")), false),
                        new TextBlock("and summarize it")))))
                .stream(true)
                .build()).forEachRemaining(streamEvents::add);

            RecordedRequest req2 = server.takeRequest();
            assertEquals("/v1beta/models/gemini-3-flash:streamGenerateContent",
                req2.getUrl().encodedPath());
            assertEquals("alt=sse", req2.getUrl().encodedQuery());
            JsonNode sent2 = JsonUtils.parseTree(req2.getBody().utf8());
            JsonNode contents2 = sent2.path("contents");
            // Gemini requires functionResponse and user follow-up text in separate user turns
            assertEquals(4, contents2.size());
            JsonNode modelPartCall = contents2.get(1).path("parts").get(1);
            assertEquals("c1", modelPartCall.path("functionCall").path("id").asText());
            assertEquals(rawSig, modelPartCall.path("thoughtSignature").asText());
            assertTrue(contents2.get(2).path("parts").get(0).has("functionResponse"));
            assertEquals("and summarize it", contents2.get(3).path("parts").get(0).path("text").asText());

            ApiMessage streamed = ProtocolWireCodec.aggregateEventsToMessage(streamEvents, "gemini-3-flash");
            assertEquals("end_turn", streamed.stopReason());
            assertEquals(30, streamed.usage().inputTokens());
            assertEquals(10, streamed.usage().cacheReadInputTokens());
            assertEquals(10, streamed.usage().outputTokens());
        }
    }

    /** Adapted from magpie {@code gemini_signature_test.go} & {@code gemini_compat_cut_test.go}. */
    @Test
    void geminiThoughtSignatureRidesThroughChatExtraContentAndDetectsTruncatedIds() throws Exception {
        String sig = "R2VtaW5pU2lnbmF0dXJlMTIzNDU2Nzg5MA==";
        String signedId = GeminiThoughtSignature.signToolId("call_abc", sig);
        GeminiThoughtSignature.UnsignedToolId intact = GeminiThoughtSignature.unsignToolId(signedId);
        assertEquals("call_abc", intact.id());
        assertEquals(sig, intact.signature());

        // Simulate a Chat client truncating tool_call_id to 20 chars (#1536)
        String truncated = signedId.substring(0, 20);
        GeminiThoughtSignature.UnsignedToolId cut = GeminiThoughtSignature.unsignToolId(truncated);
        assertEquals("", cut.signature(), "truncated signature must be discarded rather than sent corrupted");

        // On a Gemini-named Chat endpoint, intact signature goes in extra_content and unsigned gets skip validator
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("google/gemini-3-pro")
            .messages(List.of(
                new CreateMessageRequest.RequestMessage("user", "go"),
                new CreateMessageRequest.RequestMessage("assistant", List.of(
                    new ToolUseBlock(signedId, "bash", JsonUtils.parseTree("{\"cmd\":\"pwd\"}")))),
                new CreateMessageRequest.RequestMessage("user", List.of(
                    new ToolResultBlock(signedId, List.of(new TextBlock("/tmp")), false))),
                new CreateMessageRequest.RequestMessage("assistant", List.of(
                    new ToolUseBlock(truncated, "bash", JsonUtils.parseTree("{\"cmd\":\"ls\"}")))),
                new CreateMessageRequest.RequestMessage("user", List.of(
                    new ToolResultBlock(truncated, List.of(new TextBlock("a.txt")), false)))))
            .build();

        String encodedChat = ProtocolWireCodec.encodeRequest(
            ModelApiProtocol.OPENAI_CHAT, req, "https://openrouter.ai/api/v1", false);
        JsonNode chatJson = JsonUtils.parseTree(encodedChat);
        JsonNode firstCall = chatJson.path("messages").get(1).path("tool_calls").get(0);
        assertEquals("call_abc", firstCall.path("id").asText());
        assertEquals(sig, firstCall.path("extra_content").path("google").path("thought_signature").asText());
        assertEquals("call_abc", chatJson.path("messages").get(2).path("tool_call_id").asText());

        JsonNode secondCall = chatJson.path("messages").get(3).path("tool_calls").get(0);
        assertEquals(GeminiThoughtSignature.SKIP_SIGNATURE,
            secondCall.path("extra_content").path("google").path("thought_signature").asText());
    }

    /** Adapted from magpie {@code gemini_media_test.go} (snake_case proto JSON & OpenAPI schema). */
    @Test
    void geminiSnakeCaseRequestAndOpenApiUppercaseSchemaParseCleanly() throws Exception {
        String geminiSnakeBody = """
            {
              "model": "models/gemini-2.5-pro",
              "system_instruction": {"parts": [{"text": "system rule"}]},
              "generation_config": {
                "max_output_tokens": 512,
                "thinking_config": {"thinking_budget": 8000, "include_thoughts": true}
              },
              "contents": [{
                "role": "user",
                "parts": [
                  {"text": "check file"},
                  {"inline_data": {"mime_type": "application/pdf", "data": "JVBERi0xLjQK"}}
                ]
              }],
              "tools": [{
                "function_declarations": [{
                  "name": "query_db",
                  "parameters": {
                    "type": "OBJECT",
                    "propertyOrdering": ["sql_text"],
                    "properties": {"sql_text": {"type": "STRING"}}
                  }
                }]
              }]
            }
            """;

        CreateMessageRequest ir = ProtocolWireCodec.parseRequest(ModelApiProtocol.GEMINI, geminiSnakeBody);
        assertEquals("gemini-2.5-pro", ir.model());
        assertEquals("system rule", ir.systemPrompt());
        assertEquals(512, ir.maxTokens());
        assertEquals("medium", ir.effort());
        assertEquals(2, ((List<?>) ir.messages().getFirst().content()).size());
        JsonNode toolSchema = ir.tools().getFirst().inputSchema();
        assertEquals("object", toolSchema.path("type").asText());
        // Caller's own property name snake_case ("sql_text") MUST NOT be rewritten to camelCase!
        assertTrue(toolSchema.path("properties").has("sql_text"));
        assertEquals("string", toolSchema.path("properties").path("sql_text").path("type").asText());
        assertFalse(toolSchema.has("propertyOrdering"));
    }

    // ── 4 x 4 Stateless Protocol Matrix Acceptance ────────────────────────────

    /**
     * Exercises all 16 {@code (sourceProtocol × targetProtocol)} pairs across
     * {@code ANTHROPIC}, {@code OPENAI_CHAT}, {@code OPENAI_RESPONSES}, and {@code GEMINI}
     * for both multi-turn tool-call requests and unary/SSE responses.
     */
    @Test
    void fourProtocolBidirectionalMatrixTranslatesRequestsResponsesAndSseStreams() throws Exception {
        Map<ModelApiProtocol, String> sampleRequests = Map.of(
            ModelApiProtocol.ANTHROPIC, """
                {"model":"m-test","max_tokens":256,"system":"sys","messages":[
                  {"role":"user","content":"run tool"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"ping","input":{"x":1}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"pong"}]}
                ],"tools":[{"name":"ping","description":"d","input_schema":{"type":"object","properties":{"x":{"type":"integer"}}}}]}
                """,
            ModelApiProtocol.OPENAI_CHAT, """
                {"model":"m-test","max_tokens":256,"messages":[
                  {"role":"system","content":"sys"},
                  {"role":"user","content":"run tool"},
                  {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"ping","arguments":"{\\"x\\":1}"}}]},
                  {"role":"tool","tool_call_id":"call_1","content":"pong"}
                ],"tools":[{"type":"function","function":{"name":"ping","description":"d","parameters":{"type":"object","properties":{"x":{"type":"integer"}}}}}]}
                """,
            ModelApiProtocol.OPENAI_RESPONSES, """
                {"model":"m-test","max_output_tokens":256,"instructions":"sys","input":[
                  {"type":"message","role":"user","content":[{"type":"input_text","text":"run tool"}]},
                  {"type":"function_call","call_id":"call_1","name":"ping","arguments":"{\\"x\\":1}"},
                  {"type":"function_call_output","call_id":"call_1","output":"pong"}
                ],"tools":[{"type":"function","name":"ping","description":"d","parameters":{"type":"object","properties":{"x":{"type":"integer"}}}}]}
                """,
            ModelApiProtocol.GEMINI, """
                {"model":"models/m-test","systemInstruction":{"parts":[{"text":"sys"}]},
                 "generationConfig":{"maxOutputTokens":256},
                 "contents":[
                   {"role":"user","parts":[{"text":"run tool"}]},
                   {"role":"model","parts":[{"functionCall":{"id":"call_1","name":"ping","args":{"x":1}}}]},
                   {"role":"user","parts":[{"functionResponse":{"id":"call_1","name":"ping","response":{"output":"pong"}}}]}
                 ],"tools":[{"functionDeclarations":[{"name":"ping","description":"d","parametersJsonSchema":{"type":"object","properties":{"x":{"type":"integer"}}}}]}]}
                """
        );

        Map<ModelApiProtocol, String> sampleResponses = Map.of(
            ModelApiProtocol.ANTHROPIC, """
                {"id":"m1","type":"message","role":"assistant","model":"m-test",
                 "content":[{"type":"thinking","thinking":"hmm","signature":"sig1"},{"type":"text","text":"hello"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":4}}
                """,
            ModelApiProtocol.OPENAI_CHAT, """
                {"id":"c1","object":"chat.completion","model":"m-test",
                 "choices":[{"index":0,"message":{"role":"assistant","reasoning_content":"hmm","content":"hello"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                """,
            ModelApiProtocol.OPENAI_RESPONSES, """
                {"id":"r1","object":"response","status":"completed","model":"m-test",
                 "output":[
                   {"type":"reasoning","id":"rs_0","summary":[{"type":"summary_text","text":"hmm"}]},
                   {"type":"message","id":"msg_1","role":"assistant","content":[{"type":"output_text","text":"hello"}]}
                 ],"usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14}}
                """,
            ModelApiProtocol.GEMINI, """
                {"responseId":"g1","modelVersion":"m-test",
                 "candidates":[{"index":0,"content":{"role":"model","parts":[{"text":"hmm","thought":true},{"text":"hello"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"thoughtsTokenCount":2,"totalTokenCount":14}}
                """
        );

        for (ModelApiProtocol from : ModelApiProtocol.values()) {
            for (ModelApiProtocol to : ModelApiProtocol.values()) {
                String translatedReq = ProtocolWireCodec.translateRequest(
                    from, to, sampleRequests.get(from), "https://example.test/v1");
                CreateMessageRequest reparsedReq = ProtocolWireCodec.parseRequest(to, translatedReq);
                assertEquals("sys", reparsedReq.systemPrompt(), from + " -> " + to);
                assertFalse(reparsedReq.tools().isEmpty(), from + " -> " + to);

                String translatedResp = ProtocolWireCodec.translateResponse(
                    from, to, sampleResponses.get(from), Set.of());
                ApiMessage reparsedResp = ProtocolWireCodec.parseResponse(to, translatedResp, Set.of());
                assertTrue(reparsedResp.content().stream()
                    .anyMatch(b -> b instanceof TextBlock(String t) && Strings.CS.equals("hello", t)),
                    from + " -> " + to + ": " + translatedResp);
            }
        }
    }

    // ── High-Order Compatibility Capabilities ─────────────────────────────────

    /**
     * Adapted from magpie {@code claudeseal.go} & {@code reasoning_state_claude_codex_test.go}.
     * Anthropic signed {@code thinking} and {@code redacted_thinking} seal into Responses
     * {@code encrypted_content} as {@code magpie-claude1:<base64>}, restore intact back to
     * Anthropic, and strip cleanly before any native OpenAI Responses upstream.
     */
    @Test
    void claudeSealRoundTripsSignedAndRedactedThinkingThroughResponsesAndStripsForOpenAi()
            throws Exception {
        ApiMessage claudeReply = new ApiMessage(
            "msg_claude", "message", "assistant",
            List.of(
                new ThinkingBlock("deep analysis", "claude_sig_xyz"),
                new RedactedThinkingBlock("opaque_redacted_blob"),
                new ToolUseBlock("toolu_1", "bash", JsonUtils.parseTree("{\"command\":\"pwd\"}"))),
            "claude-sonnet-4-6", "tool_use", null, null);

        String responsesJson = ProtocolWireCodec.encodeResponse(
            ModelApiProtocol.OPENAI_RESPONSES, claudeReply);
        JsonNode respTree = JsonUtils.parseTree(responsesJson);
        String enc0 = respTree.path("output").get(0).path("encrypted_content").asText();
        String enc1 = respTree.path("output").get(1).path("encrypted_content").asText();
        assertTrue(ClaudeSeal.isSealed(enc0));
        assertTrue(ClaudeSeal.isSealed(enc1));

        // Codex sends those output items back in the next Responses request's input[]:
        var nextResponsesReq = JsonUtils.getMapper().createObjectNode();
        nextResponsesReq.put("model", "claude-sonnet-4-6");
        var input = nextResponsesReq.putArray("input");
        input.addObject().put("type", "message").put("role", "user").put("content", "start");
        respTree.path("output").forEach(input::add);
        input.addObject().put("type", "function_call_output").put("call_id", "toolu_1").put("output", "/work");

        // 1) Routed back to Anthropic: signed thinking + redacted_thinking are restored whole!
        String toAnthropic = ProtocolWireCodec.translateRequest(
            ModelApiProtocol.OPENAI_RESPONSES, ModelApiProtocol.ANTHROPIC,
            nextResponsesReq.toString(), "https://api.anthropic.com");
        JsonNode anthropicMsgs = JsonUtils.parseTree(toAnthropic).path("messages");
        JsonNode assistantBlocks = anthropicMsgs.get(1).path("content");
        assertEquals("thinking", assistantBlocks.get(0).path("type").asText());
        assertEquals("claude_sig_xyz", assistantBlocks.get(0).path("signature").asText());
        assertEquals("redacted_thinking", assistantBlocks.get(1).path("type").asText());
        assertEquals("opaque_redacted_blob", assistantBlocks.get(1).path("data").asText());

        // 2) Switched mid-session to OpenAI Responses upstream: magpie-claude1: is stripped!
        String toOpenAi = ProtocolWireCodec.translateRequest(
            ModelApiProtocol.OPENAI_RESPONSES, ModelApiProtocol.OPENAI_RESPONSES,
            nextResponsesReq.toString(), "https://api.openai.com/v1");
        assertFalse(Strings.CS.contains(toOpenAi, ClaudeSeal.MARK),
            "magpie-claude1: must never reach native OpenAI Responses upstream");
    }

    /**
     * Adapted from magpie {@code textcalls_test.go}, {@code textcalls_glm_test.go},
     * and {@code textcalls_invoke_test.go}.
     */
    @Test
    void textCallRescueConvertsHermesGlmDsmlAndClaudeInvokeWhileRespectingCodeFences()
            throws Exception {
        var tools = List.of(
            new CreateMessageRequest.ToolDefinition("bash", "run shell",
                JsonUtils.parseTree("{\"type\":\"object\"}")),
            new CreateMessageRequest.ToolDefinition("edit", "edit file",
                JsonUtils.parseTree("{\"type\":\"object\"}")));

        // 1) Streaming DSML with string="true" vs JSON integer parameter
        String dsmlSse = """
            data: {"id":"c1","model":"deepseek-v3","choices":[{"delta":{"content":"Running it.\\n\\n<｜DSML｜function_calls>\\n<｜DSML｜invoke name=\\"bash\\">\\n<｜DSML｜parameter name=\\"command\\" string=\\"true\\">30</｜DSML｜parameter>\\n"}}]}

            data: {"id":"c1","choices":[{"delta":{"content":"<｜DSML｜parameter name=\\"timeout\\" string=\\"false\\">30</｜DSML｜parameter>\\n</｜DSML｜invoke>\\n</｜DSML｜function_calls>\\n*Result: fake hallucinated output*"}}]}

            data: {"id":"c1","choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;
        String anthropicSse = ProtocolWireCodec.translateSseStream(
            ModelApiProtocol.OPENAI_CHAT, ModelApiProtocol.ANTHROPIC, dsmlSse, "deepseek-v3",
            Set.of("bash", "edit"));
        assertFalse(Strings.CS.contains(anthropicSse, "DSML"));
        assertFalse(Strings.CS.contains(anthropicSse, "fake hallucinated output"));
        assertTrue(Strings.CS.contains(anthropicSse, "\"stop_reason\":\"tool_use\""));
        assertTrue(Strings.CS.contains(anthropicSse, "\\\"command\\\":\\\"30\\\""));
        assertTrue(Strings.CS.contains(anthropicSse, "\\\"timeout\\\":30"));

        // 2) GLM <arg_key>/<arg_value>, Hermes <tool_call>, and fenced code block immunity
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"glm_1","model":"glm-5",
                     "choices":[{"message":{"content":"<tool_call>bash<arg_key>command</arg_key><arg_value>ls -la</arg_value><arg_key>timeout</arg_key><arg_value>15</arg_value></tool_call>"},"finish_reason":"stop"}]}
                    """).build());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"fence_1","model":"glm-5",
                     "choices":[{"message":{"content":"Example:\\n```xml\\n<tool_call>{\\"name\\":\\"bash\\",\\"arguments\\":{\\"command\\":\\"rm -rf /\\"}}</tool_call>\\n```"},"finish_reason":"stop"}]}
                    """).build());
            server.start();

            ApiMessage glmMsg = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("glm-5").tools(tools).stream(false).build());
            assertEquals("tool_use", glmMsg.stopReason());
            ToolUseBlock glmCall = assertInstanceOf(ToolUseBlock.class, glmMsg.content().getFirst());
            assertEquals("bash", glmCall.name());
            assertEquals("ls -la", glmCall.input().path("command").asText());
            assertEquals(15, glmCall.input().path("timeout").asInt());

            ApiMessage fencedMsg = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("glm-5").tools(tools).stream(false).build());
            assertEquals("end_turn", fencedMsg.stopReason());
            assertTrue(fencedMsg.content().stream().noneMatch(ToolUseBlock.class::isInstance),
                "fenced code block example must never execute as a rescued tool call");
        }
    }

    /**
     * Adapted from magpie {@code deepseek_schema_test.go}, {@code kimi_schema_test.go},
     * and {@code gemini_schema_test.go}.
     */
    @Test
    void vendorSchemaSanitizerNormalizesDeepSeekNullEscapeKimiEnumsAndGeminiSchemas()
            throws Exception {
        // 1) DeepSeek regex \0 -> \u0000 while keeping \01 octal/backref and \\0 literal
        JsonNode dsSchema = JsonUtils.parseTree("""
            {"type":"object","properties":{
              "clean":{"type":"string","pattern":"^[^\\u0000]+$"},
              "nul":{"type":"string","pattern":"^[^\\\\0]+$"},
              "octal":{"type":"string","pattern":"^\\\\01+$"},
              "escaped":{"type":"string","pattern":"^\\\\\\\\0+$"}
            }}
            """);
        JsonNode dsSanitized = VendorSchemaSanitizer.sanitizeToolSchema(
            dsSchema, "https://api.deepseek.com/v1", "deepseek-chat");
        assertEquals("^[^\\u0000]+$", dsSanitized.path("properties").path("nul").path("pattern").asText());
        assertEquals("^\\01+$", dsSanitized.path("properties").path("octal").path("pattern").asText());
        assertEquals("^\\\\0+$", dsSanitized.path("properties").path("escaped").path("pattern").asText());

        // 2) Kimi/Moonshot untyped enum & nullable enum
        JsonNode kimiSchema = JsonUtils.parseTree("""
            {"type":"object","properties":{
              "kind":{"enum":["text","icon"]},
              "nullableKind":{"enum":["text",null]},
              "constInt":{"const":42}
            }}
            """);
        JsonNode kimiSanitized = VendorSchemaSanitizer.sanitizeToolSchema(
            kimiSchema, "https://api.moonshot.ai/v1", "kimi-k2");
        assertEquals("string", kimiSanitized.path("properties").path("kind").path("type").asText());
        assertEquals("[\"string\",\"null\"]",
            kimiSanitized.path("properties").path("nullableKind").path("type").toString());
        assertEquals("integer", kimiSanitized.path("properties").path("constInt").path("type").asText());

        // 3) Gemini untyped schemas, boolean schemas, and x- vendor annotations
        JsonNode geminiSchema = JsonUtils.parseTree("""
            {"properties":{
              "any":{},
              "x-user-prop":{"x-mcp-header":"secret","enum":["a","b"]},
              "boolTrue":true,
              "boolFalse":false,
              "matrix":{"items":{"items":true}},
              "refProp":{"$ref":"#/$defs/D"},
              "dflt":{"default":3}
            },"required":["any","boolFalse"],"$defs":{"D":{"properties":{}}}}
            """);
        JsonNode gemSanitized = VendorSchemaSanitizer.sanitizeToolSchema(
            geminiSchema, "https://openrouter.ai/api/v1", "google/gemini-3.8-flash");
        assertEquals("object", gemSanitized.path("type").asText());
        assertEquals("string", gemSanitized.path("properties").path("any").path("type").asText());
        assertTrue(gemSanitized.path("properties").has("x-user-prop"),
            "user property named x-* must be kept");
        assertFalse(gemSanitized.path("properties").path("x-user-prop").has("x-mcp-header"),
            "vendor schema annotation x-mcp-header must be stripped");
        assertEquals("string",
            gemSanitized.path("properties").path("x-user-prop").path("type").asText());
        assertEquals("string",
            gemSanitized.path("properties").path("boolTrue").path("type").asText());
        assertFalse(gemSanitized.path("properties").has("boolFalse"));
        assertEquals(List.of("any"),
            JsonUtils.getMapper().convertValue(gemSanitized.path("required"), List.class));
        assertEquals("array", gemSanitized.path("properties").path("matrix").path("type").asText());
        assertEquals("string", gemSanitized.path("properties").path("matrix")
            .path("items").path("items").path("type").asText());
        assertEquals("object", gemSanitized.path("properties").path("refProp").path("type").asText());
        assertEquals("integer", gemSanitized.path("properties").path("dflt").path("type").asText());
    }

    /**
     * Adapted from magpie {@code gateway_test.go:TestWrongEndpoint},
     * {@code format_refused_test.go}, and {@code thinking_off_test.go}.
     */
    @Test
    void customModelRouterLearnsWrongEndpointAndStructuredFormatFallbacks() throws Exception {
        // 1) WrongEndpoint fallback: configured as OPENAI_CHAT, relay says use /v1/responses in Chinese,
        //    router switches to /v1/responses and caches it so the 2nd turn hits /v1/responses directly!
        List<String> hitPaths = new ArrayList<>();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getUrl().encodedPath();
                    synchronized (hitPaths) {
                        hitPaths.add(path);
                    }
                    if (Strings.CS.endsWith(path, "/chat/completions")) {
                        return new MockResponse.Builder().code(400)
                            .addHeader("Content-Type", "application/json")
                            .body("{\"error\":{\"message\":\"模型 gpt-5.6-sol 不支持 /v1/chat/completions 接口，请改用 /v1/responses\"}}")
                            .build();
                    }
                    return new MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "application/json")
                        .body("""
                            {"id":"r_ok","model":"gpt-5.6-sol","status":"completed",
                             "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"responses ok"}]}],
                             "usage":{"input_tokens":3,"output_tokens":2}}
                            """)
                        .build();
                }
            });
            server.start();

            CustomModelConfig cfg = new CustomModelConfig("gpt-5.6-sol", ModelApiProtocol.OPENAI_CHAT,
                server.url("/v1").toString(), "k", Map.of());
            CustomModelRoutingClient router = CustomModelRoutingClient.standard(
                chatClient(server),
                name -> Strings.CS.equals("gpt-5.6-sol", name) ? Optional.of(cfg) : Optional.empty());

            ApiMessage first = router.createMessage(CreateMessageRequest.builder()
                .model("gpt-5.6-sol")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "1")))
                .stream(false).build());
            ApiMessage second = router.createMessage(CreateMessageRequest.builder()
                .model("gpt-5.6-sol")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "2")))
                .stream(false).build());

            assertEquals("responses ok", ((TextBlock) first.content().getFirst()).text());
            assertEquals("responses ok", ((TextBlock) second.content().getFirst()).text());
            assertEquals(List.of("/v1/chat/completions", "/v1/responses", "/v1/responses"), hitPaths);
        }

        // 2) Structured format degradation: json_schema rejected -> json_object + schema in system prompt
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(400)
                .addHeader("Content-Type", "application/json")
                .body("{\"error\":{\"message\":\"response_format type 'json_schema' is not supported, only 'json_object' is allowed\"}}")
                .build());
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"c_fmt","model":"mimo","choices":[{"message":{"content":"{\\"ok\\":true}"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":5,"completion_tokens":2}}
                    """)
                .build());
            server.start();

            CustomModelConfig cfg = new CustomModelConfig("mimo", ModelApiProtocol.OPENAI_CHAT,
                server.url("/v1").toString(), "k", Map.of());
            CustomModelRoutingClient router = CustomModelRoutingClient.standard(
                chatClient(server),
                name -> Strings.CS.equals("mimo", name) ? Optional.of(cfg) : Optional.empty());

            var fmt = JsonUtils.getMapper().createObjectNode();
            fmt.put("type", "json_schema");
            fmt.put("name", "Res");
            fmt.set("schema", JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}"));

            ApiMessage res = router.createMessage(CreateMessageRequest.builder()
                .model("mimo")
                .systemPrompt("base sys")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "go")))
                .outputConfig(new CreateMessageRequest.OutputConfig(null, fmt))
                .stream(false).build());
            assertNotNull(res);

            JsonNode attempt1 = JsonUtils.parseTree(server.takeRequest().getBody().utf8());
            JsonNode attempt2 = JsonUtils.parseTree(server.takeRequest().getBody().utf8());
            assertEquals("json_schema", attempt1.path("response_format").path("type").asText());
            assertEquals("json_object", attempt2.path("response_format").path("type").asText());
            assertTrue(Strings.CS.contains(attempt2.path("messages").get(0).path("content").asText(),
                "matching this JSON schema"));
        }
    }

    private static ToolUseBlock call(String id) {
        return new ToolUseBlock(id, "run", JsonUtils.getMapper().createObjectNode());
    }

    private static ToolResultBlock result(String id, String text) {
        return new ToolResultBlock(id, List.of(new TextBlock(text)), false);
    }

    private static String chatTrace(JsonNode messages) {
        List<String> parts = new ArrayList<>();
        for (JsonNode msg : messages) {
            String role = msg.path("role").asText();
            if (msg.hasNonNull("tool_calls") && !msg.path("tool_calls").isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (JsonNode tc : msg.path("tool_calls")) {
                    ids.add(tc.path("id").asText());
                }
                parts.add(role + "(" + String.join(",", ids) + ")");
            } else if (msg.hasNonNull("tool_call_id")) {
                parts.add(role + "(" + msg.path("tool_call_id").asText() + ")");
            } else {
                parts.add(role);
            }
        }
        return String.join(" ", parts);
    }

    private static OpenAiCompatClient chatClient(MockWebServer server) {
        var config = new ApiConfig.OpenAiConfig("k", "m1", server.url("/v1").toString(),
            ModelApiProtocol.OPENAI_CHAT, Map.of());
        return new OpenAiCompatClient(config, HttpClientFactory.shared());
    }

    private static OpenAiResponsesClient responsesClient(MockWebServer server) {
        var config = new ApiConfig.OpenAiConfig("k", "m1", server.url("/v1").toString(),
            ModelApiProtocol.OPENAI_RESPONSES, Map.of());
        return new OpenAiResponsesClient(config, HttpClientFactory.shared());
    }

    private static GeminiClient geminiClient(MockWebServer server) {
        var config = new ApiConfig.OpenAiConfig("k", "gemini-2.5-flash",
            server.url("/v1beta").toString(), ModelApiProtocol.GEMINI, Map.of());
        return new GeminiClient(config, HttpClientFactory.shared());
    }

    private static MockWebServer okChatServer() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json")
            .body("""
                {"id":"c1","model":"m1",
                 "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":2,"completion_tokens":1}}
                """).build());
        server.start();
        return server;
    }

    private static MockWebServer okResponsesServer() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json")
            .body("""
                {"id":"resp_1","model":"m1","status":"completed",
                 "output":[{"type":"message","role":"assistant",
                   "content":[{"type":"output_text","text":"ok"}]}],
                 "usage":{"input_tokens":2,"output_tokens":1}}
                """).build());
        server.start();
        return server;
    }

    private static MockWebServer okGeminiServer() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json")
            .body("""
                {"responseId":"gem_1","modelVersion":"gemini-2.5-flash",
                 "candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1,"totalTokenCount":3}}
                """).build());
        server.start();
        return server;
    }
}
