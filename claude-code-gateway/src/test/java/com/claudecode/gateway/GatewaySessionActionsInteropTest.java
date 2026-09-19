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
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * The session row menu's mutating routes over loopback HTTP, covering the two
 * properties their status codes carry.
 *
 * <p><b>Async routes stay answerable.</b> These four run off the exchange
 * thread, which puts them outside the synchronous guard wrapping
 * {@code route()}. A failure there must still reach the wire as a 500 body:
 * {@code com.sun.net.httpserver} answers a throwing handler by closing the
 * socket unwritten, which a browser reports as {@code ERR_EMPTY_RESPONSE} and
 * {@code fetch()} rejects as a bare {@code TypeError} — indistinguishable from
 * a stopped gateway, so none of the webui's per-request error rendering runs.
 *
 * <p><b>Not-found and failed are different answers.</b> A write that fails on
 * a session that provably exists is a 500 naming the failure, never the 404
 * that tells a client the row is gone; an id nothing answers to is a 404, and
 * deleting an already-deleted session is idempotent rather than an error.
 */
class GatewaySessionActionsInteropTest {

    private static final String TOKEN = "c".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private final String activeSessionId = UUID.randomUUID().toString();
    private final String sessionId = UUID.randomUUID().toString();
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
        // No transparent retry: a route that drops the connection instead of
        // answering must surface as a failed call here, the way it does in a
        // browser. With retries on, OkHttp can replay the request and hide
        // exactly the dead-connection regression these tests exist to catch.
        .retryOnConnectionFailure(false)
        .readTimeout(Duration.ofSeconds(30)).build();
    private GatewayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * The regression for the close discipline: the async route bodies once ran
     * inside {@code try (exchange)}, whose implicit finally closed the exchange
     * before the catch that writes the 500 — so the fallback targeted a dead
     * socket and the client got nothing at all. The assertion that matters is
     * not the status alone but that a body came back with it.
     *
     * <p>Driven through the listing route, because that is where a plain
     * {@code RuntimeException} actually reaches the route's fallback: the four
     * action handlers catch {@code RuntimeException} themselves and map it to a
     * status, so for those the escaping-throwable case is the {@code Error}
     * below.
     */
    @Test
    @Timeout(20)
    void anAsyncHandlerThrowingAnExceptionStillWritesA500Body() throws Exception {
        startServer(new FakeActions(), new GatewaySessionCatalogPort() {
            @Override public List<ProjectEntry> listProjects(int perProjectLimit) {
                throw new IllegalStateException("project index unreadable");
            }
        });

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(500);
            String body = response.body().string();
            assertThat(body).isNotEmpty();
            JsonNode error = JsonUtils.getMapper().readTree(body).path("error");
            assertThat(error.path("type").asText()).isEqualTo("api_error");
            assertThat(error.path("message").asText()).contains("project index unreadable");
        }
    }

    /**
     * Rename, fork, archive and delete all share the one fallback, and all four
     * used to catch {@code RuntimeException} and nothing else. An {@code Error}
     * is what that gap let through, and it is the failure the guard was written
     * for: in a native image a handler reaching an unregistered reflective type
     * throws {@code MissingReflectionRegistrationError}.
     */
    @Test
    @Timeout(20)
    void anAsyncHandlerThrowingAnErrorStillProducesAResponseOnEveryAction() throws Exception {
        FakeActions actions = new FakeActions();
        actions.failure = new Error("cannot reflectively instantiate the array class");
        startServer(actions);

        for (Request request : List.of(
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/rename?token=" + TOKEN))
                    .post(RequestBody.create("{\"title\":\"renamed\"}", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/fork?token=" + TOKEN))
                    .post(RequestBody.create("{}", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/archive?token=" + TOKEN))
                    .post(RequestBody.create("", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "?token=" + TOKEN))
                    .delete().build())) {
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.code()).as(request.url().encodedPath()).isEqualTo(500);
                JsonNode error = JsonUtils.getMapper()
                    .readTree(response.body().string()).path("error");
                assertThat(error.path("type").asText()).isEqualTo("api_error");
                assertThat(error.path("message").asText())
                    .contains("cannot reflectively instantiate the array class");
            }
        }
    }

    /**
     * A disk-full transcript append arrives as an unchecked I/O wrapper — a
     * {@code RuntimeException} like any other, which is why the handler cannot
     * infer not-found from the exception type. Answering 404 here would tell
     * the user their conversation is gone when it is intact and merely
     * unwritable.
     */
    @Test
    @Timeout(20)
    void aWriteFailureOnAnExistingSessionIsA500NotA404() throws Exception {
        FakeActions actions = new FakeActions();
        actions.failure = new UncheckedIOException(
            new IOException("No space left on device"));
        startServer(actions);

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/rename?token=" + TOKEN))
                .post(RequestBody.create("{\"title\":\"renamed\"}", JSON)).build()).execute()) {
            assertThat(response.code()).isEqualTo(500);
            JsonNode error = JsonUtils.getMapper()
                .readTree(response.body().string()).path("error");
            assertThat(error.path("type").asText()).isEqualTo("api_error");
            assertThat(error.path("message").asText()).contains("No space left on device");
            assertThat(error.path("message").asText()).doesNotContain("unknown session");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/archive?token=" + TOKEN))
                .post(RequestBody.create("", JSON)).build()).execute()) {
            assertThat(response.code()).isEqualTo(500);
            assertThat(JsonUtils.getMapper().readTree(response.body().string())
                .path("error").path("message").asText()).contains("No space left on device");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/fork?token=" + TOKEN))
                .post(RequestBody.create("{}", JSON)).build()).execute()) {
            assertThat(response.code()).isEqualTo(500);
            assertThat(JsonUtils.getMapper().readTree(response.body().string())
                .path("error").path("message").asText()).contains("No space left on device");
        }
    }

    /** An id nothing answers to keeps the documented 404 across all four routes. */
    @Test
    @Timeout(20)
    void anUnknownSessionIsA404OnEveryAction() throws Exception {
        FakeActions actions = new FakeActions();
        actions.failure = new GatewaySessionActionsPort.UnknownSessionException(
            "Session " + sessionId + " not found in any project directory");
        startServer(actions);

        for (Request request : List.of(
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/rename?token=" + TOKEN))
                    .post(RequestBody.create("{\"title\":\"renamed\"}", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/fork?token=" + TOKEN))
                    .post(RequestBody.create("{}", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "/archive?token=" + TOKEN))
                    .post(RequestBody.create("", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/" + sessionId + "?token=" + TOKEN))
                    .delete().build())) {
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.code()).as(request.url().encodedPath()).isEqualTo(404);
                JsonNode error = JsonUtils.getMapper()
                    .readTree(response.body().string()).path("error");
                assertThat(error.path("type").asText()).isEqualTo("not_found");
                assertThat(error.path("message").asText()).contains(sessionId);
            }
        }
    }

    /**
     * Two webui tabs on the same listing race: the loser clicks Delete on a row
     * the winner already removed. The port answers {@code false} for an absent
     * session, and that must surface as the 404 the client renders as an
     * already-gone row — not the 500 that raises an error banner.
     */
    @Test
    @Timeout(20)
    void deletingAnAbsentSessionIs404NotAnErrorBanner() throws Exception {
        FakeActions actions = new FakeActions();
        actions.deleted = false;
        startServer(actions);

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(response.code()).isEqualTo(404);
            JsonNode error = JsonUtils.getMapper()
                .readTree(response.body().string()).path("error");
            assertThat(error.path("type").asText()).isEqualTo("not_found");
            assertThat(error.path("message").asText()).contains(sessionId);
        }
    }

    /** The happy paths still answer 200 with their documented bodies. */
    @Test
    @Timeout(20)
    void successfulActionsAnswerTheirDocumentedBodies() throws Exception {
        startServer(new FakeActions());

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "/rename?token=" + TOKEN))
                .post(RequestBody.create("{\"title\":\"renamed\"}", JSON)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(body.path("session_id").asText()).isEqualTo(sessionId);
            assertThat(body.path("custom_title").asText()).isEqualTo("renamed");
        }
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/" + sessionId + "?token=" + TOKEN))
                .delete().build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(response.body().string());
            assertThat(body.path("session_id").asText()).isEqualTo(sessionId);
            assertThat(body.path("deleted").asBoolean()).isTrue();
        }
    }

    /** A malformed id reaches the handler as an argument failure: 400, not 404 or 500. */
    @Test
    @Timeout(20)
    void anInvalidSessionIdIsA400OnEveryAction() throws Exception {
        FakeActions actions = new FakeActions();
        actions.failure = new IllegalArgumentException("Invalid sessionId: not-a-uuid");
        startServer(actions);

        for (Request request : List.of(
                new Request.Builder().url(url("/api/sessions/not-a-uuid/rename?token=" + TOKEN))
                    .post(RequestBody.create("{\"title\":\"renamed\"}", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/not-a-uuid/archive?token=" + TOKEN))
                    .post(RequestBody.create("", JSON)).build(),
                new Request.Builder().url(url("/api/sessions/not-a-uuid?token=" + TOKEN))
                    .delete().build())) {
            try (Response response = client.newCall(request).execute()) {
                assertThat(response.code()).as(request.url().encodedPath()).isEqualTo(400);
                assertThat(JsonUtils.getMapper().readTree(response.body().string())
                    .path("error").path("type").asText()).isEqualTo("invalid_request");
            }
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A port double that answers, or throws whatever the test planted.
     *
     * <p>{@code failure} throws from inside the port call. The three action
     * handlers catch {@code RuntimeException} themselves and map it to a
     * status, so only an {@code Error} planted here escapes to the route's own
     * fallback — which is exactly the gap this suite covers, since the async
     * routes used to catch {@code RuntimeException} and nothing else.
     */
    private static final class FakeActions implements GatewaySessionActionsPort {
        Throwable failure;
        boolean deleted = true;

        private void maybeFail() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }

        @Override public void rename(String sessionId, String title) {
            maybeFail();
        }

        @Override public ForkResult fork(String sessionId, String title) {
            maybeFail();
            return new ForkResult(UUID.randomUUID().toString());
        }

        @Override public void archive(String sessionId) {
            maybeFail();
        }

        @Override public boolean delete(String sessionId) {
            maybeFail();
            return deleted;
        }
    }

    private void startServer(GatewaySessionActionsPort actions) throws IOException {
        startServer(actions, new GatewaySessionCatalogPort() {});
    }

    private void startServer(GatewaySessionActionsPort actions,
            GatewaySessionCatalogPort catalog) throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry,
            catalog, new GatewayHeadlessSessions() {}, null,
            new GatewaySessionMessagesPort() {}, new GatewaySettingsPort() {},
            new GatewaySchedulePort() {}, new GatewayModelsPort() {},
            new GatewayCommandsPort() {}, new GatewaySessionContextPort() {}, actions);
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
