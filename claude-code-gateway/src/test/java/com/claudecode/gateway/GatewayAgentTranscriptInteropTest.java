package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interop tests for the sub-agent transcript endpoint
 * ({@code GET /api/sessions/{id}/agents/{agentId}/transcript}): one
 * sub-agent's own conversation, resolved by the composition root from its
 * sidechain transcript and projected through the same wire shape as the
 * parent message snapshot.
 */
class GatewayAgentTranscriptInteropTest {

    private static final String TOKEN = "d".repeat(48);

    private final String sessionId = UUID.randomUUID().toString();
    private final String agentId = "a1b2c3";
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
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

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void projectsSubAgentRowsThroughTheParentSnapshotShape() throws Exception {
        // A sub-agent's own conversation: a prompt, an assistant step that
        // calls a tool, and the tool's result — the same row kinds the parent
        // snapshot serves, so a client renders them with its ordinary row
        // renderer.
        List<Message> transcript = List.of(
            userText("list the files"),
            new AssistantMessage(UUID.randomUUID().toString(), new AssistantContent(
                "msg_a", List.of(
                    new TextBlock("checking the directory"),
                    new ToolUseBlock("toolu_a", "Bash",
                        JsonUtils.getMapper().createObjectNode().put("command", "ls"))),
                null)),
            new UserMessage(UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(
                    new ToolResultBlock("toolu_a", List.of(new TextBlock("a\nb")), false))),
                false, false, null, MessageOrigin.USER, null, null, null, null, null,
                null, null, null, null, null, null, null));
        startServer(transcript);

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/agents/" + agentId
                    + "/transcript?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"session_id\":\"" + sessionId + "\"")
                .contains("\"agent_id\":\"" + agentId + "\"");
            assertThat(body).contains("\"role\":\"user\"").contains("list the files");
            assertThat(body).contains("\"type\":\"text\",\"text\":\"checking the directory\"");
            assertThat(body).contains("\"tool_use_id\":\"toolu_a\"")
                .contains("\"name\":\"Bash\"")
                .contains("\"status\":\"executed\"")
                .contains("\"type\":\"execute_command_tool_result\"");
        }
    }

    @Test
    @Timeout(20)
    void subAgentRowsCarryNoTurnNumbering() throws Exception {
        // A sub-agent's conversation is its own, not a numbered turn of the
        // parent — so turn/turn_usage stay off its rows even though the rows
        // are the very ones (prompt, assistant, tool result) that would be
        // numbered in the parent snapshot.
        List<Message> transcript = List.of(
            userText("do the thing"),
            new AssistantMessage(UUID.randomUUID().toString(), new AssistantContent(
                "m", List.of(new TextBlock("done")), null)));
        startServer(transcript);

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/agents/" + agentId
                    + "/transcript?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"text\":\"done\"")
                .doesNotContain("\"turn\":")
                .doesNotContain("\"turn_usage\"");
        }
    }

    @Test
    @Timeout(20)
    void unknownAgentIs404AndMissingTokenIs401() throws Exception {
        startServer(List.of(userText("hi")));
        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/agents/" + agentId + "/transcript"))
                .build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response unknown = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/agents/no-such-agent/transcript?token="
                    + TOKEN))
                .build()).execute()) {
            assertThat(unknown.code()).isEqualTo(404);
            assertThat(unknown.body().string()).contains("unknown agent");
        }
    }

    @Test
    @Timeout(20)
    void malformedPathWithoutAgentMarkerIs400() throws Exception {
        startServer(List.of(userText("hi")));
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/transcript?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("session id and agent id are required");
        }
    }

    private void startServer(List<Message> transcript) throws IOException {
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .headless(new GatewayHeadlessSessions() {})
            .sessionMessages(new GatewaySessionMessagesPort() {
                @Override public Optional<List<Message>> agentMessages(
                        String id, String agent) {
                    return agentId.equals(agent) && sessionId.equals(id)
                        ? Optional.of(transcript) : Optional.empty();
                }
            })
            .build();
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private static UserMessage userText(String text) {
        return new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(text),
            false, false, null, MessageOrigin.USER, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    /** A sink that discards every session event (these tests never emit any). */
    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}