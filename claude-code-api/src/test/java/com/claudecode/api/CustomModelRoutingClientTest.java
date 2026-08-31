package com.claudecode.api;

import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Per-model protocol routing contract. */
class CustomModelRoutingClientTest {

    @Test
    void routesCustomModelsAndFallsBackForStandardModels() {
        RecordingClient fallback = new RecordingClient("fallback");
        AtomicReference<ModelApiProtocol> createdProtocol = new AtomicReference<>();
        var custom = new CustomModelConfig("gpt-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "key", Map.of());
        CustomModelRoutingClient router = new CustomModelRoutingClient(
            fallback,
            name -> Strings.CS.equals("gpt-custom", name) ? Optional.of(custom) : Optional.empty(),
            definition -> {
                createdProtocol.set(definition.protocol());
                return new RecordingClient(definition.modelName());
            });

        router.createMessage(CreateMessageRequest.builder().model("gpt-custom").stream(false).build());
        assertEquals(ModelApiProtocol.OPENAI_RESPONSES, createdProtocol.get());

        router.createMessage(CreateMessageRequest.builder().model("claude-sonnet").stream(false).build());
        assertEquals("claude-sonnet", fallback.lastModel.get());
    }

    @Test
    void contextTaggedModelStillRoutesToBaseCustomModel() {
        RecordingClient fallback = new RecordingClient("fallback");
        var custom = new CustomModelConfig("gpt-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "key", Map.of(), 1_000_000L);
        AtomicReference<String> routedModel = new AtomicReference<>();
        CustomModelRoutingClient router = new CustomModelRoutingClient(
            fallback,
            name -> Strings.CS.equals("gpt-custom", name) ? Optional.of(custom) : Optional.empty(),
            _ -> new RecordingClient("custom") {
                @Override public ApiMessage createMessage(CreateMessageRequest request) {
                    routedModel.set(request.model());
                    return super.createMessage(request);
                }
            });

        router.createMessage(CreateMessageRequest.builder()
            .model("gpt-custom[1m]").stream(false).build());

        assertEquals("gpt-custom[1m]", routedModel.get());
        assertNull(fallback.lastModel.get());
    }

    @Test
    void routesAllThreeProtocolsThroughTheirNativeWireEndpoints() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"a\"}],\"model\":\"claude-custom\","
                    + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .build());
            server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"id\":\"chat_1\",\"model\":\"chat-custom\",\"choices\":[{"
                    + "\"message\":{\"content\":\"b\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                .build());
            server.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"id\":\"resp_1\",\"model\":\"responses-custom\",\"status\":\"completed\","
                    + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{"
                    + "\"type\":\"output_text\",\"text\":\"c\"}]}],"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .build());
            server.start();

            String baseUrl = server.url("/v1").toString();
            Map<String, CustomModelConfig> models = Map.of(
                "claude-custom", new CustomModelConfig("claude-custom", ModelApiProtocol.ANTHROPIC,
                    baseUrl, null, Map.of("X-API-Key", "anthropic-header-only")),
                "chat-custom", new CustomModelConfig("chat-custom", ModelApiProtocol.OPENAI_CHAT,
                    baseUrl, "chat-key", Map.of()),
                "responses-custom", new CustomModelConfig("responses-custom", ModelApiProtocol.OPENAI_RESPONSES,
                    baseUrl, "responses-key", Map.of()));
            var router = CustomModelRoutingClient.standard(new RecordingClient("fallback"),
                name -> Optional.ofNullable(models.get(name)));

            for (String model : List.of("claude-custom", "chat-custom", "responses-custom")) {
                router.createMessage(CreateMessageRequest.builder().model(model).stream(false).build());
            }

            var anthropic = server.takeRequest();
            assertEquals("/v1/messages", anthropic.getUrl().encodedPath());
            assertEquals("anthropic-header-only", anthropic.getHeaders().get("X-API-Key"));
            assertNull(anthropic.getHeaders().get("Authorization"));
            var chat = server.takeRequest();
            assertEquals("/v1/chat/completions", chat.getUrl().encodedPath());
            assertEquals("Bearer chat-key", chat.getHeaders().get("Authorization"));
            var responses = server.takeRequest();
            assertEquals("/v1/responses", responses.getUrl().encodedPath());
            assertEquals("Bearer responses-key", responses.getHeaders().get("Authorization"));
        }
    }

    @Test
    void retriesOnceWithoutEffortAndRemembersEndpointCapability() {
        var custom = new CustomModelConfig("gateway-alias", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "key", Map.of());
        var endpoint = new EffortRejectingClient();
        var router = new CustomModelRoutingClient(
            new RecordingClient("fallback"), _ -> Optional.of(custom), _ -> endpoint);
        var format = JsonUtils.getMapper()
            .createObjectNode().put("type", "json_schema");

        router.createMessage(CreateMessageRequest.builder()
            .model("gateway-alias")
            .effort("high")
            .outputConfig(new CreateMessageRequest.OutputConfig("high", format))
            .stream(false)
            .build());

        assertEquals(2, endpoint.calls.get());
        assertEquals("high", endpoint.requests.getFirst().outputConfig().effort());
        assertNull(endpoint.requests.get(1).outputConfig().effort());
        assertEquals("json_schema", endpoint.requests.get(1).outputConfig().format().path("type").asText());

        router.createMessage(CreateMessageRequest.builder()
            .model("gateway-alias").effort("medium").stream(false).build());
        assertEquals(3, endpoint.calls.get());
        assertNull(endpoint.requests.get(2).effort());
    }

    @Test
    void streamingRequestRetriesOnceWithoutEffort() {
        var custom = new CustomModelConfig("gateway-stream", ModelApiProtocol.OPENAI_CHAT,
            "https://example.test/v1", "key", Map.of());
        var endpoint = new StreamingEffortRejectingClient();
        var router = new CustomModelRoutingClient(
            new RecordingClient("fallback"), _ -> Optional.of(custom), _ -> endpoint);

        Iterator<StreamEvent> events = router.createMessageStream(CreateMessageRequest.builder()
            .model("gateway-stream").effort("high").stream(true).build());

        assertEquals(2, endpoint.calls.get());
        assertEquals("high", endpoint.requests.getFirst().effort());
        assertNull(endpoint.requests.get(1).effort());
        assertEquals(StreamEvent.MessageStop.class, events.next().getClass());
    }

    @Test
    void doesNotRetryUnrelatedClientErrors() {
        var custom = new CustomModelConfig("gateway-alias", ModelApiProtocol.OPENAI_CHAT,
            "https://example.test/v1", "key", Map.of());
        var endpoint = new RecordingClient("gateway-alias") {
            @Override public ApiMessage createMessage(CreateMessageRequest request) {
                throw new ApiException("invalid tool schema", 400);
            }
        };
        var router = new CustomModelRoutingClient(
            new RecordingClient("fallback"), _ -> Optional.of(custom), _ -> endpoint);

        assertThrows(ApiException.class, () -> router.createMessage(
            CreateMessageRequest.builder().model("gateway-alias").effort("high").build()));
    }

    @Test
    void retriesEffortRejectionAcrossAllCustomProtocols() throws Exception {
        for (ModelApiProtocol protocol : ModelApiProtocol.values()) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse.Builder().code(400)
                    .addHeader("Content-Type", "application/json")
                    .body(effortError(protocol)).build());
                server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(successResponse(protocol)).build());
                server.start();

                var custom = new CustomModelConfig("custom-" + protocol.configValue(), protocol,
                    server.url("/v1").toString(), "key", Map.of());
                var router = CustomModelRoutingClient.standard(new RecordingClient("fallback"),
                    _ -> Optional.of(custom));
                router.createMessage(CreateMessageRequest.builder()
                    .model(custom.modelName())
                    .outputConfig(new CreateMessageRequest.OutputConfig("high"))
                    .stream(false)
                    .build());

                var first = JsonUtils.parseTree(
                    server.takeRequest().getBody().utf8());
                var second = JsonUtils.parseTree(
                    server.takeRequest().getBody().utf8());
                switch (protocol) {
                    case ANTHROPIC -> {
                        assertEquals("high", first.path("output_config").path("effort").asText());
                        assertFalse(second.path("output_config").has("effort"));
                    }
                    case OPENAI_CHAT -> {
                        assertEquals("high", first.path("reasoning_effort").asText());
                        assertFalse(second.has("reasoning_effort"));
                    }
                    case OPENAI_RESPONSES -> {
                        assertEquals("high", first.path("reasoning").path("effort").asText());
                        assertFalse(second.path("reasoning").has("effort"));
                    }
                }
            }
        }
    }

    @Test
    void streamingWireRetriesEffortRejectionAcrossAllCustomProtocols() throws Exception {
        for (ModelApiProtocol protocol : ModelApiProtocol.values()) {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(new MockResponse.Builder().code(400)
                    .addHeader("Content-Type", "application/json")
                    .body(effortError(protocol)).build());
                server.enqueue(new MockResponse.Builder().code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body("data: [DONE]\n\n").build());
                server.start();

                var custom = new CustomModelConfig("stream-" + protocol.configValue(), protocol,
                    server.url("/v1").toString(), "key", Map.of());
                var router = CustomModelRoutingClient.standard(new RecordingClient("fallback"),
                    _ -> Optional.of(custom));
                router.createMessageStream(CreateMessageRequest.builder()
                    .model(custom.modelName())
                    .outputConfig(new CreateMessageRequest.OutputConfig("high"))
                    .stream(true)
                    .build());

                var first = JsonUtils.parseTree(
                    server.takeRequest().getBody().utf8());
                var second = JsonUtils.parseTree(
                    server.takeRequest().getBody().utf8());
                switch (protocol) {
                    case ANTHROPIC -> assertFalse(second.path("output_config").has("effort"));
                    case OPENAI_CHAT -> assertFalse(second.has("reasoning_effort"));
                    case OPENAI_RESPONSES -> assertFalse(second.path("reasoning").has("effort"));
                }
                assertFalse(first.isEmpty());
            }
        }
    }

    private static String effortError(ModelApiProtocol protocol) {
        String parameter = switch (protocol) {
            case ANTHROPIC -> "output_config.effort";
            case OPENAI_CHAT -> "reasoning_effort";
            case OPENAI_RESPONSES -> "reasoning.effort";
        };
        return "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid "
            + parameter + " value\"}}";
    }

    private static String successResponse(ModelApiProtocol protocol) {
        return switch (protocol) {
            case ANTHROPIC -> "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"model\":\"custom\","
                + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
            case OPENAI_CHAT -> "{\"id\":\"chat_1\",\"model\":\"custom\",\"choices\":[{"
                + "\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
            case OPENAI_RESPONSES -> "{\"id\":\"resp_1\",\"model\":\"custom\","
                + "\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        };
    }

    private static class RecordingClient implements LlmClient {
        private final String model;
        private final AtomicReference<String> lastModel = new AtomicReference<>();

        private RecordingClient(String model) { this.model = model; }
        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            lastModel.set(request.model());
            return List.<StreamEvent>of(new StreamEvent.MessageStop()).iterator();
        }
        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            lastModel.set(request.model());
            return ApiMessage.stub(request.model(), "ok");
        }
        @Override public String getModel() { return model; }
    }

    private static final class EffortRejectingClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<CreateMessageRequest> requests = new ArrayList<>();

        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            return List.<StreamEvent>of(new StreamEvent.MessageStop()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            requests.add(request);
            if (calls.getAndIncrement() == 0) {
                throw new ApiException(
                    "Invalid value for reasoning.effort: model does not support this value", 400);
            }
            return ApiMessage.stub(request.model(), "ok");
        }

        @Override public String getModel() { return "gateway-alias"; }
    }

    private static final class StreamingEffortRejectingClient implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<CreateMessageRequest> requests = new ArrayList<>();

        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            requests.add(request);
            if (calls.getAndIncrement() == 0) {
                throw new ApiException("Unsupported reasoning_effort parameter", 400);
            }
            return List.<StreamEvent>of(new StreamEvent.MessageStop()).iterator();
        }

        @Override public ApiMessage createMessage(CreateMessageRequest request) {
            return ApiMessage.stub(request.model(), "ok");
        }

        @Override public String getModel() { return "gateway-stream"; }
    }
}
