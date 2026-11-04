package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.tasks.TaskBoardPort;
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
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code GET /api/session/tasks} end to end through the gateway: the webui
 * to-do dock's cold load. Covers the routing the endpoint shares with the
 * other session-scoped reads (the active TUI session, an explicit id that
 * does not match, and no port configured at all) plus the wire projection —
 * the status enum is lowercased for the client, and a session with no board
 * renders an empty list rather than a failure.
 */
class GatewayTaskBoardInteropTest {

    private static final String TOKEN = "t".repeat(48);

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** A blank id reads the active session's board and projects the full item shape. */
    @Test
    @Timeout(20)
    void readsTheActiveSessionsBoard() throws Exception {
        String sessionId = startServer("session-a", board("list-7", 4L,
            item("t1", "Wire the dock", TaskBoardPort.Status.IN_PROGRESS),
            item("t2", "Cover it", TaskBoardPort.Status.PENDING)));
        try (Response response = get("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"list_id\":\"list-7\"")
                .contains("\"revision\":4")
                .contains("\"hidden\":false")
                .contains("\"source\":\"snapshot\"")
                .contains("\"subject\":\"Wire the dock\"")
                // The status enum crosses the wire lowercased, matching the
                // union the webui store is typed against.
                .contains("\"status\":\"in_progress\"")
                .contains("\"status\":\"pending\"");
        }
        assertThat(sessionId).isEqualTo("session-a");
    }

    /** An explicit id addressing the served board reads it too. */
    @Test
    @Timeout(20)
    void readsAnExplicitlyAddressedBoard() throws Exception {
        String sessionId = startServer("session-b", board("list-1", 1L,
            item("t1", "Only task", TaskBoardPort.Status.COMPLETED)));
        try (Response response = get("?session_id=" + sessionId)) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"status\":\"completed\"")
                .contains("\"subject\":\"Only task\"");
        }
    }

    /**
     * An id the board does not answer to renders an empty list, not another
     * session's to-dos: serving wrong data under the requested id is worse
     * than serving none.
     */
    @Test
    @Timeout(20)
    void anUnknownIdRendersAnEmptyBoard() throws Exception {
        startServer("session-c", board("list-1", 1L,
            item("t1", "Not yours", TaskBoardPort.Status.PENDING)));
        try (Response response = get("?session_id=no-such-session")) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"tasks\":[]").contains("\"hidden\":true");
            assertThat(body).doesNotContain("Not yours");
        }
    }

    /** No port configured answers an empty board rather than failing the read. */
    @Test
    @Timeout(20)
    void answersAnEmptyBoardWithoutAPort() throws Exception {
        startServer("session-d", null);
        try (Response response = get("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"tasks\":[]")
                .contains("\"hidden\":true");
        }
    }

    /** The endpoint requires the same bearer token as every other read. */
    @Test
    @Timeout(20)
    void rejectsAnUnauthenticatedRead() throws Exception {
        startServer("session-e", board("list-1", 1L));
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/session/tasks")
            .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private static TaskBoardPort.Snapshot board(
            String listId, long revision, TaskBoardPort.TaskItem... tasks) {
        return new TaskBoardPort.Snapshot(listId, revision, List.of(tasks), tasks.length == 0);
    }

    private static TaskBoardPort.TaskItem item(String id, String subject, TaskBoardPort.Status status) {
        return new TaskBoardPort.TaskItem(id, subject, "", "", "", status, List.of(), List.of());
    }

    private String startServer(String sessionId, TaskBoardPort.Snapshot snapshot) throws Exception {
        return boot(sessionId, snapshot);
    }

    private String boot(String sessionId, TaskBoardPort.Snapshot snapshot) throws Exception {
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
        // Same resolution the CLI adapter performs: a blank id and the served
        // session's own id both reach the board; anything else is empty.
        GatewayTaskBoardPort taskBoard = id -> {
            if (snapshot == null) return Optional.empty();
            if (StringUtils.isNotBlank(id) && !id.equals(sessionId)) return Optional.empty();
            return Optional.of(snapshot);
        };
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            new GatewaySessionCatalogPort() {}, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, new GatewaySessionContextPort() {},
            new GatewaySessionActionsPort() {}, new GatewayPermissionModePort() {},
            new GatewaySpinnerVerbsPort() {}, new GatewayFileSuggestionsPort() {},
            taskBoard, GatewayFileDiffPort.none());
        server.start();
        // The session is activated after the server is up, so the blank-id case
        // resolves the same active session the catalog endpoints would see.
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
        return sessionId;
    }

    private Response get(String query) throws Exception {
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/session/tasks" + query)
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