package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Interop tests for the headless-session routing and lifecycle endpoints: the
 * open/close REST surface, {@code metadata.session_id} routing on the protocol
 * faces, and the shared per-session in-flight guard.
 */
class HeadlessSessionsInteropTest {

    private static final String TOKEN = "h".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    /** Scripted headless sessions: every open mints a session with turn body. */
    private static final class ScriptedHeadless implements GatewayHeadlessSessions {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicReference<Consumer<SessionEventHub>> turnBody =
            new AtomicReference<>(_ -> {});
        final ConcurrentMap<String, SessionEventHub> hubs =
            new ConcurrentHashMap<>();

        @Override
        public Optional<SessionHostSession> find(String sessionId) {
            SessionEventHub hub = hubs.get(sessionId);
            if (hub == null) return Optional.empty();
            return Optional.of(session(sessionId, hub));
        }

        @Override
        public Opened open(OpenRequest request) {
            String id = StringUtils.isBlank(request.sessionId())
                ? UUID.randomUUID().toString() : request.sessionId();
            SessionEventHub hub = hubs.computeIfAbsent(id, _ ->
                new SessionEventHub(new NoopSink(), _ -> {}));
            opened.incrementAndGet();
            return new Opened(session(id, hub), request.projectPath(), false);
        }

        @Override
        public List<SessionListing> list() {
            return hubs.keySet().stream().sorted()
                .map(id -> new SessionListing(id, "/work", Instant.EPOCH))
                .toList();
        }

        @Override
        public boolean close(String sessionId) {
            return hubs.remove(sessionId) != null;
        }

