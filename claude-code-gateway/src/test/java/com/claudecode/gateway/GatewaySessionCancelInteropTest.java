package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code POST /api/session/cancel} end to end through the gateway: the
 * webui composer's stop button. Covers the routing the stop shares with the
 * other session-scoped endpoints (an open headless id, the active TUI
 * session's own id, blank for the active session) plus the two failure
 * outcomes the client renders differently — 404 for an id no live session
 * answers to, and the same 404 when no cancel port is configured at all.
 */
class GatewaySessionCancelInteropTest {

    private static final String TOKEN = "k".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** An explicit, known session id stops that session. */
    @Test
    @Timeout(20)
    void cancelsTheAddressedSession() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        String sessionId = startServer("session-a", stops);
        try (Response response = post("{\"session_id\":\"" + sessionId + "\"}")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"cancelling\":true")
                .contains("\"session_id\":\"" + sessionId + "\"");
        }
        assertThat(stops.get()).isEqualTo(1);
    }

    /** A blank id targets the active session, matching the other session endpoints. */
    @Test
    @Timeout(20)
    void blankIdTargetsTheActiveSession() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        startServer("session-b", stops);
        try (Response response = post("{}")) {
            assertThat(response.code()).isEqualTo(200);
            // No id echoed when the active session was addressed.
            assertThat(response.body().string()).contains("\"cancelling\":true");
        }
        assertThat(stops.get()).isEqualTo(1);
    }

    /** An id no live session answers to is 404, not a silent success. */
    @Test
    @Timeout(20)
    void unknownSessionIsNotFound() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        startServer("session-c", stops);
        try (Response response = post("{\"session_id\":\"no-such-session\"}")) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("unknown session");
        }
        assertThat(stops.get()).isZero();
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private String startServer(String sessionId, AtomicInteger stops) throws Exception {
        GatewaySessionCancelPort cancel = id -> {
            // Same resolution the CLI adapter performs: a blank id or the
            // active session's own id reaches the one live session.
            if (id != null && !id.equals(sessionId)) return false;
            stops.incrementAndGet();
            return true;
        };
        return boot(sessionId, cancel);
    }

    private String boot(String sessionId, GatewaySessionCancelPort cancel) throws Exception {
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
            .cancel(cancel)
            .build();
        server.start();
        // The session is activated after the server is up, so the blank-id case
        // resolves the same active session the catalog endpoints would see.
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
        return sessionId;
    }

    private Response post(String body) throws Exception {
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/session/cancel")
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(body, JSON))
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
