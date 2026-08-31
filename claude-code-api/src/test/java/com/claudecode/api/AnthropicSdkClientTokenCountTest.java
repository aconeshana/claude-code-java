package com.claudecode.api;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


class AnthropicSdkClientTokenCountTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> beta = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages/count_tokens", exchange -> {
            path.set(exchange.getRequestURI().toString());
            beta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"input_tokens\":321}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/v1/messages", exchange -> {
            path.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"msg_count_fallback\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\"," +
                "\"content\":[{\"type\":\"text\",\"text\":\"\"}]," +
                "\"stop_reason\":\"end_turn\",\"usage\":{" +
                "\"input_tokens\":7,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void countTokensUsesBetaEndpointHeaderAndDummyMessageForEmptyMessages() throws Exception {
        AnthropicSdkClient client = new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig(
                "test-key", null, "claude-sonnet-4-6", baseUrl));
        var schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");

        long tokens = client.countTokens(
            "claude-sonnet-4-6",
            List.of(),
            List.of(new CreateMessageRequest.ToolDefinition("Bash", "Run commands", schema)));

        assertEquals(321, tokens);
        assertEquals("/v1/messages/count_tokens?beta=true", path.get());
        assertNotNull(beta.get());
        assertTrue(Strings.CS.contains(beta.get(), "token-counting-2024-11-01"));
        var request = JsonUtils.getMapper().readTree(body.get());
        assertEquals("claude-sonnet-4-6", request.path("model").asText());
        assertEquals("foo", request.path("messages").get(0).path("content").asText());
        assertEquals("https://json-schema.org/draft/2020-12/schema",
            request.path("tools").get(0).path("input_schema").path("$schema").asText());
        assertFalse(request.has("max_tokens"));
        assertFalse(request.has("stream"));

        long emptyToolTokens = client.countTokens(
            "claude-sonnet-4-6", List.of(), List.of());
        assertEquals(321, emptyToolTokens);
        var emptyToolRequest = JsonUtils.getMapper().readTree(body.get());
        assertEquals("foo", emptyToolRequest.path("messages").get(0).path("content").asText());
        assertEquals(0, emptyToolRequest.path("tools").size());
    }

    @Test
    void countTokensStripsInternalContextTagFromWireModel() throws Exception {
        AnthropicSdkClient client = new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig(
                "test-key", null, "claude-sonnet-5[1m]", baseUrl));

        client.countTokens("claude-sonnet-5[1m]", List.of(), List.of());

        assertEquals("claude-sonnet-5",
            JsonUtils.getMapper().readTree(body.get()).path("model").asText());
    }

    @Test
    void messageRequestFallsBackToNormalizedConfiguredModel() throws Exception {
        AnthropicSdkClient client = new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig(
                "test-key", null, "claude-sonnet-5[1m]", baseUrl));

        client.createMessage(CreateMessageRequest.builder()
            .maxTokens(1)
            .messages(List.of(
                new CreateMessageRequest.RequestMessage("user", "hello")))
            .stream(false)
            .build());

        assertEquals("claude-sonnet-5",
            JsonUtils.getMapper().readTree(body.get()).path("model").asText());
    }

    @Test
    void fallbackOmitsToolsWhenTheToolListIsEmpty() throws Exception {
        AnthropicSdkClient client = new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig(
                "test-key", null, "claude-sonnet-4-6", baseUrl));

        long tokens = client.countTokensFallback(
            "claude-sonnet-4-6[1m]",
            List.of(new CreateMessageRequest.RequestMessage("user", "count")),
            List.of(), "wire-session");

        assertEquals(7, tokens);
        assertEquals("/v1/messages", path.get());
        var request = JsonUtils.getMapper().readTree(body.get());
        assertEquals("claude-sonnet-4-6", request.path("model").asText());
        assertFalse(request.has("tools"));
        assertEquals(1, request.path("max_tokens").asInt());
        assertFalse(request.has("stream"));
    }
}