        private SessionHostSession session(String id, SessionEventHub hub) {
            return new SessionHostSession(
                new SessionHostInfo(id, "/work", "", 0, Instant.now(), ""),
                hub,
                submission -> {
                    hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                        null, "default"));
                    turnBody.get().accept(hub);
                    return CompletableFuture.completedFuture(null);
                });
        }
    }

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletionStage<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    private final ScriptedHeadless headless = new ScriptedHeadless();
    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private void startServer() throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0),
            TOKEN, registry, new GatewaySessionCatalogPort() {}, headless);
        server.start();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    @Test
    @Timeout(20)
    void openMintsSessionAndCloseRemovesIt() throws Exception {
        startServer();
        String openBody = "{\"session_id\":\"ws-1\",\"project_path\":\"/work\"}";
        try (Response response = post("/api/sessions/open", openBody)) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"session_id\":\"ws-1\"")
                .contains("\"project_path\":\"/work\"")
                .contains("\"headless\":true");
        }
        assertThat(headless.find("ws-1")).isPresent();

        try (Response response = post("/api/sessions/close", "{\"session_id\":\"ws-1\"}")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"closed\":true");
        }
        assertThat(headless.find("ws-1")).isEmpty();
        // Closing an unknown session is 404.
        try (Response response = post("/api/sessions/close", "{\"session_id\":\"ws-1\"}")) {
            assertThat(response.code()).isEqualTo(404);
        }
    }

    @Test
    @Timeout(20)
    void closeWithoutSessionIdIsRejected() throws Exception {
        startServer();
        try (Response response = post("/api/sessions/close", "{}")) {
            assertThat(response.code()).isEqualTo(400);
        }
    }

    @Test
    @Timeout(20)
    void protocolRoutesToHeadlessSessionById() throws Exception {
        startServer();
        post("/api/sessions/open", "{\"session_id\":\"ws-route\"}");
        headless.turnBody.set(hub -> {
            hub.onMessage(textMessage("routed to headless"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 1L, null, null, null, null));
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = ssePost("/v1/messages", """
            {"model":"claude-sonnet-5","stream":true,
             "metadata":{"session_id":"ws-route"},
             "messages":[{"role":"user","content":"hello"}]}
            """, events);

        pollUntil(events, "message_start");
        pollUntil(events, "routed to headless");
        pollUntil(events, "message_stop");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void unknownSessionIdIs404() throws Exception {
        startServer();
        try (Response response = post("/v1/messages", """
            {"model":"claude-sonnet-5","stream":true,
             "metadata":{"session_id":"nope"},
             "messages":[{"role":"user","content":"hello"}]}
            """)) {
            assertThat(response.code()).isEqualTo(404);
            assertThat(response.body().string()).contains("unknown session");
        }
    }

    @Test
    @Timeout(20)
    void concurrentHeadlessSessionsAreNotBlockedByEachOther() throws Exception {
        startServer();
        post("/api/sessions/open", "{\"session_id\":\"ws-a\"}");
        post("/api/sessions/open", "{\"session_id\":\"ws-b\"}");
        // ws-a's turn never completes: its in-flight slot must not gate ws-b.
        CountDownLatch aStarted = new CountDownLatch(1);
        headless.turnBody.set(hub -> {
            hub.onMessage(textMessage("a started"));
            aStarted.countDown();
            // No onTurnComplete: the turn stays in flight.
        });
        List<String> aEvents = new CopyOnWriteArrayList<>();
        EventSource a = ssePost("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"ws-a"},
             "messages":[{"role":"user","content":"a"}]}
            """, aEvents);
        pollUntil(aEvents, "a started");
        assertThat(aStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // ws-b still streams a full turn while ws-a is in flight.
        headless.turnBody.set(hub -> {
            hub.onMessage(textMessage("b done"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 1L, null, null, null, null));
        });
        List<String> bEvents = new CopyOnWriteArrayList<>();
        EventSource b = ssePost("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"ws-b"},
             "messages":[{"role":"user","content":"b"}]}
            """, bEvents);
        pollUntil(bEvents, "b done");
        pollUntil(bEvents, "message_stop");
        a.cancel();
        b.cancel();
    }

    @Test
    @Timeout(20)
    void busySessionReturnsProtocolErrorWhileTurnInFlight() throws Exception {
        startServer();
        post("/api/sessions/open", "{\"session_id\":\"ws-busy\"}");
        headless.turnBody.set(hub -> {
            hub.onMessage(textMessage("busy started"));
            // No onTurnComplete: the turn stays in flight.
        });
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = ssePost("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"ws-busy"},
             "messages":[{"role":"user","content":"first"}]}
            """, events);
        pollUntil(events, "busy started");

        try (Response second = post("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"ws-busy"},
             "messages":[{"role":"user","content":"second"}]}
            """)) {
            assertThat(second.code()).isEqualTo(429);
            assertThat(second.body().string()).contains("already in flight");
        }
        source.cancel();
    }

    @Test
    @Timeout(20)
    void catalogAnnotatesOpenHeadlessSessions() throws Exception {
        // The two-level /api/sessions catalog carries the full ProjectCatalog
        // listing; open headless sessions are annotated on their rows.
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN,
            registry,
            new GatewaySessionCatalogPort() {
                @Override public List<ProjectEntry> listProjects() {
                    return List.of(new ProjectEntry(
                        "/work", "work", 2, 1_700_000_000_000L,
                        List.of(
                            new SessionEntry("ws-a", "a session", 3, 1_700_000_000_000L,
                                "main", "/work", "", ""),
                            new SessionEntry("sess-disk", "disk only", 8, 1_699_000_000_000L,
                                "main", "/work", "", ""))));
                }
            },
            headless);
        server.start();
        post("/api/sessions/open", "{\"session_id\":\"ws-a\"}");
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN))
                .get().build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"projects\":");
            // The open session is annotated; a disk-only one is not.
            assertThat(body).contains("\"headless_open\":true");
            assertThat(body).contains("ws-a").contains("sess-disk");
        }
        // A closed session loses the annotation.
        post("/api/sessions/close", "{\"session_id\":\"ws-a\"}");
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN))
                .get().build()).execute()) {
            assertThat(response.body().string()).doesNotContain("\"headless_open\":true");
        }
    }

    @Test
    @Timeout(20)
    void headlessTurnsStreamIntoTheMirror() throws Exception {
        startServer();
        // The mirror follows every open session: a headless turn's events
        // appear on /api/events with the headless session's id.
        SessionEventHub activeEvents = new SessionEventHub(new NoopSink(), _ -> {});
        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo("active-1", "/work", "", 0, Instant.now(), ""),
            activeEvents, _ -> CompletableFuture.completedFuture(null)));
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource mirror = EventSources.createFactory(client)
            .newEventSource(new Request.Builder()
                .url(url("/api/events?token=" + TOKEN))
                .build(),
                new EventSourceListener() {
                    @Override public void onEvent(EventSource source, String id,
                            String type, String data) {
                        events.add((type == null ? "" : type) + "|" + data);
                    }
                });
        // Prove the mirror stream is live before running the headless turn:
        // wait for the connection.ready marker before publishing, then one
        // frame from the active session must arrive first.
        assertThat(events.poll(5, TimeUnit.SECONDS)).startsWith("connection.ready|");
        activeEvents.onTurnStart(UserInput.of("warmup", "warmup", null, "default"));
        String warmupFrame = events.poll(5, TimeUnit.SECONDS);
        // The active session's activation notice may arrive ahead of it.
        if (warmupFrame != null && Strings.CS.contains(warmupFrame, "session.activated")) {
            warmupFrame = events.poll(5, TimeUnit.SECONDS);
        }
        assertThat(warmupFrame).contains("warmup");

        post("/api/sessions/open", "{\"session_id\":\"ws-mirror\"}");
        headless.turnBody.set(hub -> {
            hub.onMessage(textMessage("headless work"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 1L, null, null, null, null));
        });
        List<String> turn = new CopyOnWriteArrayList<>();
        EventSource source = ssePost("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"ws-mirror"},
             "messages":[{"role":"user","content":"go"}]}
            """, turn);
        // The headless turn's text frame arrives on the mirror and names the
        // session it belongs to.
        boolean attributed = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = events.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && Strings.CS.contains(frame, "headless work")) {
                assertThat(frame).contains("\"session_id\":\"ws-mirror\"");
                attributed = true;
                break;
            }
        }
        assertThat(attributed).isTrue();
        source.cancel();
        mirror.cancel();
    }

    @Test
    @Timeout(20)
    void activeSessionIdAddressesTheActiveSession() throws Exception {
        // A web client sees the active TUI session's id in /api/sessions; the
        // same id in metadata.session_id must reach that session, not 404.
        startServer();
        SessionEventHub activeEvents = new SessionEventHub(new NoopSink(), _ -> {});
        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo("active-1", "/work", "", 0, Instant.now(), ""),
            activeEvents, submission -> {
                activeEvents.onTurnStart(UserInput.of(submission.prompt(),
                    submission.prompt(), null, "default"));
                activeEvents.onMessage(textMessage("turn on the active session"));
                activeEvents.onTurnComplete(new TurnOutcome(
                    false, false, false, false, false, 1L, null, null, null, null));
                return CompletableFuture.completedFuture(null);
            }));
        List<String> events = new CopyOnWriteArrayList<>();
        EventSource source = ssePost("/v1/messages", """
            {"model":"m","stream":true,"metadata":{"session_id":"active-1"},
             "messages":[{"role":"user","content":"hello"}]}
            """, events);
        pollUntil(events, "turn on the active session");
        pollUntil(events, "message_stop");
        source.cancel();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Response post(String path, String body) throws IOException {
        return client.newCall(new Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(body, JSON))
            .build()).execute();
    }

    private EventSource ssePost(String path, String body, List<String> events) {
        return EventSources.createFactory(client).newEventSource(
            new Request.Builder()
                .url(url(path))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(body, JSON))
                .build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource source, String id,
                        String type, String data) {
                    events.add((type == null ? "" : type) + "|" + data);
                }
            });
    }

    private static void pollUntil(List<String> events, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (events) {
                for (String frame : events) {
                    if (Strings.CS.contains(frame, expected)) return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("frame containing '" + expected + "' did not arrive");
    }

    private static SDKMessage.Assistant textMessage(String text) {
        return new SDKMessage.Assistant(
            new AssistantMessage(UUID.randomUUID().toString(),
                new AssistantContent(null, List.of(new TextBlock(text)), null)),
            Usage.EMPTY, "claude-sonnet-5");
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
