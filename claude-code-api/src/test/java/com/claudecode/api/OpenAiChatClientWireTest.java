package com.claudecode.api;

import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * <ul>
 *   <li>OpenAI Chat Completions mock-wire acceptance when no live Chat endpoint is available.</li>
 * </ul>
 */
class OpenAiChatClientWireTest {

    @Test
    void nonStreamingRequestUsesChatEndpointCustomHeadersAndStreamFalse() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chatcmpl_1","model":"chat-test",
                     "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":2,"completion_tokens":1}}
                    """).build());
            server.start();

            var config = new ApiConfig.OpenAiConfig("key", "chat-test", server.url("/v1").toString(),
                ModelApiProtocol.OPENAI_CHAT, Map.of("X-Custom", "yes"));
            var client = new OpenAiCompatClient(config, HttpClientFactory.shared());
            client.createMessage(CreateMessageRequest.builder().model("chat-test")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "Hi")))
                .stream(false).build());

            var recorded = server.takeRequest();
            assertEquals("/v1/chat/completions", recorded.getUrl().encodedPath());
            assertEquals("yes", recorded.getHeaders().get("X-Custom"));
            assertFalse(JsonUtils.parseTree(recorded.getBody().utf8()).path("stream").asBoolean());
        }
    }

    @Test
    void stripsInternalContextTagFromChatWireModel() throws Exception {
        try (MockWebServer server = chatServer()) {
            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("chat-test[1m]").stream(false).build());

            assertEquals("chat-test", JsonUtils.parseTree(
                server.takeRequest().getBody().utf8()).path("model").asText());
        }
    }

    @Test
    void nonStreamingUsagePreservesCachedTokenDetailWithoutAddingItToGptContext() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chatcmpl_cached","model":"gpt-5.6-sol",
                     "choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":20,"completion_tokens":8,
                       "prompt_tokens_details":{"cached_tokens":12},"total_tokens":29}}
                    """).build());
            server.start();

            var config = new ApiConfig.OpenAiConfig("key", "gpt-5.6-sol",
                server.url("/v1").toString(), ModelApiProtocol.OPENAI_CHAT, Map.of());
            var message = new OpenAiCompatClient(config, HttpClientFactory.shared()).createMessage(
                CreateMessageRequest.builder().model("gpt-5.6-sol").stream(false).build());

            assertEquals(12, message.usage().cacheReadInputTokens());
            assertEquals(8, message.usage().inputTokens());
            assertEquals(29, message.usage().reportedTotalTokens());
            assertEquals(29, TokenEstimator.contextTokens(
                message.usage(), "gpt-5.6-sol"));
        }
    }

    @Test
    void lowersAssistantToolCallsAndToolResultsToNativeChatMessages() throws Exception {
        try (MockWebServer server = chatServer()) {
            var client = chatClient(server);
            client.createMessage(CreateMessageRequest.builder().model("chat-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "Weather?"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("call_1", "lookup", JsonUtils.parseTree("{\"city\":\"Paris\"}")))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("call_1", List.of(new TextBlock("sunny")), false)))))
                .stream(false).build());

            var messages = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            var assistant = messages.get(1);
            assertEquals("assistant", assistant.path("role").asText());
            assertTrue(assistant.path("content").isNull());
            assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
            assertEquals("lookup", assistant.path("tool_calls").get(0).path("function").path("name").asText());
            var tool = messages.get(2);
            assertEquals("tool", tool.path("role").asText());
            assertEquals("call_1", tool.path("tool_call_id").asText());
            assertEquals("sunny", tool.path("content").asText());
        }
    }

    @Test
    void lowersUserImagesAndAssistantReasoningContent() throws Exception {
        try (MockWebServer server = chatServer()) {
            var source = JsonUtils.getMapper().createObjectNode()
                .put("type", "base64")
                .put("media_type", "image/png")
                .put("data", "AAECAw==");
            var client = chatClient(server);
            client.createMessage(CreateMessageRequest.builder().model("chat-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new TextBlock("Inspect"), new ImageBlock(source))),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ThinkingBlock("internal"), new TextBlock("answer")))))
                .stream(false).build());

            var messages = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("image_url", messages.get(0).path("content").get(1).path("type").asText());
            assertEquals("data:image/png;base64,AAECAw==",
                messages.get(0).path("content").get(1).path("image_url").path("url").asText());
            assertEquals("answer", messages.get(1).path("content").asText());
            assertEquals("internal", messages.get(1).path("reasoning_content").asText());
        }
    }

    @Test
    void ordersParallelToolResponsesBeforeAggregatedVisionMessage() throws Exception {
        try (MockWebServer server = chatServer()) {
            var source = JsonUtils.getMapper().createObjectNode()
                .put("type", "base64").put("media_type", "image/png").put("data", "AAECAw==");
            chatClient(server).createMessage(CreateMessageRequest.builder().model("chat-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("call_1", List.of(new TextBlock("one"), new ImageBlock(source)), false))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("call_2", List.of(new TextBlock("two"), new ImageBlock(source)), false)))))
                .stream(false).build());

            var messages = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("messages");
            assertEquals("tool", messages.get(0).path("role").asText());
            assertEquals("call_1", messages.get(0).path("tool_call_id").asText());
            assertEquals("tool", messages.get(1).path("role").asText());
            assertEquals("call_2", messages.get(1).path("tool_call_id").asText());
            assertEquals("user", messages.get(2).path("role").asText());
            assertEquals(2, messages.get(2).path("content").size());
        }
    }

    @Test
    void preservesStreamedToolCallIdAndUsage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"chat_1","model":"chat-test","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_real","type":"function","function":{"name":"lookup","arguments":"{\\\"city\\\":\\\"Paris\\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"id":"chat_1","model":"chat-test","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":4}}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            var tool = events.stream()
                .filter(StreamEvent.ContentBlockStart.class::isInstance)
                .map(StreamEvent.ContentBlockStart.class::cast)
                .map(StreamEvent.ContentBlockStart::contentBlock)
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
            assertEquals("call_real", tool.id());
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.InputJsonDelta input
                && Strings.CS.contains(input.partialJson(), "Paris")));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.MessageDelta delta
                && delta.usage().inputTokens() == 11 && delta.usage().outputTokens() == 4));
        }
    }

    /**
     * vLLM-style servers repeat {@code {"id":null,"function":{"name":null,"arguments":"…"}}} on every
     * continuation chunk. {@code NullNode.asText()} is the string {@code "null"}, so treating the
     * present-but-null key as a value used to overwrite the real name and reach the tool router as
     * {@code No such tool available: null}.
     */
    @Test
    void keepsTheToolNameWhenContinuationChunksRepeatExplicitNulls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"chat_1","model":"chat-test","choices":[{"delta":{"tool_calls":[{"id":"call_q","index":0,"type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}

                    data: {"id":"chat_1","choices":[{"delta":{"tool_calls":[{"id":null,"index":0,"type":"function","function":{"name":null,"arguments":"{"}}]},"finish_reason":null}]}

                    data: {"id":"chat_1","choices":[{"delta":{"tool_calls":[{"id":null,"index":0,"type":"function","function":{"name":null,"arguments":"\\"file_path\\": \\"/tmp/notes.txt\\""}}]},"finish_reason":null}]}

                    data: {"id":"chat_1","choices":[{"delta":{"tool_calls":[{"id":null,"index":0,"type":"function","function":{"name":null,"arguments":"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            List<ToolUseBlock> tools = events.stream()
                .filter(StreamEvent.ContentBlockStart.class::isInstance)
                .map(StreamEvent.ContentBlockStart.class::cast)
                .map(StreamEvent.ContentBlockStart::contentBlock)
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
            assertFalse(tools.isEmpty(), "the streamed tool call must reach the consumer");
            tools.forEach(tool -> {
                assertEquals("Read", tool.name());
                assertEquals("call_q", tool.id());
            });
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.InputJsonDelta input
                && Strings.CS.contains(input.partialJson(), "/tmp/notes.txt")));
            // The same chunks spell out "content": null; a literal "null" must never be streamed
            // into the transcript as assistant text.
            assertTrue(events.stream()
                .filter(StreamEvent.ContentBlockDelta.class::isInstance)
                .map(StreamEvent.ContentBlockDelta.class::cast)
                .map(StreamEvent.ContentBlockDelta::delta)
                .filter(Delta.TextDelta.class::isInstance)
                .map(Delta.TextDelta.class::cast)
                .noneMatch(text -> Strings.CS.contains(text.text(), "null")),
                "explicit JSON nulls must not surface as assistant text");
        }
    }

    /** The non-streaming arm shares the {@code NullNode.asText()} hazard. */
    @Test
    void dropsExplicitNullContentInsteadOfEmittingTheStringNull() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":null,"model":null,"choices":[{"message":{"content":null,
                     "tool_calls":[{"id":null,"type":"function",
                       "function":{"name":"Read","arguments":"{\\"file_path\\":\\"/tmp/notes.txt\\"}"}}]},
                     "finish_reason":"tool_calls"}]}
                    """).build());
            server.start();

            ApiMessage message = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("chat-test").stream(false).build());

            assertTrue(message.content().stream().noneMatch(block -> block instanceof TextBlock text
                && Strings.CS.contains(text.text(), "null")));
            ToolUseBlock tool = message.content().stream()
                .filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
            assertEquals("Read", tool.name());
            assertTrue(Strings.CS.startsWith(tool.id(), "toolu_"),
                "a missing id is synthesized rather than becoming the string null");
            assertEquals("chat-test", message.model());
        }
    }

    /**
     * A second parallel tool call carrying an explicit {@code "index": null} must open its own
     * slot. {@code NullNode.asInt()} is 0, so it used to land in slot 0, replace the first call's
     * id and name, and concatenate its arguments onto the first call's accumulator — losing one
     * tool call and leaving the survivor with unparseable arguments.
     */
    @Test
    void appendsANewSlotWhenAParallelToolCallCarriesAnExplicitNullIndex() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"chat_1","model":"chat-test","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"first","arguments":"{\\"file_path\\":\\"/a\\"}"}}]},"finish_reason":null}]}

                    data: {"id":"chat_1","choices":[{"delta":{"tool_calls":[{"index":null,"id":"call_b","type":"function","function":{"name":"second","arguments":"{\\"command\\":\\"ls\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            List<ToolUseBlock> tools = events.stream()
                .filter(StreamEvent.ContentBlockStart.class::isInstance)
                .map(StreamEvent.ContentBlockStart.class::cast)
                .map(StreamEvent.ContentBlockStart::contentBlock)
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .toList();
            assertEquals(List.of("call_a", "call_b"),
                tools.stream().map(ToolUseBlock::id).toList(),
                "the null-index call must append a slot rather than overwrite slot 0");
            assertEquals(List.of("first", "second"),
                tools.stream().map(ToolUseBlock::name).toList());

            // Each call keeps its own arguments; the null-index call must not be concatenated
            // onto the first call's accumulator as {"file_path":"/a"}{"command":"ls"}.
            List<String> arguments = events.stream()
                .filter(StreamEvent.ContentBlockDelta.class::isInstance)
                .map(StreamEvent.ContentBlockDelta.class::cast)
                .map(StreamEvent.ContentBlockDelta::delta)
                .filter(Delta.InputJsonDelta.class::isInstance)
                .map(Delta.InputJsonDelta.class::cast)
                .map(Delta.InputJsonDelta::partialJson)
                .toList();
            assertEquals(List.of("{\"file_path\":\"/a\"}", "{\"command\":\"ls\"}"), arguments);
        }
    }

    /**
     * A tool call the model asked for is never silently dropped — a missing name or function
     * object fails with a named diagnostic, the way {@code requireJsonArguments} rejects
     * unparseable arguments. Above all, the literal string {@code "null"} must never become a
     * tool name and reach the router as {@code No such tool available: null}.
     */
    @Test
    void rejectsNonStreamingToolCallsWithAnExplicitNullNameOrMissingFunction() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_1","model":"chat-test","choices":[{"message":{"content":null,
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":null,"arguments":"{}"}}]},
                     "finish_reason":"tool_calls"}]}
                    """).build());
            server.start();

            ApiException failure = assertThrows(ApiException.class, () -> chatClient(server)
                .createMessage(CreateMessageRequest.builder()
                    .model("chat-test").stream(false).build()));
            assertTrue(Strings.CS.contains(failure.getMessage(), "function name"),
                "the diagnostic must name the malformed field, not surface as Request failed: null");
            assertFalse(Strings.CS.contains(failure.getMessage(), "No such tool"));
        }

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_1","model":"chat-test","choices":[{"message":{"content":null,
                     "tool_calls":[{"id":"call_1","type":"function"}]},
                     "finish_reason":"tool_calls"}]}
                    """).build());
            server.start();

            // An absent function object used to NPE inside createMessage, which the generic
            // catch turned into the opaque "Request failed: null".
            ApiException failure = assertThrows(ApiException.class, () -> chatClient(server)
                .createMessage(CreateMessageRequest.builder()
                    .model("chat-test").stream(false).build()));
            assertTrue(Strings.CS.contains(failure.getMessage(), "function object"));
            assertFalse(Strings.CS.contains(failure.getMessage(), "Request failed: null"));
        }
    }

    /**
     * An explicit {@code "arguments": null} is the server spelling out a no-argument call. It must
     * become {@code {}}, not the string {@code "null"} — which {@code requireJsonArguments} would
     * happily parse into a JSON null node and hand to the tool as its input.
     */
    @Test
    void treatsAnExplicitNullArgumentsFieldAsAnEmptyObject() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_1","model":"chat-test","choices":[{"message":{"content":null,
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":"ListMcpResources","arguments":null}}]},
                     "finish_reason":"tool_calls"}]}
                    """).build());
            server.start();

            ApiMessage message = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("chat-test").stream(false).build());

            ToolUseBlock tool = message.content().stream()
                .filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
            assertEquals("ListMcpResources", tool.name());
            assertTrue(tool.input().isObject(), "a null arguments field must become {}, not a null node");
            assertTrue(tool.input().isEmpty());
        }
    }

    /**
     * The switch that maps finish reasons passes unrecognized values through, so an explicit
     * {@code "finish_reason": null} would otherwise make the stop reason the string "null".
     */
    @Test
    void keepsTheDefaultStopReasonWhenFinishReasonIsExplicitlyNull() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_1","model":"chat-test",
                     "choices":[{"message":{"content":"ok"},"finish_reason":null}],
                     "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":null}}
                    """).build());
            server.start();

            ApiMessage message = chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("chat-test").stream(false).build());

            assertFalse(Strings.CS.equals("null", message.stopReason()));
            assertEquals("stop", message.stopReason());
            assertNull(message.usage().reportedTotalTokens(),
                "an explicit null total must stay absent rather than report a boxed 0");
        }
    }

    @Test
    void emitsParallelToolCallsWithStableIdsAndToolUseStopReason() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"first","arguments":"{}"}},{"index":1,"id":"call_b","function":{"name":"second","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            var ids = events.stream()
                .filter(StreamEvent.ContentBlockStart.class::isInstance)
                .map(StreamEvent.ContentBlockStart.class::cast)
                .map(StreamEvent.ContentBlockStart::contentBlock)
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .map(ToolUseBlock::id)
                .toList();
            assertEquals(List.of("call_a", "call_b"), ids);
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.MessageDelta delta
                && Strings.CS.equals("tool_use", delta.delta().stopReason())));
        }
    }

    @Test
    void closesStreamingTextAndReasoningBlocksBeforeMessageStop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"id":"chat_1","model":"chat-test","choices":[{"delta":{"reasoning_content":"think"},"finish_reason":null}]}

                    data: {"id":"chat_1","model":"chat-test","choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            assertInstanceOf(StreamEvent.MessageStart.class, events.getFirst());
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.ThinkingDelta));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.TextDelta));
            assertEquals(2, events.stream().filter(StreamEvent.ContentBlockStop.class::isInstance).count());
            assertInstanceOf(StreamEvent.MessageStop.class, events.getLast());
        }
    }

    @Test
    void normalizesTrailingSlashAndAllowsHeaderOnlyAuthentication() throws Exception {
        try (MockWebServer server = chatServer()) {
            var config = new ApiConfig.OpenAiConfig(null, "chat-test", server.url("/v1/").toString(),
                ModelApiProtocol.OPENAI_CHAT, Map.of("X-API-Key", "gateway-key"));
            new OpenAiCompatClient(config, HttpClientFactory.shared()).createMessage(
                CreateMessageRequest.builder().model("chat-test").stream(false).build());

            var request = server.takeRequest();
            assertEquals("/v1/chat/completions", request.getUrl().encodedPath());
            assertNull(request.getHeaders().get("Authorization"));
            assertEquals("gateway-key", request.getHeaders().get("X-API-Key"));
        }
    }

    @Test
    void mapsChatGenerationToolChoiceAndStructuredOutputOptions() throws Exception {
        try (MockWebServer server = chatServer()) {
            var format = JsonUtils.getMapper().createObjectNode().put("type", "json_schema");
            format.put("name", "answer");
            format.putObject("schema").put("type", "object");

            chatClient(server).createMessage(CreateMessageRequest.builder()
                .model("chat-test")
                .stopSequences(List.of("STOP"))
                .toolChoice(new CreateMessageRequest.ToolChoice("any", null))
                .outputConfig(new CreateMessageRequest.OutputConfig("medium", format))
                .stream(false).build());

            var body = JsonUtils.parseTree(server.takeRequest().getBody().utf8());
            assertEquals("STOP", body.path("stop").get(0).asText());
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals("medium", body.path("reasoning_effort").asText());
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertEquals("answer", body.path("response_format").path("json_schema").path("name").asText());
        }
    }

    @Test
    void doesNotFinalizeStreamedToolCallWithoutFinishReason() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);

            assertFalse(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ToolUseBlock));
        }
    }

    @Test
    void surfacesMalformedStreamEventsAndToolArguments() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    data: not-json

                    data: [DONE]

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            chatClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("chat-test").stream(true).build()).forEachRemaining(events::add);
            assertTrue(events.stream().anyMatch(StreamEvent.Error.class::isInstance));
        }

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"chat_bad","model":"chat-test",
                     "choices":[{"message":{"content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{"}}
                     ]},"finish_reason":"tool_calls"}]}
                    """).build());
            server.start();

            assertThrows(ApiException.class, () -> chatClient(server).createMessage(
                CreateMessageRequest.builder().model("chat-test").stream(false).build()));
        }
    }

    private static OpenAiCompatClient chatClient(MockWebServer server) {
        var config = new ApiConfig.OpenAiConfig("key", "chat-test", server.url("/v1").toString(),
            ModelApiProtocol.OPENAI_CHAT, Map.of());
        return new OpenAiCompatClient(config, HttpClientFactory.shared());
    }

    private static MockWebServer chatServer() throws Exception {
        var server = new MockWebServer();
        server.enqueue(new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json")
            .body("{\"id\":\"chatcmpl_1\",\"model\":\"chat-test\","
                + "\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}")
            .build());
        server.start();
        return server;
    }
}
