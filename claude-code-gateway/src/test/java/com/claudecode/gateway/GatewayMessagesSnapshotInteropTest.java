package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
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
import com.claudecode.core.message.SDKMessage;
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
 * Interop tests for the message snapshot endpoint: the authoritative
 * current message list of one session, with typed tool_call entries paired
 * by tool use id — the snapshot half of the snapshot-first model.
 */
class GatewayMessagesSnapshotInteropTest {

    private static final String TOKEN = "c".repeat(48);

    private final String sessionId = UUID.randomUUID().toString();
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
    void snapshotProjectsTypedToolCallEntriesPairedByToolUseId() throws Exception {
        List<Message> conversation = List.of(
            userText("please list the files"),
            new AssistantMessage(UUID.randomUUID().toString(), new AssistantContent(
                "msg_a", List.of(
                    new TextBlock("let me check"),
                    new ThinkingBlock("thinking about it"),
                    new ToolUseBlock("toolu_1", "Bash",
                        JsonUtils.getMapper().createObjectNode()
                            .put("command", "ls -la")),
                    // A second call whose result never arrived stays pending.
                    new ToolUseBlock("toolu_2", "Read", null)), null)),
            new UserMessage(UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(
                    new ToolResultBlock("toolu_1",
                        List.of(new TextBlock("file-a\nfile-b")), false))),
                false, false, null, MessageOrigin.USER, null, null, null, null, null,
                null, null, null, null, null, null, null),
            new AssistantMessage(UUID.randomUUID().toString(), new AssistantContent(
                "msg_b", List.of(new TextBlock("there are two files")), null)));
        startServer(new GatewaySessionMessagesPort() {
            @Override public Optional<List<Message>> messages(
                    String id, String headlessId) {
                return Optional.of(conversation);
            }
        });

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/messages?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"session_id\":\"" + sessionId + "\"");
            // User text row.
            assertThat(body).contains("\"role\":\"user\"")
                .contains("please list the files");
            // Assistant content[] entries: text, thinking, and the paired
            // tool_call with the executed result.
            assertThat(body).contains("\"type\":\"text\",\"text\":\"let me check\"");
            assertThat(body).contains(
                "\"type\":\"thinking\",\"thinking\":\"thinking about it\"");
            assertThat(body).contains("\"tool_use_id\":\"toolu_1\"")
                .contains("\"name\":\"Bash\"")
                .contains("\"status\":\"executed\"")
                .contains("\"ready\":true")
                .contains("\"type\":\"execute_command_tool_result\"")
                .contains("\"data\":\"file-a\\nfile-b\"");
            // The unmatched call stays pending and not ready.
            assertThat(body).contains("\"tool_use_id\":\"toolu_2\"")
                .contains("\"status\":\"pending\"")
                .contains("\"ready\":false");
            assertThat(body).contains("there are two files");
        }
    }

    @Test
    @Timeout(20)
    void failedToolResultProjectsErrorFields() throws Exception {
        List<Message> conversation = List.of(
            new AssistantMessage(UUID.randomUUID().toString(), new AssistantContent(
                "msg_c", List.of(new ToolUseBlock("toolu_err", "Read", null)), null)),
            new UserMessage(UUID.randomUUID().toString(),
                MessageContent.ofBlocks(List.of(
                    new ToolResultBlock("toolu_err",
                        List.of(new TextBlock("File not found")), true))),
                false, false, null, MessageOrigin.USER, null, null, null, null, null,
                null, null, null, null, null, null, null));
        startServer(fixedMessages(conversation));

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/messages?token=" + TOKEN))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"status\":\"failed\"")
                .contains("\"type\":\"read_file_tool_result\"")
                .contains("\"errorMessage\":\"File not found\"")
                .contains("\"errorCode\":\"tool_error\"");
        }
    }

    @Test
    @Timeout(20)
    void unknownSessionIs404AndMissingTokenIs401() throws Exception {
        // The port answers empty (unknown id) — not the active session — so
        // the handler must surface 404 even though a session is active.
        startServer(new GatewaySessionMessagesPort() {});
        try (Response anonymous = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/messages")).build()).execute()) {
            assertThat(anonymous.code()).isEqualTo(401);
        }
        try (Response unknown = client.newCall(new Request.Builder()
                .url(url("/api/sessions/no-such-session/messages?token=" + TOKEN))
                .build()).execute()) {
            assertThat(unknown.code()).isEqualTo(404);
            assertThat(unknown.body().string()).contains("unknown session");
        }
    }

    private void startServer(GatewaySessionMessagesPort messages) throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN,
            registry, new GatewaySessionCatalogPort() {},
            new GatewayHeadlessSessions() {}, null, messages);
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
    }

    /** A port always answering with the same fixed message list. */
    private static GatewaySessionMessagesPort fixedMessages(List<Message> rows) {
        return new GatewaySessionMessagesPort() {
            @Override public Optional<List<Message>> messages(
                    String id, String headlessId) {
                return Optional.of(rows);
            }
        };
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private static UserMessage userText(String text) {
        return new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofText(text));
    }

    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
