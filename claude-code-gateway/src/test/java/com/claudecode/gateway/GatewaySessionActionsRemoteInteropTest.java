package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The four mutating routes as the standalone {@code claude web} gateway serves
 * them: wired with a port that cannot answer, against a transcript the gateway
 * does not own, over loopback HTTP.
 *
 * <p>This is the reported bug's own shape. The composition root that starts
 * that gateway passes the unmapped {@link GatewaySessionActionsPort} (it runs no
 * engine) while enabling remote session state, so rename, fork, archive and
 * delete each answered {@code 500 "… is not configured"} for a row the same
 * webui had just listed from the catalog.
 *
 * <p>The decorator in {@link TranscriptSessionActions} is what fixes it, but the
 * decorator only helps if {@code GatewayServer} installs it — the wiring is the
 * part that was missing, and a unit test of the decorator alone cannot see it.
 * These cases drive the real routes.
 */
class GatewaySessionActionsRemoteInteropTest {

    private static final String TOKEN = "c".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

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
        .retryOnConnectionFailure(false)
        .readTimeout(Duration.ofSeconds(30)).build();
    private GatewayServer server;
    private Path home;
    private String hostedId;
    private String foreignId;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory(Path.of("/tmp"), "gsar");
        hostedId = UUID.randomUUID().toString();
        foreignId = UUID.randomUUID().toString();
        // The gateway's own session, listed by the registry below, and one from
        // a process that already exited: both are rows in the same webui list.
        writeTranscript(hostedId);
        writeTranscript(foreignId);

        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .catalog(new GatewaySessionCatalogPort() {})
            .headless(new GatewayHeadlessSessions() {})
            // What the standalone `claude web` composition root passes.
            .actions(new GatewaySessionActionsPort() {})
            .webWorkers(new WebWorkerDirectory(home.resolve("web-workers")))
            .remoteSessionState(true)
            .build();
        server.start();

        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo(hostedId, "/work", "a session", 3, Instant.now(), "main"),
            new SessionEventHub(new SessionSink() {
                @Override public void onTurnStart(UserInput input) {}
                @Override public void onMessage(SDKMessage msg) {}
                @Override public void onError(Throwable error, boolean userCancel) {}
                @Override public void onTurnComplete(TurnOutcome outcome) {}
                @Override public void onIdle() {}
            }, _ -> {}),
            _ -> CompletableFuture.completedFuture(null)));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        deleteTree(home);
    }

    /**
     * A session whose owning process has already exited is still a row the webui
     * lists and still a row the user renames; it is also the case a forwarding
     * design would fail, because there is no owner left to forward to.
     */
    @Test
    @Timeout(20)
    void renameSucceedsForASessionNoProcessHosts() throws Exception {
        assertThat(post("/api/sessions/" + foreignId + "/rename?token=" + TOKEN,
                "{\"title\":\"tidied up\"}").code()).isEqualTo(200);

        assertThat(Files.readString(transcriptOf(foreignId)))
            .contains("\"customTitle\":\"tidied up\"");
    }

    @Test
    @Timeout(20)
    void archiveSucceedsForASessionThisProcessHosts() throws Exception {
        try (Response response = post("/api/sessions/" + hostedId + "/archive?token=" + TOKEN, "{}")) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(body.path("archived").asBoolean()).isTrue();
        }
        assertThat(Files.readString(transcriptOf(hostedId))).contains("\"archived\":true");
    }

    @Test
    @Timeout(20)
    void forkReturnsAChildSessionIdThatNamesANewTranscript() throws Exception {
        try (Response response = post("/api/sessions/" + foreignId + "/fork?token=" + TOKEN, "{}")) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(response.body().string());
            String child = body.path("session_id").asText();
            assertThat(child).isNotEmpty().isNotEqualTo(foreignId);
            assertThat(transcriptOf(child)).exists();
        }
    }

    @Test
    @Timeout(20)
    void deleteRemovesTheTranscriptAndAnswersDeleted() throws Exception {
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + foreignId + "?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(body.path("deleted").asBoolean()).isTrue();
        }
        assertThat(transcriptOf(foreignId)).doesNotExist();
    }

    /**
     * The distinction the handler cannot recover from types has to survive the
     * decorator: an id no transcript answers to is a 404, not the 500 the bug
     * reported, and not a 500 that hides a missing row behind "try again".
     */
    @Test
    @Timeout(20)
    void anUnresolvableIdIs404OnAllFourRoutes() throws Exception {
        String missing = UUID.randomUUID().toString();

        try (Response response = post("/api/sessions/" + missing + "/rename?token=" + TOKEN,
                "{\"title\":\"x\"}")) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(JsonUtils.getMapper().readTree(response.body().string())
                .path("error").path("type").asText()).isEqualTo("not_found");
        }
        try (Response response = post("/api/sessions/" + missing + "/fork?token=" + TOKEN, "{}")) {
            assertThat(response.code()).isEqualTo(404);
        }
        try (Response response = post("/api/sessions/" + missing + "/archive?token=" + TOKEN, "{}")) {
            assertThat(response.code()).isEqualTo(404);
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + missing + "?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(response.code()).isEqualTo(404);
        }
    }

    /** The exact symptom: none of the four may report itself unconfigured. */
    @Test
    @Timeout(20)
    void noRouteReportsItselfUnconfigured() throws Exception {
        for (String action : List.of("rename", "fork", "archive")) {
            String payload = Strings.CS.equals("rename", action) ? "{\"title\":\"x\"}" : "{}";
            try (Response response = post("/api/sessions/" + foreignId + "/" + action
                    + "?token=" + TOKEN, payload)) {
                assertThat(response.body().string()).doesNotContain("is not configured");
            }
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + foreignId + "?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(response.body().string()).doesNotContain("is not configured");
        }
    }

    // ------------------------------------------------------------ fixtures

    private Response post(String path, String body) throws IOException {
        return client.newCall(new Request.Builder().url(url(path))
            .post(RequestBody.create(body, JSON)).build()).execute();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private Path writeTranscript(String sessionId) throws IOException {
        Path project = Files.createDirectories(home.resolve("projects").resolve("-work"));
        Path transcript = project.resolve(sessionId + ".jsonl");
        String user = UUID.randomUUID().toString();
        String assistant = UUID.randomUUID().toString();
        Files.writeString(transcript,
            "{\"type\":\"user\",\"uuid\":\"" + user + "\",\"parentUuid\":null,\"sessionId\":\""
                + sessionId + "\",\"message\":{\"role\":\"user\",\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-01T00:00:00Z\"}\n"
                + "{\"type\":\"assistant\",\"uuid\":\"" + assistant + "\",\"parentUuid\":\""
                + user + "\",\"sessionId\":\"" + sessionId + "\",\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
                + "\"timestamp\":\"2026-01-01T00:00:01Z\"}\n",
            StandardCharsets.UTF_8);
        return transcript;
    }

    private Path transcriptOf(String sessionId) {
        return home.resolve("projects").resolve("-work").resolve(sessionId + ".jsonl");
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}