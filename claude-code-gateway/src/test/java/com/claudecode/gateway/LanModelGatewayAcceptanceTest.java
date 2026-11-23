/*
 * LAN Model Gateway acceptance suite inspired by yetone/magpie (internal/gateway/lan_test.go & gateway_test.go).
 */
package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.CustomModelRoutingClient;
import com.claudecode.api.Delta;
import com.claudecode.api.LlmClient;
import com.claudecode.api.MessageDeltaData;
import com.claudecode.api.MockGeminiServer;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
 * End-to-end acceptance tests for the single-port LAN Model Gateway:
 * <ul>
 *   <li>Smart LAN / Bonjour / Tailscale address classification & Clash Fake-IP TUN filtering</li>
 *   <li>Live listener hot-rebinding ({@code 127.0.0.1} ↔ {@code 0.0.0.0}) on the same port</li>
 *   <li>Strict plane isolation (a {@code sk-poco-...} key or forwarded LAN peer can never touch
 *       {@code /api/*} or drive the host's local {@code SessionHost} tools)</li>
 *   <li>Stateless {@code 4 × 4} unary & incremental SSE proxy across Anthropic, Chat, Responses, and Gemini</li>
 *   <li>Multi-header key extraction, per-key model allowlists, Claude tier stand-in, and loop guard</li>
 * </ul>
 */
class LanModelGatewayAcceptanceTest {

    private static final String ADMIN_TOKEN = "a".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    @TempDir
    Path tempDir;

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
    private final AtomicInteger hostSessionSubmits = new AtomicInteger();
    private final List<CreateMessageRequest> proxiedUpstreamRequests = new CopyOnWriteArrayList<>();

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

    private GatewayServer server;
    private LanGatewayConfigStore lanStore;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(15))
        .build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    void smartAddressDetectionFiltersFakeIpAndOrdersLanMdnsTailscale() throws Exception {
        List<InetAddress> addrs = List.of(
            InetAddress.getByName("127.0.0.1"),
            InetAddress.getByName("169.254.10.20"),
            InetAddress.getByName("198.18.0.1"),
            InetAddress.getByName("198.19.255.2"),
            InetAddress.getByName("8.8.4.4"),
            InetAddress.getByName("100.101.102.103"),
            InetAddress.getByName("192.168.31.50")
        );

        List<LanAddressDetector.DetectedEndpoint> endpoints =
            LanAddressDetector.classifyAddresses(addrs, "MacBook-Pro.local.", true, 19845);

        assertThat(endpoints).extracting(LanAddressDetector.DetectedEndpoint::kind)
            .containsExactly("lan", "mdns", "tailscale", "public");
        assertThat(endpoints).extracting(LanAddressDetector.DetectedEndpoint::url)
            .containsExactly(
                "http://192.168.31.50:19845",
                "http://macbook-pro.local:19845",
                "http://100.101.102.103:19845",
                "http://8.8.4.4:19845");
    }

    @Test
    @Timeout(20)
    void adminLifecycleHotRebindsListenerMintsDefaultKeyAndPersistsConfig() throws Exception {
        startGateway();
        assertThat(server.boundHost()).isEqualTo("127.0.0.1");

        // Enable LAN sharing via Web UI admin endpoint -> hot rebinds to 0.0.0.0:<samePort>
        String lanKey;
        try (Response enableResp = adminPost("/api/lan-gateway",
                "{\"enabled\":true,\"tier_stand_in_model\":\"deepseek-chat\"}")) {
            assertThat(enableResp.code()).isEqualTo(200);
            JsonNode snap = JsonUtils.parseTree(enableResp.body().string());
            assertThat(snap.path("enabled").asBoolean()).isTrue();
            assertThat(snap.path("listening_host").asText()).isEqualTo("0.0.0.0");
            assertThat(snap.path("tier_stand_in_model").asText()).isEqualTo("deepseek-chat");
            assertThat(snap.path("keys")).hasSize(1);
            lanKey = snap.path("keys").get(0).path("secret").asText();
            assertThat(lanKey).startsWith("sk-poco-");
        }
        assertThat(server.boundHost()).isEqualTo("0.0.0.0");
        assertThat(Files.exists(lanStore.file())).isTrue();

        // Immediately call the rebound listener with the newly minted LAN key
        try (Response probe = client.newCall(new Request.Builder()
                .url(baseUrl("/v1"))
                .header("Authorization", "Bearer " + lanKey)
                .get()
                .build()).execute()) {
            assertThat(probe.code()).isEqualTo(200);
            assertThat(JsonUtils.parseTree(probe.body().string()).path("status").asText())
                .isEqualTo("ok");
        }

        // Toggle off -> hot rebinds back to 127.0.0.1:<samePort> without breaking Web UI
        try (Response disableResp = adminPost("/api/lan-gateway", "{\"enabled\":false}")) {
            assertThat(disableResp.code()).isEqualTo(200);
            JsonNode snap = JsonUtils.parseTree(disableResp.body().string());
            assertThat(snap.path("enabled").asBoolean()).isFalse();
            assertThat(snap.path("listening_host").asText()).isEqualTo("127.0.0.1");
        }
        assertThat(server.boundHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @Timeout(20)
    void strictPlaneIsolationBlocksLanKeysAndForwardedCallersFromHostSessionAndAdminApis() throws Exception {
        startGateway();
        LanGatewayConfigStore.GatewayKey key = lanStore.createKey("Windows Workstation", List.of());
        lanStore.updateSettings(true, false, "", false, List.of());

        // 1. A valid sk-poco-... key is strictly forbidden from touching /api/* or /webui/*
        for (String adminPath : List.of("/api/sessions", "/api/models", "/api/lan-gateway", "/webui/index.html")) {
            try (Response resp = client.newCall(new Request.Builder()
                    .url(baseUrl(adminPath))
                    .header("Authorization", "Bearer " + key.secret())
                    .get()
                    .build()).execute()) {
                assertThat(resp.code()).as("path=%s", adminPath).isEqualTo(403);
            }
        }

        // 2. A proxied/remote caller (X-Forwarded-For) cannot use the host Launch Token to access /api/*
        try (Response proxiedAdmin = client.newCall(new Request.Builder()
                .url(baseUrl("/api/sessions"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .header("X-Forwarded-For", "192.168.1.99")
                .get()
                .build()).execute()) {
            assertThat(proxiedAdmin.code()).isEqualTo(403);
        }

        // 3. DNS rebinding guard blocks unauthenticated browser requests with foreign Host header
        try (Response rebound = client.newCall(new Request.Builder()
                .url(baseUrl("/"))
                .header("Host", "evil-rebinding.example.com")
                .header("Origin", "http://evil-rebinding.example.com")
                .get()
                .build()).execute()) {
            assertThat(rebound.code()).isEqualTo(403);
        }

        // 4. POST /v1/messages with sk-poco-... hits ONLY the stateless LLM proxy, NEVER SessionHost!
        try (Response proxyTurn = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/messages"))
                .header("x-api-key", key.secret())
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","max_tokens":128,"stream":false,
                     "messages":[{"role":"user","content":"hello from windows"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(proxyTurn.code()).isEqualTo(200);
            assertThat(hostSessionSubmits.get()).isZero();
            assertThat(proxiedUpstreamRequests).hasSize(1);
        }

        // 5. Meanwhile, loopback Web UI with Launch Token still drives SessionHost as expected
        try (Response localSessionTurn = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/messages"))
                .header("Authorization", "Bearer " + ADMIN_TOKEN)
                .post(RequestBody.create("""
                    {"model":"default","stream":false,
                     "messages":[{"role":"user","content":"hello local tui"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(localSessionTurn.code()).isEqualTo(200);
            assertThat(hostSessionSubmits.get()).isEqualTo(1);
        }
    }

    @Test
    @Timeout(20)
    void fourProtocolStatelessProxyTranslatesUnaryAndStreamingAcrossAnthropicChatResponsesAndGemini()
            throws Exception {
        startGateway();
        LanGatewayConfigStore.GatewayKey key = lanStore.createKey("Linux Devbox", List.of());
        lanStore.updateSettings(true, false, "", false, List.of());

        // 1. Anthropic client sending placeholder Bearer + real x-api-key (magpie callerKeys case)
        try (Response r = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/messages"))
                .header("Authorization", "Bearer placeholder-not-a-key")
                .header("x-api-key", key.secret())
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","max_tokens":256,"stream":false,
                     "tools":[{"name":"Read","description":"Read file","input_schema":{"type":"object"}}],
                     "messages":[{"role":"user","content":"read README"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            JsonNode body = JsonUtils.parseTree(r.body().string());
            assertThat(body.path("type").asText()).isEqualTo("message");
            assertThat(body.path("stop_reason").asText()).isEqualTo("tool_use");
            assertThat(proxiedUpstreamRequests.getLast().tools()).hasSize(1);
        }

        // 2. OpenAI Chat Completions streaming (bare /chat/completions alias)
        try (Response r = client.newCall(new Request.Builder()
                .url(baseUrl("/chat/completions"))
                .header("Authorization", "Bearer " + key.secret())
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","stream":true,
                     "messages":[{"role":"user","content":"stream chat"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            String sse = r.body().string();
            assertThat(sse).contains("\"object\":\"chat.completion.chunk\"")
                .contains("\"reasoning_content\":\"step-1\"")
                .contains("\"content\":\"Hello LAN\"")
                .contains("\"tool_calls\"")
                .contains("data: [DONE]");
        }

        // 3. OpenAI Responses / Codex streaming (/v1/responses) with ClaudeSeal encrypted_content
        try (Response r = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/responses"))
                .header("Authorization", "Bearer " + key.secret())
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","stream":true,"input":"stream responses"}
                    """, JSON))
                .build()).execute()) {
            assertThat(r.code()).isEqualTo(200);
            String sse = r.body().string();
            assertThat(sse).contains("event: response.created")
                .contains("event: response.output_text.delta")
                .contains("event: response.function_call_arguments.delta")
                .contains("magpie-claude1:")
                .contains("event: response.completed");
        }

        // 4. Google Gemini unary, ?alt=sse streaming, JSON-array streaming, and :countTokens
        try (Response unaryGem = client.newCall(new Request.Builder()
                .url(baseUrl("/v1beta/models/deepseek-chat:generateContent?key=" + key.secret()))
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"hi gemini"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(unaryGem.code()).isEqualTo(200);
            JsonNode gem = JsonUtils.parseTree(unaryGem.body().string());
            assertThat(gem.path("candidates").get(0).path("content").path("parts").toString())
                .contains("Hello LAN")
                .contains("functionCall");
        }

        try (Response sseGem = client.newCall(new Request.Builder()
                .url(baseUrl("/v1beta/models/deepseek-chat:streamGenerateContent?alt=sse"))
                .header("x-goog-api-key", key.secret())
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"stream gemini"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(sseGem.code()).isEqualTo(200);
            String raw = sseGem.body().string();
            assertThat(raw).contains("data: {")
                .contains("\"thought\":true")
                .contains("\"usageMetadata\"");
        }

        try (Response jsonArrayGem = client.newCall(new Request.Builder()
                .url(baseUrl("/v1beta/models/deepseek-chat:streamGenerateContent"))
                .header("x-goog-api-key", key.secret())
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"array gemini"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(jsonArrayGem.code()).isEqualTo(200);
            JsonNode arr = JsonUtils.parseTree(jsonArrayGem.body().string());
            assertThat(arr.isArray()).isTrue();
            assertThat(arr).hasSizeGreaterThanOrEqualTo(2);
        }

        try (Response countGem = client.newCall(new Request.Builder()
                .url(baseUrl("/v1beta/models/deepseek-chat:countTokens"))
                .header("x-goog-api-key", key.secret())
                .post(RequestBody.create("""
                    {"contents":[{"role":"user","parts":[{"text":"count me"}]}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(countGem.code()).isEqualTo(200);
            assertThat(JsonUtils.parseTree(countGem.body().string()).path("totalTokens").asLong())
                .isEqualTo(42L);
        }

        // 5. Verify recent calls ring buffer and cumulative key usage via admin API
        try (Response callsResp = adminGet("/api/lan-gateway/calls")) {
            assertThat(callsResp.code()).isEqualTo(200);
            JsonNode calls = JsonUtils.parseTree(callsResp.body().string()).path("calls");
            assertThat(calls).hasSize(6);
            assertThat(calls.get(0).path("key_name").asText()).isEqualTo("Linux Devbox");
        }
    }

    @Test
    @Timeout(20)
    void modelDiscoveryPerKeyAllowlistTierStandInAndLoopGuardWorkEndToEnd() throws Exception {
        startGateway();
        LanGatewayConfigStore.GatewayKey restrictedKey =
            lanStore.createKey("Restricted Client", List.of("qwen-max"));
        LanGatewayConfigStore.GatewayKey fullKey =
            lanStore.createKey("Full Client", List.of());
        lanStore.updateSettings(true, false, "deepseek-chat", false, List.of());

        // Restricted key only sees qwen-max in /v1/models and /v1beta/models
        try (Response oaiModels = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/models"))
                .header("Authorization", "Bearer " + restrictedKey.secret())
                .get()
                .build()).execute()) {
            assertThat(oaiModels.code()).isEqualTo(200);
            JsonNode data = JsonUtils.parseTree(oaiModels.body().string()).path("data");
            assertThat(data).hasSize(1);
            assertThat(data.get(0).path("id").asText()).isEqualTo("qwen-max");
        }

        try (Response gemModels = client.newCall(new Request.Builder()
                .url(baseUrl("/v1beta/models?key=" + restrictedKey.secret()))
                .get()
                .build()).execute()) {
            assertThat(gemModels.code()).isEqualTo(200);
            JsonNode models = JsonUtils.parseTree(gemModels.body().string()).path("models");
            assertThat(models).hasSize(1);
            assertThat(models.get(0).path("name").asText()).isEqualTo("models/qwen-max");
        }

        // Restricted key calling deepseek-chat is rejected 403
        try (Response denied = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/chat/completions"))
                .header("Authorization", "Bearer " + restrictedKey.secret())
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(denied.code()).isEqualTo(403);
        }

        // Claude family tier stand-in maps unserved claude-haiku-4-5-20251001 -> deepseek-chat
        try (Response standInResp = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/messages"))
                .header("x-api-key", fullKey.secret())
                .post(RequestBody.create("""
                    {"model":"claude-haiku-4-5-20251001","max_tokens":64,"stream":false,
                     "messages":[{"role":"user","content":"summarize title"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(standInResp.code()).isEqualTo(200);
            assertThat(proxiedUpstreamRequests.getLast().model()).isEqualTo("deepseek-chat");
        }

        // Self-loop guard rejects inbound request carrying our own X-Poco-Gateway-Hop
        String capturedHopId = StubProxyLlmClient.lastObservedHopId;
        assertThat(capturedHopId).isNotBlank();
        try (Response loopResp = client.newCall(new Request.Builder()
                .url(baseUrl("/v1/chat/completions"))
                .header("Authorization", "Bearer " + fullKey.secret())
                .header(GatewayLanProxyPort.LOOP_GUARD_HEADER, capturedHopId)
                .post(RequestBody.create("""
                    {"model":"deepseek-chat","messages":[{"role":"user","content":"loop"}]}
                    """, JSON))
                .build()).execute()) {
            assertThat(loopResp.code()).isEqualTo(508);
        }
    }

    /**
     * Deterministic CI mirror of the live multi-JCC scenario matrix:
     * <ul>
     *   <li>{@code 4 × 4 = 16} real {@link CustomModelRoutingClient} HTTP+SSE chains across
     *       {@code Anthropic}, {@code OpenAI Chat}, {@code OpenAI Responses}, and {@code Gemini}</li>
     *   <li>Multi-turn Function Call + multimodal {@link ToolResultBlock} ({@code ImageBlock} PNG)</li>
     *   <li>All 4 thinking effort levels ({@code low}, {@code medium}, {@code high}, {@code max})
     *       plus {@code adaptive} null-budget thinking</li>
     *   <li>Direct first-turn user {@link ImageBlock} vision across all 4 upstream protocols</li>
     * </ul>
     */
    @Test
    @Timeout(30)
    void fullFourByFourRealLlmClientsCoverMultiToolLoopsAllThinkingEffortsAndMultimodalVision()
            throws Exception {
        String pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        List<JsonNode> anthropicBodies = new CopyOnWriteArrayList<>();
        List<JsonNode> chatBodies = new CopyOnWriteArrayList<>();
        List<JsonNode> responsesBodies = new CopyOnWriteArrayList<>();

        HttpServer mockUpstreams = startMultiProtocolUpstreamServer(
            anthropicBodies, chatBodies, responsesBodies);
        try (MockGeminiServer mockGemini = MockGeminiServer.start()) {
            String upBase = "http://127.0.0.1:" + mockUpstreams.getAddress().getPort();
            Map<String, CustomModelConfig> upstreamCatalog = Map.of(
                "up-anthropic", new CustomModelConfig("up-anthropic", ModelApiProtocol.ANTHROPIC,
                    upBase, "sk-up-anth", Map.of(), 128_000L, true),
                "up-chat", new CustomModelConfig("up-chat", ModelApiProtocol.OPENAI_CHAT,
                    upBase + "/v1", "sk-up-chat", Map.of(), 128_000L, true),
                "up-responses", new CustomModelConfig("up-responses", ModelApiProtocol.OPENAI_RESPONSES,
                    upBase + "/v1", "sk-up-resp", Map.of(), 128_000L, true),
                "up-gemini", new CustomModelConfig("up-gemini", ModelApiProtocol.GEMINI,
                    mockGemini.baseUrl(), "AIza-up-gem", Map.of(), 1_048_576L, true)
            );
            LlmClient gatewayUpstreamRouter = CustomModelRoutingClient.standard(
                new StubProxyLlmClient(proxiedUpstreamRequests),
                name -> Optional.ofNullable(upstreamCatalog.get(name)),
                () -> null);

            lanStore = new LanGatewayConfigStore(tempDir.resolve("matrix-gateway.json"));
            LanGatewayConfigStore.GatewayKey lanKey = lanStore.createKey("Matrix JCC", List.of());
            lanStore.updateSettings(true, false, "", false, List.of());

            GatewayLanProxyPort proxyPort = new GatewayLanProxyPort() {
                @Override
                public List<SharedModel> listModels(boolean includeDefaultProvider) {
                    return upstreamCatalog.values().stream()
                        .map(c -> new SharedModel(c.modelName(), c.protocol().configValue(), false, 128_000L))
                        .toList();
                }

                @Override
                public Optional<LlmClient> resolveClient(String modelName, boolean allowDefaultProvider) {
                    return upstreamCatalog.containsKey(modelName)
                        ? Optional.of(gatewayUpstreamRouter) : Optional.empty();
                }

                @Override
                public Optional<ModelApiProtocol> upstreamProtocol(String modelName) {
                    return Optional.ofNullable(upstreamCatalog.get(modelName)).map(CustomModelConfig::protocol);
                }
            };

            server = GatewayServer.builder(
                    new GatewayServer.Config("127.0.0.1", 0), ADMIN_TOKEN, registry)
                .lanGateway(lanStore, proxyPort)
                .build();
            server.start();
            String gwHost = "http://127.0.0.1:" + server.port();

            List<ModelApiProtocol> protocols = List.of(
                ModelApiProtocol.ANTHROPIC,
                ModelApiProtocol.OPENAI_CHAT,
                ModelApiProtocol.OPENAI_RESPONSES,
                ModelApiProtocol.GEMINI);
            List<String> upModels = List.of("up-anthropic", "up-chat", "up-responses", "up-gemini");

            var readTool = new CreateMessageRequest.ToolDefinition(
                "Read", "Read file or image",
                JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
            var imgSource = JsonUtils.getMapper().createObjectNode()
                .put("type", "base64")
                .put("media_type", "image/png")
                .put("data", pngBase64);

            // 1. 4 x 4 = 16 Streaming Tool-Use + Multimodal ToolResultBlock Round-Trip Matrix
            for (ModelApiProtocol downProto : protocols) {
                LlmClient downstreamJcc = downstreamClientFor(downProto, gwHost, lanKey.secret());
                for (String targetModel : upModels) {
                    if (Strings.CS.equals("up-gemini", targetModel)) {
                        mockGemini.enqueue(MockGeminiServer.Reply.toolCall(
                            "plan", "sig_m1", "call_up_1", "Read",
                            JsonUtils.getMapper().createObjectNode().put("path", "logo.png")));
                        mockGemini.enqueueText("MATRIX_OK");
                    }

                    // Turn 1: downstream JCC streams tool call from upstream via LAN Gateway
                    List<StreamEvent> turn1 = drainStream(downstreamJcc.createMessageStream(
                        CreateMessageRequest.builder()
                            .model(targetModel)
                            .maxTokens(512)
                            .stream(true)
                            .tools(List.of(readTool))
                            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "inspect logo.png")))
                            .build()));
                    ToolUseBlock toolCall = extractFirstToolUse(turn1);
                    assertThat(toolCall).as("down=%s up=%s", downProto, targetModel).isNotNull();
                    assertThat(toolCall.name()).isEqualTo("Read");

                    // Turn 2: downstream JCC returns ToolResultBlock containing PNG ImageBlock + TextBlock
                    List<StreamEvent> turn2 = drainStream(downstreamJcc.createMessageStream(
                        CreateMessageRequest.builder()
                            .model(targetModel)
                            .maxTokens(512)
                            .stream(true)
                            .tools(List.of(readTool))
                            .messages(List.of(
                                new CreateMessageRequest.RequestMessage("user", "inspect logo.png"),
                                new CreateMessageRequest.RequestMessage("assistant", List.<ContentBlock>of(toolCall)),
                                new CreateMessageRequest.RequestMessage("user", List.<ContentBlock>of(
                                    new ToolResultBlock(toolCall.id(), List.<ContentBlock>of(
                                        new TextBlock("loaded png"),
                                        new ImageBlock(imgSource)), false)))))
                            .build()));
                    assertThat(collectDeltaText(turn2))
                        .as("down=%s up=%s turn2", downProto, targetModel)
                        .contains("MATRIX_OK");
                }
            }

            // Verify AnthropicSdkClient upstream received explicit "type":"tool_result" and "type":"image"
            assertThat(anthropicBodies).anyMatch(b ->
                Strings.CS.contains(b.toString(), "\"type\":\"tool_result\"")
                    && Strings.CS.contains(b.toString(), pngBase64));
            assertThat(chatBodies).anyMatch(b -> Strings.CS.contains(b.toString(), pngBase64));
            assertThat(responsesBodies).anyMatch(b -> Strings.CS.contains(b.toString(), pngBase64));
            assertThat(mockGemini.requests()).anyMatch(r ->
                Strings.CS.contains(r.body().toString(), pngBase64));

            // 2. All 4 Thinking Effort Levels (low, medium, high, max) + Adaptive + First-Turn Vision
            for (String effort : List.of("low", "medium", "high", "max")) {
                for (int i = 0; i < protocols.size(); i++) {
                    ModelApiProtocol downProto = protocols.get(i);
                    String targetModel = upModels.get((i + 1) % upModels.size());
                    if (Strings.CS.equals("up-gemini", targetModel)) {
                        mockGemini.enqueueText("EFFORT_OK");
                    }
                    LlmClient downstreamJcc = downstreamClientFor(downProto, gwHost, lanKey.secret());
                    List<StreamEvent> events = drainStream(downstreamJcc.createMessageStream(
                        CreateMessageRequest.builder()
                            .model(targetModel)
                            .maxTokens(512)
                            .stream(true)
                            .effort(effort)
                            .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
                            .messages(List.of(new CreateMessageRequest.RequestMessage(
                                "user", List.<ContentBlock>of(
                                    new TextBlock("analyze color"),
                                    new ImageBlock(imgSource)))))
                            .build()));
                    assertThat(events).as("effort=%s down=%s up=%s", effort, downProto, targetModel)
                        .isNotEmpty();
                }
            }
        } finally {
            mockUpstreams.stop(0);
        }
    }

    private void startGateway() throws IOException {
        lanStore = new LanGatewayConfigStore(tempDir.resolve("gateway.json"));
        LlmClient stubLlm = new StubProxyLlmClient(proxiedUpstreamRequests);
        GatewayLanProxyPort proxyPort = new GatewayLanProxyPort() {
            @Override
            public List<SharedModel> listModels(boolean includeDefaultProvider) {
                return List.of(
                    new SharedModel("deepseek-chat", "chat", false, 128_000L),
                    new SharedModel("qwen-max", "chat", false, 128_000L));
            }

            @Override
            public Optional<LlmClient> resolveClient(String modelName, boolean allowDefaultProvider) {
                if (Strings.CS.equals("deepseek-chat", modelName)
                        || Strings.CS.equals("qwen-max", modelName)) {
                    return Optional.of(stubLlm);
                }
                return Optional.empty();
            }

            @Override
            public Optional<ModelApiProtocol> upstreamProtocol(String modelName) {
                return Optional.of(ModelApiProtocol.OPENAI_CHAT);
            }
        };

        server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), ADMIN_TOKEN, registry)
            .lanGateway(lanStore, proxyPort)
            .build();
        server.start();

        SessionHostSession localSession = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "", 0, Instant.now(), ""),
            hub, submission -> {
                hostSessionSubmits.incrementAndGet();
                hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(), null, "default"));
                hub.onMessage(new SDKMessage.Assistant(
                    new AssistantMessage(UUID.randomUUID().toString(),
                        new AssistantContent(null, List.of(new TextBlock("local-tui-reply")), null)),
                    new Usage(5, 3, 0, 0), "default"));
                hub.onTurnComplete(new TurnOutcome(
                    false, false, false, false, false, 5L, null, null, null, null));
                return CompletableFuture.completedFuture(null);
            });
        registry.activateLocal(localSession);
    }

    private String baseUrl(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private Response adminGet(String path) throws IOException {
        return client.newCall(new Request.Builder()
            .url(baseUrl(path))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .get()
            .build()).execute();
    }

    private Response adminPost(String path, String json) throws IOException {
        return client.newCall(new Request.Builder()
            .url(baseUrl(path))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .post(RequestBody.create(json, JSON))
            .build()).execute();
    }

    private static final class StubProxyLlmClient implements LlmClient {
        static volatile String lastObservedHopId;
        private final List<CreateMessageRequest> captured;

        StubProxyLlmClient(List<CreateMessageRequest> captured) {
            this.captured = captured;
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            lastObservedHopId = GatewayLanProxyPort.currentOutboundHopId();
            captured.add(request);
            return new ApiMessage(
                "msg_proxy_1",
                "message",
                "assistant",
                List.of(
                    new ThinkingBlock("step-1", "sig-abc"),
                    new TextBlock("Hello LAN"),
                    new ToolUseBlock("call_1", "Read",
                        JsonUtils.getMapper().createObjectNode().put("file_path", "README.md"))),
                request.model(),
                "tool_use",
                null,
                new Usage(25L, 12L, 0L, 0L));
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            lastObservedHopId = GatewayLanProxyPort.currentOutboundHopId();
            captured.add(request);
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(new ApiMessage(
                    "msg_stream_1", "message", "assistant", List.of(),
                    request.model(), null, null, new Usage(25L, 0L, 0L, 0L))),
                new StreamEvent.ContentBlockStart(0, new ThinkingBlock("", null)),
                new StreamEvent.ContentBlockDelta(0, new Delta.ThinkingDelta("step-1")),
                new StreamEvent.ContentBlockDelta(0, new Delta.SignatureDelta("sig-abc")),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.ContentBlockStart(1, new TextBlock("")),
                new StreamEvent.ContentBlockDelta(1, new Delta.TextDelta("Hello LAN")),
                new StreamEvent.ContentBlockStop(1),
                new StreamEvent.ContentBlockStart(2, new ToolUseBlock(
                    "call_1", "Read", JsonUtils.getMapper().createObjectNode())),
                new StreamEvent.ContentBlockDelta(2,
                    new Delta.InputJsonDelta("{\"file_path\":\"README.md\"}")),
                new StreamEvent.ContentBlockStop(2),
                new StreamEvent.MessageDelta(new MessageDeltaData("tool_use", null),
                    new Usage(25L, 12L, 0L, 0L)),
                new StreamEvent.MessageStop()
            ).iterator();
        }

        @Override
        public long countTokens(
                String model,
                List<CreateMessageRequest.RequestMessage> messages,
                List<CreateMessageRequest.ToolDefinition> tools) {
            return 42L;
        }

        @Override
        public String getModel() {
            return "deepseek-chat";
        }
    }

    private LlmClient downstreamClientFor(ModelApiProtocol protocol, String gwHost, String lanSecret) {
        String baseUrl = switch (protocol) {
            case ANTHROPIC -> gwHost;
            case OPENAI_CHAT, OPENAI_RESPONSES -> gwHost + "/v1";
            case GEMINI -> gwHost + "/v1beta";
        };
        return CustomModelRoutingClient.standard(
            new StubProxyLlmClient(proxiedUpstreamRequests),
            modelName -> Optional.of(new CustomModelConfig(
                modelName, protocol, baseUrl, lanSecret, Map.of(), 128_000L, true)),
            () -> null);
    }

    private static HttpServer startMultiProtocolUpstreamServer(
            List<JsonNode> anthropicBodies,
            List<JsonNode> chatBodies,
            List<JsonNode> responsesBodies) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        srv.createContext("/v1/messages", ex -> {
            try (ex) {
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = JsonUtils.parseTree(raw);
                anthropicBodies.add(req);
                boolean hasToolResult = Strings.CS.contains(raw, "\"tool_result\"");
                boolean wantTool = req.has("tools") && !req.path("tools").isEmpty() && !hasToolResult;
                String sse = wantTool
                    ? """
                      event: message_start
                      data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","content":[],"model":"up-anthropic","usage":{"input_tokens":10,"output_tokens":0}}}

                      event: content_block_start
                      data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                      event: content_block_delta
                      data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

                      event: content_block_stop
                      data: {"type":"content_block_stop","index":0}

                      event: content_block_start
                      data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_up_1","name":"Read","input":{}}}

                      event: content_block_delta
                      data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"logo.png\\"}"}}

                      event: content_block_stop
                      data: {"type":"content_block_stop","index":1}

                      event: message_delta
                      data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":6}}

                      event: message_stop
                      data: {"type":"message_stop"}

                      """
                    : """
                      event: message_start
                      data: {"type":"message_start","message":{"id":"m2","type":"message","role":"assistant","content":[],"model":"up-anthropic","usage":{"input_tokens":12,"output_tokens":0}}}

                      event: content_block_start
                      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                      event: content_block_delta
                      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"MATRIX_OK"}}

                      event: content_block_stop
                      data: {"type":"content_block_stop","index":0}

                      event: message_delta
                      data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                      event: message_stop
                      data: {"type":"message_stop"}

                      """;
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            }
        });

        srv.createContext("/v1/chat/completions", ex -> {
            try (ex) {
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = JsonUtils.parseTree(raw);
                chatBodies.add(req);
                boolean hasToolResult = Strings.CS.contains(raw, "\"role\":\"tool\"");
                boolean wantTool = req.has("tools") && !req.path("tools").isEmpty() && !hasToolResult;
                String sse = wantTool
                    ? """
                      data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"plan"}}]}

                      data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_up_1","type":"function","function":{"name":"Read","arguments":"{\\"path\\":\\"logo.png\\"}"}}]}}]}

                      data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":9,"completion_tokens":5}}

                      data: [DONE]

                      """
                    : """
                      data: {"id":"c2","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"MATRIX_OK"}}]}

                      data: {"id":"c2","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":14,"completion_tokens":3}}

                      data: [DONE]

                      """;
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            }
        });

        srv.createContext("/v1/responses", ex -> {
            try (ex) {
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = JsonUtils.parseTree(raw);
                responsesBodies.add(req);
                boolean hasToolResult = Strings.CS.contains(raw, "\"function_call_output\"");
                boolean wantTool = req.has("tools") && !req.path("tools").isEmpty() && !hasToolResult;
                String sse = wantTool
                    ? """
                      event: response.created
                      data: {"type":"response.created","response":{"id":"r1","status":"in_progress","output":[]}}

                      event: response.output_item.added
                      data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_up_1","name":"Read","arguments":""}}

                      event: response.function_call_arguments.delta
                      data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"{\\"path\\":\\"logo.png\\"}"}

                      event: response.output_item.done
                      data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_up_1","name":"Read","arguments":"{\\"path\\":\\"logo.png\\"}"}}

                      event: response.completed
                      data: {"type":"response.completed","response":{"id":"r1","status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":"call_up_1","name":"Read","arguments":"{\\"path\\":\\"logo.png\\"}"}],"usage":{"input_tokens":10,"output_tokens":5}}}

                      """
                    : """
                      event: response.created
                      data: {"type":"response.created","response":{"id":"r2","status":"in_progress","output":[]}}

                      event: response.output_text.delta
                      data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"MATRIX_OK"}

                      event: response.completed
                      data: {"type":"response.completed","response":{"id":"r2","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"MATRIX_OK"}]}],"usage":{"input_tokens":15,"output_tokens":3}}}

                      """;
                byte[] out = sse.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
            }
        });

        srv.start();
        return srv;
    }

    private static List<StreamEvent> drainStream(Iterator<StreamEvent> it) {
        List<StreamEvent> events = new ArrayList<>();
        it.forEachRemaining(events::add);
        return events;
    }

    private static ToolUseBlock extractFirstToolUse(List<StreamEvent> events) {
        String id = null;
        String name = null;
        StringBuilder json = new StringBuilder();
        for (StreamEvent ev : events) {
            if (ev instanceof StreamEvent.ContentBlockStart(_, ToolUseBlock tu, _) && id == null) {
                id = tu.id();
                name = tu.name();
                if (tu.input() != null && !tu.input().isEmpty()) {
                    json.append(tu.input().toString());
                }
            } else if (ev instanceof StreamEvent.ContentBlockDelta(_, Delta.InputJsonDelta(String p), _)
                    && id != null) {
                json.append(p);
            }
        }
        if (id == null) return null;
        JsonNode parsed = json.isEmpty()
            ? JsonUtils.getMapper().createObjectNode()
            : JsonUtils.parseTree(json.toString());
        return new ToolUseBlock(id, name, parsed);
    }

    private static String collectDeltaText(List<StreamEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent ev : events) {
            if (ev instanceof StreamEvent.ContentBlockDelta(_, Delta.TextDelta(String t), _)) {
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
