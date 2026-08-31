package com.claudecode.api;

import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <ul>
 *   <li>OpenAI Responses API request, response, SSE, tool-call, and custom-header wire contract.</li>
 * </ul>
 */
class OpenAiResponsesClientTest {

    @Test
    void sendsResponsesRequestAndParsesNonStreamingOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_1","model":"gpt-test","status":"completed",
                     "output":[
                       {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]},
                       {"type":"function_call","call_id":"call_1","name":"Read","arguments":"{\\"file_path\\":\\"README.md\\"}"}
                     ],
                     "usage":{"input_tokens":12,"output_tokens":7}}
                    """).build());
            server.start();

            var config = new ApiConfig.OpenAiConfig("key", "gpt-test", server.url("/v1").toString(),
                ModelApiProtocol.OPENAI_RESPONSES, Map.of("X-Custom", "yes"));
            var client = new OpenAiResponsesClient(config, HttpClientFactory.shared());
            var request = CreateMessageRequest.builder()
                .model("gpt-test").systemPrompt("Be concise")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "Hi")))
                .tools(List.of(new CreateMessageRequest.ToolDefinition(
                    "Read", "Read a file", JsonUtils.parseTree("{\"type\":\"object\"}"))))
                .stream(false).build();

            ApiMessage message = client.createMessage(request);
            var recorded = server.takeRequest();
            assertEquals("/v1/responses", recorded.getUrl().encodedPath());
            assertEquals("Bearer key", recorded.getHeaders().get("Authorization"));
            assertEquals("yes", recorded.getHeaders().get("X-Custom"));
            var body = JsonUtils.parseTree(recorded.getBody().utf8());
            assertFalse(body.get("stream").asBoolean());
            assertEquals("Be concise", body.get("instructions").asText());
            assertEquals("function", body.get("tools").get(0).get("type").asText());
            assertInstanceOf(TextBlock.class, message.content().getFirst());
            assertInstanceOf(ToolUseBlock.class, message.content().get(1));
            assertEquals(12, message.usage().inputTokens());
            assertEquals(7, message.usage().outputTokens());
        }
    }

    @Test
    void stripsInternalContextTagFromResponsesWireModel() throws Exception {
        try (MockWebServer server = responseServer("resp_context_tag")) {
            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test[1m]").stream(false).build());

            assertEquals("gpt-test", recordedBody(server).path("model").asText());
        }
    }

    @Test
    void replaysAssistantHistoryAsOutputText() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_2","model":"gpt-test","status":"completed",
                     "output":[{"type":"message","role":"assistant",
                       "content":[{"type":"output_text","text":"next"}]}],
                     "usage":{"input_tokens":8,"output_tokens":1}}
                    """).build());
            server.start();

            var config = new ApiConfig.OpenAiConfig("key", "gpt-test", server.url("/v1").toString(),
                ModelApiProtocol.OPENAI_RESPONSES, Map.of());
            var client = new OpenAiResponsesClient(config, HttpClientFactory.shared());
            var request = CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "first"),
                    new CreateMessageRequest.RequestMessage("assistant", "previous answer"),
                    new CreateMessageRequest.RequestMessage("user", "continue")))
                .stream(false)
                .build();

            client.createMessage(request);

            var input = JsonUtils.parseTree(server.takeRequest().getBody().utf8()).path("input");
            assertEquals("input_text", input.get(0).path("content").get(0).path("type").asText());
            assertEquals("output_text", input.get(1).path("content").get(0).path("type").asText());
            assertEquals("input_text", input.get(2).path("content").get(0).path("type").asText());
        }
    }

    @Test
    void preparesFunctionCallAndFunctionOutputInputItems() throws Exception {
        try (MockWebServer server = responseServer("resp_tools")) {
            var client = responsesClient(server);
            var request = CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", "Use the tool"),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("call_1", "lookup", JsonUtils.parseTree("{\"query\":\"weather\"}")))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("call_1", List.of(new TextBlock("sunny")), false)))))
                .stream(false)
                .build();

            client.createMessage(request);

            var input = recordedBody(server).path("input");
            assertEquals("function_call", input.get(1).path("type").asText());
            assertEquals("call_1", input.get(1).path("call_id").asText());
            assertEquals("lookup", input.get(1).path("name").asText());
            assertEquals("function_call_output", input.get(2).path("type").asText());
            assertEquals("call_1", input.get(2).path("call_id").asText());
            assertEquals("sunny", input.get(2).path("output").asText());
        }
    }

    @Test
    void lowersUserAndToolResultImagesAsInputImageItems() throws Exception {
        try (MockWebServer server = responseServer("resp_images")) {
            var source = JsonUtils.getMapper().createObjectNode()
                .put("type", "base64")
                .put("media_type", "image/png")
                .put("data", "AAECAw==");
            var client = responsesClient(server);
            var request = CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new TextBlock("Inspect"), new ImageBlock(source))),
                    new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock("call_1", "screenshot", JsonUtils.parseTree("{}")))),
                    new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock("call_1", List.of(
                            new TextBlock("captured"), new ImageBlock(source)), false, false, true)))))
                .stream(false)
                .build();

            client.createMessage(request);

            var input = recordedBody(server).path("input");
            assertEquals("input_image", input.get(0).path("content").get(1).path("type").asText());
            assertEquals("data:image/png;base64,AAECAw==",
                input.get(0).path("content").get(1).path("image_url").asText());
            var output = input.get(2).path("output");
            assertTrue(output.isArray());
            assertEquals("input_text", output.get(0).path("type").asText());
            assertEquals("input_image", output.get(1).path("type").asText());
        }
    }

    @Test
    void rejectsUnsupportedResponsesToolResultContentBlocks() throws Exception {
        try (MockWebServer server = responseServer("resp_unsupported_tool_content")) {
            var document = new DocumentBlock(JsonUtils.getMapper().createObjectNode()
                .put("type", "text").put("data", "unsupported"));
            var request = CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", List.of(
                    new ToolResultBlock("call_1", List.of(document), false, false, true)))))
                .stream(false)
                .build();

            ApiException error = assertThrows(ApiException.class,
                () -> responsesClient(server).createMessage(request));

            assertEquals("OpenAI Responses tool output does not support DocumentBlock",
                error.getMessage());
        }
    }

    @Test
    void replaysSignedThinkingAsStatelessReasoningItem() throws Exception {
        try (MockWebServer server = responseServer("resp_reasoning")) {
            var client = responsesClient(server);
            client.createMessage(CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(new CreateMessageRequest.RequestMessage("assistant", List.of(
                    new ThinkingBlock("summary", "encrypted-state"),
                    new TextBlock("answer")))))
                .stream(false)
                .build());

            var input = recordedBody(server).path("input");
            assertEquals("reasoning", input.get(0).path("type").asText());
            assertEquals("summary", input.get(0).path("summary").get(0).path("text").asText());
            assertEquals("encrypted-state", input.get(0).path("encrypted_content").asText());
            assertEquals("output_text", input.get(1).path("content").get(0).path("type").asText());
        }
    }

    @Test
    void joinsConsecutiveReasoningSummaryBlocksForStatelessContinuation() throws Exception {
        try (MockWebServer server = responseServer("resp_reasoning_parts")) {
            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test")
                .messages(List.of(new CreateMessageRequest.RequestMessage("assistant", List.of(
                    new ThinkingBlock("First", null),
                    new ThinkingBlock("Second", "encrypted-state")))))
                .stream(false)
                .build());

            var reasoning = recordedBody(server).path("input").get(0);
            assertEquals("reasoning", reasoning.path("type").asText());
            assertEquals("First", reasoning.path("summary").get(0).path("text").asText());
            assertEquals("Second", reasoning.path("summary").get(1).path("text").asText());
            assertEquals("encrypted-state", reasoning.path("encrypted_content").asText());
        }
    }

    @Test
    void mapsRequiredToolChoiceReasoningEffortAndStructuredOutput() throws Exception {
        try (MockWebServer server = responseServer("resp_options")) {
            var format = JsonUtils.getMapper().createObjectNode().put("type", "json_schema");
            format.put("name", "answer");
            format.putObject("schema").put("type", "object");

            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test")
                .toolChoice(new CreateMessageRequest.ToolChoice("any", null))
                .outputConfig(new CreateMessageRequest.OutputConfig("high", format))
                .stream(false).build());

            var body = recordedBody(server);
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals("high", body.path("reasoning").path("effort").asText());
            assertEquals("auto", body.path("reasoning").path("summary").asText());
            assertEquals("reasoning.encrypted_content", body.path("include").get(0).asText());
            assertEquals("json_schema", body.path("text").path("format").path("type").asText());
            assertEquals("answer", body.path("text").path("format").path("name").asText());
            assertEquals("object", body.path("text").path("format").path("schema").path("type").asText());
        }
    }

    @Test
    void requestsReasoningSummaryWheneverThinkingIsEnabledWithoutExplicitEffort() throws Exception {
        try (MockWebServer server = responseServer("resp_thinking_summary")) {
            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test")
                .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                .stream(false)
                .build());

            var body = recordedBody(server);
            assertEquals("auto", body.path("reasoning").path("summary").asText());
            assertFalse(body.path("reasoning").has("effort"));
            assertEquals("reasoning.encrypted_content", body.path("include").get(0).asText());
        }
    }

    @Test
    void omitsReasoningSummaryAndEncryptedStateWhenThinkingIsDisabled() throws Exception {
        try (MockWebServer server = responseServer("resp_thinking_disabled")) {
            responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test")
                .thinking(CreateMessageRequest.ThinkingConfig.disabled())
                .stream(false)
                .build());

            var body = recordedBody(server);
            assertFalse(body.has("reasoning"));
            assertFalse(body.has("include"));
        }
    }

    @Test
    void translatesResponsesSseIntoUnifiedStreamEvents() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.created
                    data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hi"}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"Read","arguments":""}}

                    event: response.function_call_arguments.delta
                    data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{\\"file_path\\":\\"README.md\\"}"}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":3}}}

                    """).build());
            server.start();

            var config = new ApiConfig.OpenAiConfig("key", "gpt-test", server.url("/v1").toString(),
                ModelApiProtocol.OPENAI_RESPONSES, Map.of());
            var client = new OpenAiResponsesClient(config, HttpClientFactory.shared());
            var request = CreateMessageRequest.builder().model("gpt-test")
                .messages(List.of(new CreateMessageRequest.RequestMessage("user", "Hi"))).stream(true).build();

            List<StreamEvent> events = new ArrayList<>();
            client.createMessageStream(request).forEachRemaining(events::add);
            assertTrue(events.stream().anyMatch(StreamEvent.ContentBlockDelta.class::isInstance));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ToolUseBlock));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.InputJsonDelta input
                && Strings.CS.contains(input.partialJson(), "README.md")));
            assertInstanceOf(StreamEvent.MessageStop.class, events.getLast());
        }
    }

    @Test
    void parsesReasoningCachedUsageAndToolStopReason() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_reasoning","model":"gpt-test","status":"completed",
                     "output":[
                       {"type":"reasoning","summary":[{"type":"summary_text","text":"analysis"}],
                        "encrypted_content":"encrypted-state"},
                       {"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{}"}
                     ],
                     "usage":{"input_tokens":20,"output_tokens":8,
                       "input_tokens_details":{"cached_tokens":12},"total_tokens":29}}
                    """).build());
            server.start();

            ApiMessage message = responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test").stream(false).build());

            var reasoning = assertInstanceOf(ThinkingBlock.class, message.content().getFirst());
            assertEquals("analysis", reasoning.thinking());
            assertEquals("encrypted-state", reasoning.signature());
            assertEquals("tool_use", message.stopReason());
            assertEquals(12, message.usage().cacheReadInputTokens());
            assertEquals(8, message.usage().inputTokens());
            assertEquals(29, message.usage().reportedTotalTokens());
            assertEquals(29, TokenEstimator.contextTokens(
                message.usage(), "gpt-test"),
                "Codex prefers the provider-reported total_tokens snapshot");
        }
    }

    @Test
    void translatesReasoningSummaryAndEncryptedStateFromStream() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.created
                    data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}

                    event: response.reasoning_summary_text.delta
                    data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":0,"summary_index":0,"delta":"thinking"}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"thinking"}],"encrypted_content":"encrypted-state"}}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":3}}}

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            responsesClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("gpt-test").stream(true).build()).forEachRemaining(events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ThinkingBlock));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.ThinkingDelta thinking
                && Strings.CS.equals("thinking", thinking.thinking())));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.SignatureDelta signature
                && Strings.CS.equals("encrypted-state", signature.signature())));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.MessageDelta delta
                && delta.usage().equals(new Usage(5, 3, 0, 0))));
        }
    }

    @Test
    void streamsReasoningSummaryPartsAsSeparateContinuationBlocks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.created
                    data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","encrypted_content":null}}

                    event: response.reasoning_summary_part.added
                    data: {"type":"response.reasoning_summary_part.added","item_id":"rs_1","summary_index":0}

                    event: response.reasoning_summary_text.delta
                    data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":0,"delta":"First"}

                    event: response.reasoning_summary_part.done
                    data: {"type":"response.reasoning_summary_part.done","item_id":"rs_1","summary_index":0}

                    event: response.reasoning_summary_part.added
                    data: {"type":"response.reasoning_summary_part.added","item_id":"rs_1","summary_index":1}

                    event: response.reasoning_summary_text.delta
                    data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","summary_index":1,"delta":"Second"}

                    event: response.reasoning_summary_part.done
                    data: {"type":"response.reasoning_summary_part.done","item_id":"rs_1","summary_index":1}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","encrypted_content":"encrypted-state"}}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":3}}}

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            responsesClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("gpt-test").stream(true).build()).forEachRemaining(events::add);

            assertEquals(2, events.stream().filter(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ThinkingBlock).count());
            assertEquals(2, events.stream().filter(StreamEvent.ContentBlockStop.class::isInstance).count());
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockDelta delta
                && delta.delta() instanceof Delta.SignatureDelta signature
                && Strings.CS.equals("encrypted-state", signature.signature())));
        }
    }

    @Test
    void treatsIncompleteStreamAsCleanMaxTokenFinish() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.created
                    data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}

                    event: response.incomplete
                    data: {"type":"response.incomplete","response":{"id":"resp_1","model":"gpt-test","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":5,"output_tokens":9}}}

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            responsesClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("gpt-test").stream(true).build()).forEachRemaining(events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.MessageDelta delta
                && Strings.CS.equals("max_tokens", delta.delta().stopReason())
                && delta.usage().outputTokens() == 9));
            assertInstanceOf(StreamEvent.MessageStop.class, events.getLast());
        }
    }

    @Test
    void surfacesNestedResponsesStreamErrorMessage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.failed
                    data: {"type":"response.failed","response":{"error":{"code":"context_length_exceeded","message":"prompt too long"}}}

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            responsesClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("gpt-test").stream(true).build()).forEachRemaining(events::add);
            var error = events.stream().filter(StreamEvent.Error.class::isInstance)
                .map(StreamEvent.Error.class::cast).findFirst().orElseThrow();
            assertEquals("context_length_exceeded: prompt too long",
                error.exception().getMessage());
            assertEquals("context_length_exceeded", error.exception().errorType());
            assertFalse(Strings.CS.startsWith(
                error.exception().getMessage(), "Failed to parse event:"));
        }
    }

    @Test
    void decodesHostedToolsAsProviderExecutedCallAndResultBlocks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_hosted","model":"gpt-test","status":"completed",
                     "output":[
                       {"type":"web_search_call","id":"ws_1","status":"completed",
                        "action":{"type":"search","query":"effect 4"}},
                       {"type":"code_interpreter_call","id":"ci_1","status":"completed",
                        "code":"print(1+1)","container_id":"cnt_1",
                        "outputs":[{"type":"logs","logs":"2\\n"}]}
                     ],"usage":{"input_tokens":5,"output_tokens":1}}
                    """).build());
            server.start();

            ApiMessage message = responsesClient(server).createMessage(CreateMessageRequest.builder()
                .model("gpt-test").stream(false).build());

            var webCall = assertInstanceOf(ServerToolUseBlock.class, message.content().getFirst());
            assertEquals("web_search", webCall.name());
            assertEquals("effect 4", webCall.input().path("query").asText());
            var webResult = assertInstanceOf(ServerToolResultBlock.class, message.content().get(1));
            assertEquals("web_search_call", webResult.providerType());
            assertFalse(webResult.isError());
            var codeCall = assertInstanceOf(ServerToolUseBlock.class, message.content().get(2));
            assertEquals("print(1+1)", codeCall.input().path("code").asText());
            var codeResult = assertInstanceOf(ServerToolResultBlock.class, message.content().get(3));
            assertEquals("2\n", codeResult.content().path("outputs").get(0).path("logs").asText());
            assertEquals("end_turn", message.stopReason());
        }
    }

    @Test
    void streamsHostedToolAsCompleteProviderExecutedBlocks() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("""
                    event: response.created
                    data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-test"}}

                    event: response.output_item.added
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"web_search_call","id":"ws_1","status":"in_progress","action":{"type":"search","query":"effect 4"}}}

                    event: response.output_item.done
                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"effect 4"}}}

                    event: response.completed
                    data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-test","status":"completed","usage":{"input_tokens":5,"output_tokens":1}}}

                    """).build());
            server.start();

            List<StreamEvent> events = new ArrayList<>();
            responsesClient(server).createMessageStream(CreateMessageRequest.builder()
                .model("gpt-test").stream(true).build()).forEachRemaining(events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ServerToolUseBlock call
                && Strings.CS.equals("web_search", call.name())));
            assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ContentBlockStart start
                && start.contentBlock() instanceof ServerToolResultBlock result
                && Strings.CS.equals("web_search_call", result.providerType())));
        }
    }

    @Test
    void rejectsMalformedFunctionCallArguments() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                    {"id":"resp_bad","model":"gpt-test","status":"completed",
                     "output":[{"type":"function_call","call_id":"call_1","name":"lookup","arguments":"{"}],
                     "usage":{"input_tokens":1,"output_tokens":1}}
                    """).build());
            server.start();

            assertThrows(ApiException.class, () -> responsesClient(server).createMessage(
                CreateMessageRequest.builder().model("gpt-test").stream(false).build()));
        }
    }

    private static OpenAiResponsesClient responsesClient(MockWebServer server) {
        var config = new ApiConfig.OpenAiConfig("key", "gpt-test", server.url("/v1").toString(),
            ModelApiProtocol.OPENAI_RESPONSES, Map.of());
        return new OpenAiResponsesClient(config, HttpClientFactory.shared());
    }

    private static MockWebServer responseServer(String id) throws Exception {
        var server = new MockWebServer();
        server.enqueue(new MockResponse.Builder().code(200)
            .addHeader("Content-Type", "application/json")
            .body("{\"id\":\"" + id + "\",\"model\":\"gpt-test\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
            .build());
        server.start();
        return server;
    }

    private static JsonNode recordedBody(MockWebServer server) throws Exception {
        return JsonUtils.parseTree(server.takeRequest().getBody().utf8());
    }
}
