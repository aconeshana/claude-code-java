package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionResolution;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interop tests for the permission interaction channel: a pending ask
 * projects into the mirror stream, the respond endpoint answers it
 * first-come against other observers, and the resolution projects back —
 * the two-observer model the TUI dialog and a web client share.
 */
class GatewayPermissionsInteropTest {

    private static final String TOKEN = "b".repeat(48);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
    private final InteractionCoordinator interactions = new InteractionCoordinator(
        () -> sessionId);
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
        interactions.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void pendingAskStreamsIntoTheMirrorAndWebRespondWins() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);
        awaitMirrorReady(events);

        // One ask raised on a virtual thread, like a blocked tool turn.
        CompletableFuture<PermissionAskCallback.Result> ask = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> ask.complete(interactions.ask(
            PermissionAskContext.simple("Bash",
                JSON.createObjectNode().put("command", "rm -rf build/"), "toolu_1"))));

        String askedFrame = pollUntil(events,
            f -> Strings.CS.contains(f, "permission.asked"));
        assertThat(askedFrame).contains("\"tool\":\"Bash\"")
            .contains("\"tool_use_id\":\"toolu_1\"")
            .contains("\"session_id\":\"" + sessionId + "\"");
        String requestId = requestIdOf(askedFrame);

        // The web client answers before the TUI observer would.
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create("""
                    {"request_id":"%s","session_id":"%s","allowed":true}
                    """.formatted(requestId, sessionId),
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"responded\":true");
        }

        PermissionAskCallback.Result result = ask.get(5, TimeUnit.SECONDS);
        assertThat(result.allowed()).isTrue();

        String resolvedFrame = pollUntil(events,
            f -> Strings.CS.contains(f, "permission.resolved"));
        assertThat(resolvedFrame).contains("\"request_id\":\"" + requestId + "\"")
            .contains("\"resolution\":\"allowed\"")
            .contains("\"origin\":\"web\"");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void firstAnswerWinsAndLateRespondIs409() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);
        awaitMirrorReady(events);

        CompletableFuture<PermissionAskCallback.Result> ask = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> ask.complete(interactions.ask(
            PermissionAskContext.simple("Bash", null, "toolu_2"))));
        String askedFrame = pollUntil(events,
            f -> Strings.CS.contains(f, "permission.asked"));
        String requestId = requestIdOf(askedFrame);

        // The TUI observer answers first (LOCAL endpoint, like the dialog).
        interactions.respond(InteractionFeatures.PERMISSION, requestId, sessionId,
            PermissionAskCallback.Result.denyWithFeedback("not safe"),
            InteractionEndpoint.LOCAL);

        PermissionAskCallback.Result result = ask.get(5, TimeUnit.SECONDS);
        assertThat(result.allowed()).isFalse();
        assertThat(result.feedback()).isEqualTo("not safe");

        // The web client's late answer finds no pending ask: 409.
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create("""
                    {"request_id":"%s","session_id":"%s","allowed":true}
                    """.formatted(requestId, sessionId),
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(409);
            assertThat(response.body().string()).contains("already_responded");
        }
        source.cancel();
    }

    @Test
    @Timeout(20)
    void askUserQuestionProjectsQuestionsAndWebAnswerFeedsBackInput() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);
        awaitMirrorReady(events);

        // An AskUserQuestion ask: the questions array must reach the web view.
        ObjectNode input = JSON.createObjectNode();
        input.putArray("questions").addObject()
            .put("question", "Which library?")
            .put("header", "Library")
            .put("multiSelect", false)
            .putArray("options").addObject()
                .put("label", "Jackson")
                .put("description", "databind");
        CompletableFuture<PermissionAskCallback.Result> ask = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> ask.complete(interactions.ask(
            PermissionAskContext.simple("AskUserQuestion", input, "toolu_q1"))));

        String askedFrame = pollUntil(events,
            f -> Strings.CS.contains(f, "permission.asked"));
        assertThat(askedFrame).contains("\"tool\":\"AskUserQuestion\"")
            .contains("\"questions\":[{\"question\":\"Which library?\"")
            .contains("\"label\":\"Jackson\"");
        String requestId = requestIdOf(askedFrame);

        // The web client answers with the rewritten input (the chosen option).
        ObjectNode updatedInput = JSON.createObjectNode();
        updatedInput.putArray("answers").addObject()
            .put("question", "Which library?")
            .put("answer", "Jackson");
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create(JSON.createObjectNode()
                        .put("request_id", requestId)
                        .put("session_id", sessionId)
                        .put("allowed", true)
                        .<ObjectNode>set("updated_input", updatedInput)
                        .toString(),
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }

        PermissionAskCallback.Result result = ask.get(5, TimeUnit.SECONDS);
        assertThat(result.allowed()).isTrue();
        assertThat(result.updatedInput()).isNotNull();
        assertThat(result.updatedInput().path("answers").get(0).path("answer").asText())
            .isEqualTo("Jackson");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void imAndGatewayRemotePresentersCoexistOnTheBroadcastList() throws Exception {
        // The remote endpoint is a broadcast list: the IM link's presenter and
        // the gateway's mirror presenter both receive every pending ask and
        // every resolution — /web starting beside a running Turn
        // Collaboration endpoint is the production shape.
        List<String> imAsked = new CopyOnWriteArrayList<>();
        List<String> imResolved = new CopyOnWriteArrayList<>();
        InteractionPresenter<PermissionAskContext, PermissionAskCallback.Result> imPresenter =
            new InteractionPresenter<>() {
                @Override public InteractionEndpoint endpoint() {
                    return InteractionEndpoint.REMOTE;
                }
                @Override public InteractionSupport support() {
                    return InteractionSupport.SUPPORTED;
                }
                @Override public boolean available(String id) { return true; }
                @Override public void present(InteractionRequest<
                        PermissionAskContext, PermissionAskCallback.Result> request) {
                    imAsked.add(request.payload().toolName());
                }
                @Override public void resolved(
                        InteractionResolution<PermissionAskCallback.Result> resolution) {
                    imResolved.add(resolution.result().allowed() ? "allow" : "deny");
                }
            };
        try (AutoCloseable ignored = interactions.register(
                InteractionFeatures.PERMISSION, imPresenter)) {
            startServer();  // coexists instead of throwing "already registered"

            LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
            EventSource source = openMirror(events);
            awaitMirrorReady(events);
            CompletableFuture<PermissionAskCallback.Result> ask = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> ask.complete(interactions.ask(
                PermissionAskContext.simple("Bash", null, "toolu_broadcast"))));

            // The ask reaches BOTH remote observers.
            String askedFrame = pollUntil(events,
                f -> Strings.CS.contains(f, "permission.asked"));
            assertThat(askedFrame).contains("\"tool\":\"Bash\"");
            assertThat(imAsked).containsExactly("Bash");

            // The web client answers; both observers see the resolution.
            String requestId = requestIdOf(askedFrame);
            try (Response response = client.newCall(new Request.Builder()
                    .url(url("/api/permissions/respond?token=" + TOKEN))
                    .post(RequestBody.create("""
                        {"request_id":"%s","session_id":"%s","allowed":true}
                        """.formatted(requestId, sessionId),
                        MediaType.parse("application/json")))
                    .build()).execute()) {
                assertThat(response.code()).isEqualTo(200);
            }
            assertThat(ask.get(5, TimeUnit.SECONDS).allowed()).isTrue();
            assertThat(imResolved).containsExactly("allow");
            String resolvedFrame = pollUntil(events,
                f -> Strings.CS.contains(f, "permission.resolved"));
            assertThat(resolvedFrame).contains("\"origin\":\"web\"");
            source.cancel();
        }
    }

    @Test
    @Timeout(20)
    void invalidRespondBodiesAreRejected() throws Exception {
        startServer();
        try (Response missing = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(missing.code()).isEqualTo(400);
        }
        try (Response badAllowed = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"request_id\":\"r\",\"session_id\":\"s\",\"allowed\":\"yes\"}",
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(badAllowed.code()).isEqualTo(400);
        }
        try (Response unknown = client.newCall(new Request.Builder()
                .url(url("/api/permissions/respond?token=" + TOKEN))
                .post(RequestBody.create(
                    "{\"request_id\":\"nope\",\"session_id\":\"" + sessionId
                        + "\",\"allowed\":true}",
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(unknown.code()).isEqualTo(409);
        }
    }

    /** Polls until a frame matching the predicate arrives; 5s cap per poll. */
    private String pollUntil(LinkedBlockingQueue<String> events,
            Predicate<String> matcher) throws InterruptedException {
        String frame = events.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("expected frame").isNotNull();
        while (!matcher.test(frame)) {
            frame = events.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("expected frame").isNotNull();
        }
        return frame;
    }

    /**
     * Waits until the mirror connection is live: the session-activation frame
     * (or any first frame) proves the SSE lane is subscribed, so a promptly
     * raised ask cannot race ahead of the subscription.
     */
    private void awaitMirrorReady(LinkedBlockingQueue<String> events)
            throws InterruptedException {
        String frame = events.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("mirror connection ready").isNotNull();
        if (!Strings.CS.contains(frame, "session.activated")) {
            // Any warm-up frame proves liveness; keep it for later matching.
            // (The activation frame may have been consumed before connect.)
        }
    }

    private static String requestIdOf(String askedFrame) {
        int index = askedFrame.indexOf("\"request_id\":\"");
        int start = index + "\"request_id\":\"".length();
        int end = askedFrame.indexOf('"', start);
        return askedFrame.substring(start, end);
    }

    private EventSource openMirror(LinkedBlockingQueue<String> events) {
        Request.Builder builder = new Request.Builder()
            .url(url("/api/events?token=" + TOKEN + "&session_id=" + sessionId));
        return EventSources.createFactory(client).newEventSource(builder.build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource source, String id, String type,
                        String data) {
                    events.add((type == null ? "" : type) + "|" + data);
                }
            });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private void startServer() throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN,
            registry, new GatewaySessionCatalogPort() {},
            new GatewayHeadlessSessions() {}, interactions);
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);
    }

    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
