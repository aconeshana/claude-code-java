/*
 * Gemini upstream gateway integration suite ported from yetone/magpie
 * (internal/gateway/gemini_upstream_test.go & internal/provider/gemini_api_test.go).
 */
package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.api.ApiConfig;
import com.claudecode.api.GeminiClient;
import com.claudecode.api.LlmClient;
import com.claudecode.api.MockGeminiServer;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end HTTP-over-HTTP tests connecting downstream clients speaking all 4 protocols
 * ({@code Chat}, {@code Anthropic Messages}, {@code OpenAI Responses}, and {@code Google Gemini})
 * through {@link GatewayServer}'s LAN Model Proxy to a live upstream {@link MockGeminiServer}.
 */
class MagpieGeminiUpstreamGatewayTest {

    private static final String ADMIN_TOKEN = "g".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    @TempDir
    Path tempDir;

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    private MockGeminiServer mockGemini;
    private GatewayServer gateway;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(15))
        .build();

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.close();
        if (mockGemini != null) mockGemini.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    /** Ported from {@code gemini_upstream_test.go:TestCustomGeminiUpstream}. */
    @Test
    @Timeout(20)
    void customGeminiUpstreamUnderRelayPrefixServesAllFourDownstreamProtocolsAndToolRoundTrips()
            throws Exception {
        mockGemini = MockGeminiServer.builder()
            .pathPrefix("/proxy/v1beta")
            .expectedApiKey("AIza-upstream-secret")
            .start();

        GeminiClient realGeminiClient = new GeminiClient(
            new ApiConfig.OpenAiConfig(
                "AIza-upstream-secret", "gemini-3-flash", mockGemini.baseUrl()),
            http);

        LanGatewayConfigStore lanStore = new LanGatewayConfigStore(tempDir.resolve("gateway.json"));
        LanGatewayConfigStore.GatewayKey lanKey = lanStore.createKey("Ubuntu Workstation", List.of());
        lanStore.updateSettings(true, false, "", false, List.of());

        GatewayLanProxyPort proxyPort = new GatewayLanProxyPort() {
            @Override
            public List<SharedModel> listModels(boolean includeDefaultProvider) {
                return List.of(new SharedModel("gemini-3-flash", "gemini", false, 1_048_576L));
            }

            @Override
            public Optional<LlmClient> resolveClient(String modelName, boolean allowDefaultProvider) {
                return Strings.CS.equals("gemini-3-flash", modelName)
                    ? Optional.of(realGeminiClient) : Optional.empty();
            }

            @Override
            public Optional<ModelApiProtocol> upstreamProtocol(String modelName) {
                return Optional.of(ModelApiProtocol.GEMINI);
            }
        };

        gateway = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), ADMIN_TOKEN, registry)
            .lanGateway(lanStore, proxyPort)
            .build();
        gateway.start();
        String gw = "http://127.0.0.1:" + gateway.port();

        // 1. OpenAI Chat unary -> Gemini upstream (:generateContent)
        mockGemini.enqueueText("hello-", "from-gemini");
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1/chat/completions")
                .header("Authorization", "Bearer " + lanKey.secret())
                .post(RequestBody.create("""
                    {"model":"gemini-3-flash","messages":[{"role":"user","content":"hi there"}]}
                    """, JSON))
                .build()).execute()) {
            String respText = r.body().string();
            assertThat(r.code()).as("body=%s", respText).isEqualTo(200);
            JsonNode body = JsonUtils.parseTree(respText);
            assertThat(body.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("hello-from-gemini");
            assertThat(body.path("usage").path("prompt_tokens").asInt()).isEqualTo(7);
        }
        assertUpstreamCall(false, "hi there");

        // 2. OpenAI Chat stream -> Gemini upstream (:streamGenerateContent?alt=sse)
        mockGemini.enqueueText("hello-", "from-gemini");
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1/chat/completions")
                .header("Authorization", "Bearer " + lanKey.secret())
                .post(RequestBody.create("""
                    {"model":"gemini-3-flash","stream":true,"reasoning_effort":"medium",
                     "messages":[{"role":"user","content":"hi there"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            String sse = r.body().string();
            assertThat(sse).contains("\"content\":\"hello-\"")
                .contains("\"content\":\"from-gemini\"")
                .contains("[DONE]");
        }
        assertUpstreamCall(true, "hi there");
        assertThat(mockGemini.lastRequest().thinkingConfig().path("thinkingLevel").asText())
            .isEqualTo("medium");

        // 3. Anthropic Messages + MCP tool call + thoughtSignature round-trip -> Gemini upstream
        mockGemini.enqueue(MockGeminiServer.Reply.toolCall(
            "checking file", "sig_gem_99", "call_1", "Read",
            JsonUtils.getMapper().createObjectNode().put("file_path", "a.txt")));
        String signedToolId;
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1/messages")
                .header("x-api-key", lanKey.secret())
                .post(RequestBody.create("""
                    {"model":"gemini-3-flash","max_tokens":128,"stream":false,
                     "tools":[{"name":"Read","input_schema":{"type":"object","properties":{"file_path":{"type":"string"}},"additionalProperties":false}}],
                     "messages":[{"role":"user","content":"hi there"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            JsonNode body = JsonUtils.parseTree(r.body().string());
            assertThat(body.path("stop_reason").asText()).isEqualTo("tool_use");
            signedToolId = body.path("content").get(1).path("id").asText();
            assertThat(signedToolId).contains("__ts");
        }

        // Send tool_result back through Anthropic Messages -> verify MockGeminiServer receives restored thoughtSignature
        mockGemini.enqueueText("Read complete!");
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1/messages")
                .header("x-api-key", lanKey.secret())
                .post(RequestBody.create("""
                    {"model":"gemini-3-flash","max_tokens":128,"stream":false,
                     "messages":[
                       {"role":"user","content":"hi there"},
                       {"role":"assistant","content":[{"type":"tool_use","id":"%s","name":"Read","input":{"file_path":"a.txt"}}]},
                       {"role":"user","content":[{"type":"tool_result","tool_use_id":"%s","content":"contents-of-a"}]}
                     ]}
                    """.formatted(signedToolId, signedToolId), JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            assertThat(r.body().string()).contains("Read complete!");
        }
        assertThat(mockGemini.lastRequest().contents().get(1).path("parts").get(0)
            .path("thoughtSignature").asText()).isEqualTo("sig_gem_99");

        // 4. OpenAI Responses stream -> Gemini upstream
        mockGemini.enqueue(MockGeminiServer.Reply.thoughtAndText(
            "thinking-step", "sig_resp_1", 8, 4, "hello-responses"));
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1/responses")
                .header("Authorization", "Bearer " + lanKey.secret())
                .post(RequestBody.create("""
                    {"model":"gemini-3-flash","stream":true,"input":"hi there"}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            String sse = r.body().string();
            assertThat(sse).contains("hello-responses")
                .contains("event: response.completed");
        }

        // 5. Native Gemini client (:streamGenerateContent?alt=sse & :generateContent & :countTokens) -> Gemini upstream
        mockGemini.enqueueText("hello-", "from-gemini");
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1beta/models/gemini-3-flash:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", lanKey.secret())
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"hi there"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            String sse = r.body().string();
            assertThat(sse).contains("data:").contains("from-gemini");
        }
        assertUpstreamCall(true, "hi there");

        mockGemini.setTokenCount(77L);
        try (Response r = http.newCall(new Request.Builder()
                .url(gw + "/v1beta/models/gemini-3-flash:countTokens?key=" + lanKey.secret())
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"hi there"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            assertThat(JsonUtils.parseTree(r.body().string()).path("totalTokens").asLong())
                .isEqualTo(77L);
        }
    }

    private void assertUpstreamCall(boolean streamed, String expectedText) {
        MockGeminiServer.CapturedRequest last = mockGemini.lastRequest();
        String expectedUri = streamed
            ? "/proxy/v1beta/models/gemini-3-flash:streamGenerateContent?alt=sse"
            : "/proxy/v1beta/models/gemini-3-flash:generateContent";
        assertThat(last.requestUri()).isEqualTo(expectedUri);
        assertThat(last.googApiKey()).isEqualTo("AIza-upstream-secret");
        assertThat(last.authorization()).isNull();
        assertThat(last.body().has("model")).isFalse();
        assertThat(last.body().has("stream")).isFalse();
        assertThat(last.contents().toString()).contains(expectedText);
    }
}
