package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayFileDiffPort.FileDiff;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
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
 * {@code GET /api/changes.summary} and {@code GET /api/changes.diff} end to end
 * through the gateway: the changed-files card's read and the diff viewer's read.
 *
 * <p>The reads cover the routing the other session-scoped endpoints share
 * (blank id resolving to the active session, an explicit id, a path no turn
 * touched) plus the wire projection the viewer's discriminated union is typed
 * against — a text diff carries its hunks, while binary and oversized carry
 * only the notice.
 */
class GatewayChangesInteropTest {

    private static final String TOKEN = "t".repeat(48);
    private static final String TEXT_PATH = "src/App.java";

    /** The hunks a text diff carries: one replacement and one pure addition. */
    private static final List<StructuredPatchHunk> HUNKS = List.of(
        new StructuredPatchHunk(1, 3, 1, 3, List.of(
            " package app;", "-class Old {}", "+class New {}", " // tail")),
        new StructuredPatchHunk(9, 0, 10, 2, List.of(
            "+  void added() {}", "+}")));

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(20)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * A text diff carries its hunks with every offset the viewer draws from:
     * the two line-number bases, the two counts, and the verbatim lines whose
     * leading {@code +/-/ } is itself the contract.
     */
    @Test
    @Timeout(20)
    void readsATextDiffAsHunks() throws Exception {
        startServer(textPort());
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body)
                .contains("\"kind\":\"text\"")
                .contains("\"path\":\"" + TEXT_PATH + "\"")
                .contains("\"display\":\"" + TEXT_PATH + "\"")
                .contains("\"coarse\":false");
            // Every field of both hunks reaches the wire under its own name;
            // a viewer reading oldStart/newStart cannot be served by a flattened
            // pair, which is what this shape replaced.
            JsonUtils.getMapper().readTree(body).get("hunks").forEach(hunk ->
                assertThat(hunk.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("oldStart", "oldLines", "newStart", "newLines", "lines"));
        }
    }

    /** The parsed hunks are the port's own, not a reconstruction. */
    @Test
    @Timeout(20)
    void carriesTheHunksVerbatim() throws Exception {
        startServer(textPort());
        try (Response response = getDiff("")) {
            JsonNode hunks = JsonUtils.getMapper().readTree(response.body().string()).get("hunks");
            assertThat(hunks).hasSize(2);
            assertThat(hunks.get(0).get("oldStart").intValue()).isEqualTo(1);
            assertThat(hunks.get(0).get("newLines").intValue()).isEqualTo(3);
            assertThat(hunks.get(1).get("newStart").intValue()).isEqualTo(10);
            assertThat(hunks.get(1).get("lines").get(0).textValue()).isEqualTo("+  void added() {}");
        }
    }

    /**
     * A created file says so through the boolean flags rather than an absent
     * side: the viewer names "created" from {@code before=false}, and a null
     * side would instead read as a file whose content could not be loaded.
     */
    @Test
    @Timeout(20)
    void reportsACreatedFileThroughItsFlags() throws Exception {
        startServer(new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, false, true, HUNKS, false));
            }
            @Override public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return Optional.empty();
            }
        });
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"before\":false")
                .contains("\"after\":true");
        }
    }

    /** Binary and oversized files carry only their notice; there are no hunks. */
    @Test
    @Timeout(20)
    void readsBinariesAndOversizedFilesAsNotices() throws Exception {
        startServer(kindPort(FileDiff.Kind.BINARY));
        try (Response response = getDiff("")) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"kind\":\"binary\"");
            assertThat(body).doesNotContain("hunks");
        }

        startServer(kindPort(FileDiff.Kind.OVERSIZED));
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
                return Optional.of(FileDiff.text(sessionId, true, true, HUNKS, false));
            }
            @Override public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return Optional.empty();
            }
        });
        try (Response response = getDiff("&session_id=session-z")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string())
                .contains("\"path\":\"session-z\"");
        }
    }

    // ── changes.summary ──────────────────────────────────────────────────────

    /**
     * The summary carries the folded card's whole payload: the turn it belongs
     * to, the totals the header states, and one row per file with its own
     * counts.
     */
    @Test
    @Timeout(20)
    void readsATurnSummary() throws Exception {
        startServer(summaryPort());
        try (Response response = getSummary("&turn=2")) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body)
                .contains("\"turn\":2")
                .contains("\"total\":2")
                .contains("\"added\":14")
                .contains("\"deleted\":3")
                .contains("\"path\":\"" + TEXT_PATH + "\"")
                .contains("\"added\":11");
        }
    }

    /**
     * A binary or oversized row says so; a plain text row omits the flag rather
     * than writing {@code false}, so the payload carries only what the file is.
     */
    @Test
    @Timeout(20)
    void marksRefusedFilesInTheSummaryOnlyWhenTrue() throws Exception {
        startServer(summaryPort());
        try (Response response = getSummary("&turn=2")) {
            JsonNode files = JsonUtils.getMapper().readTree(response.body().string()).get("files");
            assertThat(files.get(0).has("binary")).isFalse();
            assertThat(files.get(1).get("binary").booleanValue()).isTrue();
        }
    }

    /** A non-numeric turn is a client error, not a lookup miss. */
    @Test
    @Timeout(20)
    void rejectsANonNumericTurn() throws Exception {
        startServer(summaryPort());
        try (Response response = getSummary("&turn=abc")) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"type\":\"invalid_request\"");
        }
    }

    /** Turn numbers are 1-based; zero addresses no turn at all. */
    @Test
    @Timeout(20)
    void rejectsATurnBelowOne() throws Exception {
        startServer(summaryPort());
        try (Response response = getSummary("&turn=0")) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"type\":\"invalid_request\"");
        }
    }

    /**
     * A turn whose files cannot be resolved is a 404: the card is only mounted
     * for a turn that has a summary, so an unresolved turn is a genuine miss
     * rather than an empty card.
     */
    @Test
    @Timeout(20)
    void answersNotFoundForAnUnresolvedTurn() throws Exception {
        startServer(GatewayFileDiffPort.none());
        try (Response response = getSummary("&turn=1")) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("\"type\":\"not_found\"");
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
        startServer(textPort());
        try (Response response = get("/api/changes.diff?path=%20")) {
            assertThat(response.code()).isEqualTo(400);
            assertThat(response.body().string()).contains("\"type\":\"invalid_request\"");
        }
    }

    /** The read requires the same bearer token as every other endpoint. */
    @Test
    @Timeout(20)
    void rejectsAnUnauthenticatedRead() throws Exception {
        startServer(textPort());
        Request request = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/changes.diff?path=" + TEXT_PATH)
            .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * A port serving {@link #HUNKS} for any path. The summary is left
     * unresolved: these cases address the diff read, and answering a summary
     * they never request would hide a mis-route that sent one to the other.
     */
    private static GatewayFileDiffPort textPort() {
        return new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.text(path, true, true, HUNKS, false));
            }
            @Override public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return Optional.empty();
            }
        };
    }

    /** A port answering the given refusal kind for any path. */
    private static GatewayFileDiffPort kindPort(FileDiff.Kind kind) {
        return new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.of(FileDiff.of(path, kind));
            }
            @Override public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return Optional.empty();
            }
        };
    }

    /** A port serving turn 2's summary: one text row and one binary row. */
    private static GatewayFileDiffPort summaryPort() {
        return new GatewayFileDiffPort() {
            @Override public Optional<FileDiff> diff(String sessionId, String path) {
                return Optional.empty();
            }
            @Override public Optional<ChangesSummary> summary(String sessionId, int turn) {
                if (turn != 2) return Optional.empty();
                return Optional.of(new ChangesSummary(2, List.of(
                    new ChangedFile(TEXT_PATH, TEXT_PATH, 11, 3, false, false),
                    new ChangedFile("assets/logo.png", "assets/logo.png", 3, 0, true, false)),
                    2, 14, 3));
            }
        };
    }

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

    /** A summary read; {@code extra} adds query parameters. */
    private Response getSummary(String extra) throws Exception {
        return get("/api/changes.summary?session_id=session-a" + extra);
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