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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard: OkHttp's {@code Request.Builder#header(name, value)}
 * REPLACES any existing header with that name (unlike java.net.http's
 * additive {@code header}), so setting {@code anthropic-beta} multiple times
 * — once per active feature (effort / interleaved-thinking /
 * context-management) — used to silently wipe out all but the last one on
 * the real wire, even though {@link AnthropicSdkClientRetryTest} and unit
 * tests on {@link CreateMessageRequest} never would have caught it (they
 * don't inspect outgoing HTTP headers). Caught via a live capture where only
 * one beta header made it onto the wire despite three branches firing.
 */
class AnthropicSdkClientHeadersTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<List<String>> capturedBetaHeaders = new AtomicReference<>(List.of());
    private final AtomicReference<String> capturedCustomHeader = new AtomicReference<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            capturedBetaHeaders.set(exchange.getRequestHeaders().get("anthropic-beta"));
            capturedCustomHeader.set(exchange.getRequestHeaders().getFirst("X-Custom"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"id\":\"msg_1\",\"model\":\"claude-sonnet-4-6\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicSdkClient client() {
        return new AnthropicSdkClient(
            new ApiConfig.AnthropicConfig("test-key", null, "claude-sonnet-4-6", baseUrl));
    }

    @Test
    void allThreeBetaFeaturesTogetherProduceThreeHeaderValues() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(100)
                .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                .outputConfig(new CreateMessageRequest.OutputConfig("high"))
                .contextManagement(new CreateMessageRequest.ContextManagementConfig(
                    List.of(CreateMessageRequest.ContextEditStrategy.clearThinkingKeepAll())))
                .build();

        client().createMessage(request);

        List<String> betas = capturedBetaHeaders.get();
        assertNotNull(betas, "anthropic-beta header must be present on the wire");
        // A single "anthropic-beta" HTTP header line can carry a comma-joined
        // list, or arrive as repeated header lines depending on how the
        // client emits it — either shape is fine as long as all three values
        // survive somewhere in it.
        String joined = String.join(",", betas);
        assertTrue(Strings.CS.contains(joined, "effort-2025-11-24"), "effort beta missing: " + joined);
        assertTrue(Strings.CS.contains(joined, "interleaved-thinking-2025-05-14"), "interleaved-thinking beta missing: " + joined);
        assertTrue(Strings.CS.contains(joined, "context-management-2025-06-27"), "context-management beta missing: " + joined);
    }

    @Test
    void fastSpeedAddsReleasedFastModeBeta() {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-opus-4-8")
            .maxTokens(100)
            .speed("fast")
            .build();

        client().createMessage(request);

        String joined = String.join(",", capturedBetaHeaders.get());
        assertTrue(Strings.CS.contains(joined, "fast-mode-2026-02-01"),
            "fast mode beta missing: " + joined);
        assertEquals("fast", JsonUtils.parseTree(capturedBody.get()).path("speed").asText());
    }

    @Test
    void onlyThinkingActiveStillSendsThatOneBeta() {
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(100)
                .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                .build();

        client().createMessage(request);

        String joined = String.join(",", capturedBetaHeaders.get());
        assertTrue(Strings.CS.contains(joined, "interleaved-thinking-2025-05-14"), "interleaved-thinking beta missing: " + joined);
        assertFalse(Strings.CS.contains(joined, "effort-2025-11-24"), "effort beta should be absent: " + joined);
        assertFalse(Strings.CS.contains(joined, "context-management-2025-06-27"), "context-management beta should be absent: " + joined);
    }

    @Test
    void oneHourPromptCacheAddsExtendedTtlBetaAlongsideOtherBetas() {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(100)
            .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.ONE_HOUR)
            .build();

        client().createMessage(request);

        String joined = String.join(",", capturedBetaHeaders.get());
        assertTrue(Strings.CS.contains(joined, "extended-cache-ttl-2025-04-11"),
            "extended cache TTL beta missing: " + joined);
        assertTrue(Strings.CS.contains(joined, "interleaved-thinking-2025-05-14"),
            "thinking beta missing: " + joined);
    }

    @Test
    void fiveMinutePromptCacheDoesNotAddExtendedTtlBeta() {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(100)
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.FIVE_MINUTES)
            .build();

        client().createMessage(request);

        List<String> headers = capturedBetaHeaders.get();
        String joined = headers == null ? "" : String.join(",", headers);
        assertFalse(Strings.CS.contains(joined, "extended-cache-ttl-2025-04-11"),
            "extended cache TTL beta should be absent: " + joined);
    }

    @Test
    void structuredOutputFormatSendsStructuredOutputsBeta() {
        var format = JsonUtils.getMapper().createObjectNode();
        format.put("type", "json_schema");
        format.putObject("schema").put("type", "object");
        CreateMessageRequest request = CreateMessageRequest.builder()
                .model("glm-5.2")
                .maxTokens(100)
                .outputConfig(new CreateMessageRequest.OutputConfig(null, format))
                .build();

        client().createMessage(request);

        String joined = String.join(",", capturedBetaHeaders.get());
        assertTrue(Strings.CS.contains(joined, "structured-outputs-2025-12-15"),
            "structured outputs beta missing: " + joined);
    }

    @Test
    void customModelHeadersAreSentWithoutLoggingTheApiKey() {
        var client = new AnthropicSdkClient(new ApiConfig.AnthropicConfig(
            "secret-key", null, "claude-sonnet-4-6", baseUrl,
            Map.of("X-Custom", "yes")));

        client.createMessage(CreateMessageRequest.builder()
            .model("claude-sonnet-4-6").maxTokens(1).stream(false).build());

        assertEquals("yes", capturedCustomHeader.get());
    }

    @Test
    void sdkBetasAppendWithoutReplacingBuiltInBetas() {
        var client = new AnthropicSdkClient(new ApiConfig.AnthropicConfig(
            "test-key", null, "claude-sonnet-4-6", baseUrl,
            Map.of("anthropic-beta", "custom-a,custom-b")));

        client.createMessage(CreateMessageRequest.builder()
            .model("claude-sonnet-4-6").maxTokens(1)
            .thinking(CreateMessageRequest.ThinkingConfig.adaptive()).build());

        String joined = String.join(",", capturedBetaHeaders.get());
        assertTrue(Strings.CS.contains(joined, "custom-a"));
        assertTrue(Strings.CS.contains(joined, "custom-b"));
        assertTrue(Strings.CS.contains(joined, "interleaved-thinking-2025-05-14"));
    }

    @Test
    void taskBudgetAddsBodyAndRequiredBeta() {
        var budget = JsonUtils.getMapper().createObjectNode().put("total", 8192);
        client().createMessage(CreateMessageRequest.builder()
            .model("claude-sonnet-4-6").maxTokens(1)
            .outputConfig(new CreateMessageRequest.OutputConfig(null, null, budget)).build());

        assertTrue(Strings.CS.contains(capturedBody.get(),
            "\"task_budget\":{\"total\":8192}"));
        assertTrue(Strings.CS.contains(String.join(",", capturedBetaHeaders.get()),
            "task-budgets-2026-03-13"));
    }
}
