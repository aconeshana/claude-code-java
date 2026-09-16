package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The four context-timeline routes over loopback HTTP: auth, the dsh-context
 * wire field names, cold-session nulls, the content faces, and the overview.
 */
class GatewayContextTimelineInteropTest {

    private static final String TOKEN = "b".repeat(48);

    private final String activeSessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new SessionSink() {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }, _ -> {});
    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();
    private GatewayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void timelineAndDetailServeDshShapesForTheActiveSession() throws Exception {
        FakePort port = new FakePort(activeSessionId);
        port.messages = conversation();
        port.breakdown = new GatewaySessionContextPort.ContextBreakdown(1_200, 800, 0);
        port.metrics = new SessionMetricsSnapshot(true, 1, 1, 3_000, 500, 400, 1, 2_600, 120,
            8_000, 120, 300, 5_000);
        startServer(port);

        try (Response denied = client.newCall(new Request.Builder()
                .url(url("/api/session/context/timeline")).build()).execute()) {
            assertThat(denied.code()).isEqualTo(401);
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/timeline?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode timeline = JsonUtils.getMapper().readTree(response.body().string())
                .path("timeline");
            assertThat(timeline.path("ok").asBoolean()).isTrue();
            assertThat(timeline.path("model").asText()).isEqualTo("claude-sonnet-5");
            assertThat(timeline.path("current").path("system").asLong()).isEqualTo(1_200);
            assertThat(timeline.path("current").path("tools").asLong()).isEqualTo(800);
            assertThat(timeline.path("counts").path("steps").asInt()).isEqualTo(1);
            assertThat(timeline.path("detailRev").isNumber()).isTrue();
            assertThat(timeline.path("last").path("prompt").asLong()).isEqualTo(13_300);
            assertThat(timeline.path("timing").path("wallMs").asLong()).isEqualTo(3_500);
            assertThat(timeline.path("timing").path("ttftMs").asLong()).isEqualTo(400);
            assertThat(timeline.path("running").asBoolean()).isFalse();
            assertThat(timeline.path("systems").get(0).path("tokens").asLong()).isEqualTo(1_200);
            assertThat(timeline.has("requests")).isTrue();
            assertThat(timeline.path("requests")).isEmpty();
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/detail?token=" + TOKEN
                    + "&session_id=" + activeSessionId)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode detail = JsonUtils.getMapper().readTree(response.body().string())
                .path("detail");
            assertThat(detail.path("rev").asLong()).isEqualTo(detail.path("head").path("detailRev").asLong());
            assertThat(detail.path("requests")).hasSize(1);
            assertThat(detail.path("nodes")).hasSize(4);
            assertThat(detail.path("nodes").get(3).path("cat").asText()).isEqualTo("tool");
            assertThat(detail.path("fileOps")).hasSize(1);
            assertThat(detail.path("agents")).isEmpty();
            assertThat(detail.path("archive")).isEmpty();
            assertThat(detail.path("droppedNodes").asInt()).isZero();
            JsonNode epoch = detail.path("headers").path("headers").get(0);
            assertThat(epoch.path("systemTokens").asLong()).isPositive();
            assertThat(epoch.path("tools").get(0).path("name").asText()).isEqualTo("Read");
            assertThat(epoch.path("tools").get(0).path("plugin").asText()).isEqualTo("builtin");
        }
    }

    @Test
    @Timeout(20)
    void contentServesNodeTextSystemPromptAndToolSchema() throws Exception {
        FakePort port = new FakePort(activeSessionId);
        port.messages = conversation();
        startServer(port);

        String detailBody;
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/detail?token=" + TOKEN)).build()).execute()) {
            detailBody = response.body().string();
        }
        long toolSeq = JsonUtils.getMapper().readTree(detailBody).path("detail")
            .path("nodes").get(3).path("seq").asLong();
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN + "&seq=" + toolSeq))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode content = JsonUtils.getMapper().readTree(response.body().string())
                .path("content");
            assertThat(content.path("cat").asText()).isEqualTo("tool");
            assertThat(content.path("tool").asText()).isEqualTo("Read");
            assertThat(content.path("text").asText()).isEqualTo("line one\nline two\n");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN + "&kind=system"))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode content = JsonUtils.getMapper().readTree(response.body().string())
                .path("content");
            assertThat(content.path("text").asText()).contains("You are a coding agent");
            assertThat(content.path("parts")).hasSize(2);
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN + "&kind=tool&name=Read"))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode content = JsonUtils.getMapper().readTree(response.body().string())
                .path("content");
            assertThat(content.path("name").asText()).isEqualTo("Read");
            assertThat(content.path("schema").path("type").asText()).isEqualTo("object");
            assertThat(content.path("source").asText()).isEqualTo("builtin");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN + "&kind=tools"))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode content = JsonUtils.getMapper().readTree(response.body().string())
                .path("content");
            assertThat(content.path("kind").asText()).isEqualTo("tools");
            assertThat(content.path("tools")).isNotEmpty();
            assertThat(content.path("tools").get(0).path("name").asText()).isEqualTo("Read");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN + "&seq=999999"))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(404);
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/content?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(400);
        }
    }

    @Test
    @Timeout(20)
    void coldSessionsAnswerNullAndOverviewListsOnlyLiveSessions() throws Exception {
        FakePort port = new FakePort(activeSessionId);
        port.messages = conversation();
        startServer(port);

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/timeline?token=" + TOKEN + "&session_id=cold-id"))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"timeline\":null}");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/detail?token=" + TOKEN + "&session_id=cold-id"))
                .build()).execute()) {
            assertThat(response.body().string()).isEqualTo("{\"detail\":null}");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/overview?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode overview = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(overview.path("sessions")).hasSize(1);
            JsonNode row = overview.path("sessions").get(0);
            assertThat(row.path("id").asText()).isEqualTo(activeSessionId);
            assertThat(row.path("title").asText()).isEqualTo("a session");
            assertThat(row.path("running").asBoolean()).isFalse();
            assertThat(row.path("timeline").path("counts").path("steps").asInt()).isEqualTo(1);
            assertThat(row.path("activity").path("days").size()).isEqualTo(1);
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/context/unknown?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------- fixtures

    private static List<Message> conversation() {
        Instant t0 = Instant.parse("2026-09-16T10:00:00Z");
        List<Message> rows = new ArrayList<>();
        rows.add(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("read a.txt"),
            false, false, null, MessageOrigin.USER, null, t0, null, null));
        Usage usage = new Usage(8_000, 120, 300, 5_000, null, "standard", null, "",
            List.of(), "standard", null);
        rows.add(assistant("resp-1", List.of(new TextBlock("Reading.")), usage, t0.plusSeconds(1)));
        ObjectNode input = JsonUtils.getMapper().createObjectNode().put("file_path", "/work/a.txt");
        rows.add(assistant("resp-1", List.of(new ToolUseBlock("tu-1", "Read", input)), usage,
            t0.plusSeconds(1)));
        rows.add(new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new ToolResultBlock("tu-1",
                List.of(new TextBlock("line one\nline two\n")), false))),
            false, false, null, MessageOrigin.TOOL_RESULT, null, t0.plusSeconds(2), null, null));
        return rows;
    }

    private static AssistantMessage assistant(String id, List<ContentBlock> blocks, Usage usage,
                                              Instant time) {
        return new AssistantMessage(UUID.randomUUID().toString(),
            new AssistantContent(id, blocks, usage, "claude-sonnet-5", "end_turn", null),
            false, null, time);
    }

    /** A port double serving one live session (the active TUI id) and nothing else. */
    private static final class FakePort implements GatewaySessionContextPort {
        private final String liveId;
        List<Message> messages = List.of();
        ContextBreakdown breakdown;
        SessionMetricsSnapshot metrics;

        FakePort(String liveId) {
            this.liveId = liveId;
        }

        private boolean live(String sessionId) {
            return StringUtils.isBlank(sessionId) || sessionId.equals(liveId);
        }

        @Override public Optional<ModelSelection> selection(String sessionId) {
            return live(sessionId)
                ? Optional.of(new ModelSelection("claude-sonnet-5", List.of(), "", "", List.of()))
                : Optional.empty();
        }

        @Override public Optional<List<Message>> messages(String sessionId) {
            return live(sessionId) ? Optional.of(messages) : Optional.empty();
        }

        @Override public Optional<ContextBreakdown> breakdown(String sessionId) {
            return live(sessionId) ? Optional.ofNullable(breakdown) : Optional.empty();
        }

        @Override public Optional<SessionMetricsSnapshot> metrics(String sessionId) {
            return live(sessionId) ? Optional.ofNullable(metrics) : Optional.empty();
        }

        @Override public Optional<HeaderContent> headerContent(String sessionId) {
            if (!live(sessionId)) return Optional.empty();
            ObjectNode schema = JsonUtils.getMapper().createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("file_path").put("type", "string");
            return Optional.of(new HeaderContent(
                List.of("You are a coding agent.", "Follow the rules."),
                List.of(new HeaderTool("Read", "Reads a file", schema, "builtin"))));
        }

        @Override public List<LiveSession> liveSessions() {
            return List.of(new LiveSession(liveId, "a session", "/work", Instant.now()));
        }
    }

    private void startServer(GatewaySessionContextPort context) throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, context);
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(activeSessionId, "/work", "a session", 3, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }
}
