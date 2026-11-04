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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code GET /api/changes.diff} and {@code POST /api/changes.open} end to end
 * through the gateway: the delivered-files viewer's read and the native
 * open-file action.
 *
 * <p>The read covers the routing the other session-scoped endpoints share
 * (blank id resolving to the active session, an explicit id, a path no turn
 * touched) plus the wire projection the viewer's discriminated union is typed
 * against — a text diff carries its two sides, while binary and oversized
 * carry only the notice. The open action is asserted to answer an explicit
 * {@code 422 unavailable} rather than 404 or a silent 200: the browser client
 * greys the control out from that answer, so a wrong status would either hide
 * the control's reason or fake a success.
 */
class GatewayChangesInteropTest {

    private static final String TOKEN = "t".repeat(48);
    private static final String TEXT_PATH = "src/App.java";

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** A text diff projects both sides, with the path carried verbatim. */
    @Test
    @Timeout(20)
    void readsATextDiff() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, "class Old {}\n", "class New {}\n"));
            }
        });
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"kind\":\"text\"")
                .contains("\"path\":\"" + TEXT_PATH + "\"")
                .contains("\"display\":\"" + TEXT_PATH + "\"")
                .contains("\"old_text\":\"class Old {}\\n\"")
                .contains("\"new_text\":\"class New {}\\n\"")
                .contains("\"truncated\":false");
        }
    }

    /**
     * A created file's old side is absent from the payload rather than an empty
     * string, which is what "no removed lines" means to the viewer.
     */
    @Test
    @Timeout(20)
    void omitsTheOldSideForACreatedFile() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, null, "package app;\n"));
            }
        });
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"old_text\":null")
                .contains("\"new_text\":\"package app;\\n\"");
        }
    }

    /** Binary and oversized files carry only their notice; there are no sides. */
    @Test
    @Timeout(20)
    void readsBinariesAndOversizedFilesAsNotices() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.of(path, FileDiff.Kind.BINARY));
            }
        });
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"kind\":\"binary\"");
            assertThat(body).doesNotContain("old_text");
        }

        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.of(path, FileDiff.Kind.OVERSIZED));
            }
        });
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"kind\":\"oversized\"");
        }
    }

    /** An explicit id reaches the port as given, so the CLI can filter on it. */
    @Test
    @Timeout(20)
    void passesAnExplicitSessionIdToThePort() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, null, "from:" + sessionId + "\n"));
            }
        });
        try (Response response = getDiff("&session_id=session-z")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"new_text\":\"from:session-z\\n\"");
        }
    }

    /**
     * A path no turn touched is a 404, not an empty diff: the viewer shows a
     * failure, which is the truthful reading of "this session never wrote it".
     */
    @Test
    @Timeout(20)
    void answersNotFoundForAnUntouchedPath() throws Exception {
        startServer(GatewayFileDiffPort.none());
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("\"type\":\"not_found\"");
        }
    }

    /** A missing path is a client error, not a lookup miss. */
    @Test
    @Timeout(20)
    void rejectsABlankPath() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, "a\n", "b\n"));
            }
        });
        try (Response response = get("/api/changes.diff?path=%20")) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"type\":\"invalid_request\"");
        }
    }

    /**
     * The open action reports unavailability with a readable reason. The
     * browser client renders the control disabled from this answer, so it must
     * stay a deliberate 422 rather than becoming a 404 or a bare 500.
     */
    @Test
    @Timeout(20)
    void reportsTheOpenActionAsUnavailable() throws Exception {
        startServer(GatewayFileDiffPort.none());
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/changes.open")
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(
                "{\"path\":\"src/App.java\"}",
                MediaType.parse("application/json")))
            .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(422);
            assertThat(response.body().string())
                .contains("\"type\":\"unavailable\"")
                .contains("cannot open local files");
        }
    }

    /** The read requires the same bearer token as every other endpoint. */
    @Test
    @Timeout(20)
    void rejectsAnUnauthenticatedRead() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, "a\n", "b\n"));
            }
        });
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/changes.diff?path=" + TEXT_PATH)
            .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────

    private void startServer(GatewayFileDiffPort fileDiff) throws Exception {
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
            .fileDiff(fileDiff)
            .build();
        server.start();
        // The session is activated after the server is up, so the blank-id case
        // resolves the same active session the catalog endpoints would see.
        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo("session-a", "/work", "a session", 3, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null)));
    }

    /** A read of {@link #TEXT_PATH}; {@code extra} adds query parameters. */
    private Response getDiff(String extra) throws Exception {
        return get("/api/changes.diff?path=" + TEXT_PATH + extra);
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