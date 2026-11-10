package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayWorkspaceFilePort.TextFile;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code GET /api/workspace-file} end to end through the gateway: the whole-file
 * preview read. Text carries its content; binary and oversized carry only the
 * notice, so the preview can never render bytes it refused to decode.
 */
class GatewayWorkspaceFileInteropTest {

    private static final String TOKEN = "t".repeat(48);
    private static final String PATH = "src/App.java";

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void servesTextWithItsFullContent() throws Exception {
        startServer((sessionId, path) ->
            Optional.of(new TextFile(path, TextFile.Kind.TEXT, "class App {}\n")));
        try (Response response = get("/api/workspace-file?path=" + PATH)) {
            assertThat(response.code()).isEqualTo(200);
            var body = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(body.get("path").textValue()).isEqualTo(PATH);
            assertThat(body.get("kind").textValue()).isEqualTo("text");
            assertThat(body.get("text").textValue()).isEqualTo("class App {}\n");
        }
    }

    @Test
    @Timeout(20)
    void omitsTheContentOfBinaryAndOversizedFiles() throws Exception {
        startServer((sessionId, path) ->
            Optional.of(new TextFile(path, TextFile.Kind.BINARY, "must not leak")));
        try (Response response = get("/api/workspace-file?path=" + PATH)) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"kind\":\"binary\"").doesNotContain("text");
            assertThat(body).doesNotContain("must not leak");
        }

        server.close();
        startServer((sessionId, path) ->
            Optional.of(new TextFile(path, TextFile.Kind.OVERSIZED, "must not leak")));
        try (Response response = get("/api/workspace-file?path=" + PATH)) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"kind\":\"oversized\"").doesNotContain("text");
            assertThat(body).doesNotContain("must not leak");
        }
    }

    @Test
    @Timeout(20)
    void passesAnExplicitSessionIdToThePort() throws Exception {
        List<String> seen = new java.util.ArrayList<>();
        startServer((sessionId, path) -> {
            seen.add(sessionId);
            return Optional.of(new TextFile(path, TextFile.Kind.TEXT, ""));
        });
        try (Response response = get("/api/workspace-file?session_id=session-z&path=" + PATH)) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(seen).containsExactly("session-z");
    }

    @Test
    @Timeout(20)
    void answersNotFoundWhenThePortCannotResolveThePath() throws Exception {
        startServer(GatewayWorkspaceFilePort.none());
        try (Response response = get("/api/workspace-file?path=" + PATH)) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("\"type\":\"not_found\"");
        }
    }

    @Test
    @Timeout(20)
    void rejectsABlankPath() throws Exception {
        startServer(GatewayWorkspaceFilePort.none());
        try (Response response = get("/api/workspace-file?path=%20")) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"type\":\"invalid_request\"");
        }
    }

    private void startServer(GatewayWorkspaceFilePort files) throws Exception {
        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override public CompletableFuture<SessionHostSession> activate(
                        SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used");
                }
                @Override public List<SessionHostInfo> list() {
                    return List.of();
                }
            });
        SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .headless(new GatewayHeadlessSessions() {})
            .workspaceFile(files)
            .build();
        server.start();
        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo("session-a", "/work", "a session", 3, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null)));
    }

    private Response get(String path) throws Exception {
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + path)
            .header("Authorization", "Bearer " + TOKEN)
            .get()
            .build();
        return client.newCall(request).execute();
    }

    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
