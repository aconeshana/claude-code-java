package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostPermissionOptions;
import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * The shared-gate permission-mode broadcast, end to end through the gateway:
 * a change made on one surface must reach a mirror client (the webui chip)
 * as a live {@code permission.mode} frame, and the frame's payload must equal
 * what the polled {@code GET /api/session/permission-mode} answers — the two
 * reads of one state that a poll-only client could drift between.
 *
 * <p>The gate is the same shared instance the TUI writes through; the listener
 * registered here mirrors what the CLI composition root wires (publish the
 * gate's current state to the active session's mirror), so this test covers
 * the gateway half of that path.
 */
class PermissionModeBroadcastTest {

    private static final String TOKEN = "c".repeat(48);

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
    private final PermissionGate gate = new PermissionGate(
        ToolPermissionContext.of(Path.of("/work")));
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
    private Runnable unsubscribe;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();
    private final AtomicReference<CountDownLatch> mirrorOpened =
        new AtomicReference<>(new CountDownLatch(1));

    @AfterEach
    void tearDown() {
        if (unsubscribe != null) unsubscribe.run();
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /** A TUI-side change (Shift+Tab, /permission) reaches the mirror as a frame. */
    @Test
    @Timeout(20)
    void aGateChangePublishesTheNewModeToTheMirror() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);
        awaitMirrorReady();

        gate.setMode(PermissionMode.PLAN);

        String frame = pollUntil(events, f -> Strings.CS.contains(f, "permission.mode"));
        assertThat(frame).contains("\"session_id\":\"" + sessionId + "\"")
            .contains("\"permission_mode\":{")
            .contains("\"current\":\"plan\"");
        source.cancel();
    }

    /**
     * A webui POST changes the gate; the mirror then carries the same value the
     * GET endpoint reports, so a poll-only chip and a live chip agree.
     */
    @Test
    @Timeout(20)
    void aWebSelectionChangesTheGateAndPublishesTheSameStateTheGetterReports()
            throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);
        awaitMirrorReady();

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/permission-mode?token=" + TOKEN
                    + "&session_id=" + sessionId))
                .post(RequestBody.create(
                    "{\"session_id\":\"" + sessionId + "\",\"mode\":\"acceptEdits\"}",
                    MediaType.parse("application/json")))
                .build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }

        // The gate actually moved (the POST's own response already shows it).
        assertThat(gate.currentMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);

        String frame = pollUntil(events, f -> Strings.CS.contains(f, "permission.mode"));
        assertThat(frame).contains("\"permission_mode\":{")
            .contains("\"current\":\"acceptEdits\"");

        // The live frame and the polled body are one projection.
        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/session/permission-mode?token=" + TOKEN
                    + "&session_id=" + sessionId))
                .build()).execute()) {
            String body = response.body().string();
            assertThat(body).contains("\"current\":\"acceptEdits\"");
            assertThat(extractCurrent(frame)).isEqualTo(extractCurrent(body));
        }
        source.cancel();
    }

    /** Pulls `"current":"…"` out of a JSON blob for a shape-insensitive compare. */
    private static String extractCurrent(String json) {
        int index = json.indexOf("\"current\":\"");
        if (index < 0) return null;
        int start = index + "\"current\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

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

    private void awaitMirrorReady() throws InterruptedException {
        assertThat(mirrorOpened.get().await(5, TimeUnit.SECONDS))
            .as("mirror connection ready").isTrue();
    }

    private EventSource openMirror(LinkedBlockingQueue<String> events) {
        mirrorOpened.set(new CountDownLatch(1));
        Request.Builder builder = new Request.Builder()
            .url(url("/api/events?token=" + TOKEN + "&session_id=" + sessionId));
        return EventSources.createFactory(client).newEventSource(builder.build(),
            new EventSourceListener() {
                @Override public void onOpen(EventSource source, Response response) {
                    mirrorOpened.get().countDown();
                }

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
        GatewayPermissionModePort port = new GatewayPermissionModePort() {
            @Override public Optional<
                    SessionHostPermissionState> state(
                    String id) {
                return Optional.of(SessionHostPermissionOptions.build(gate));
            }

            @Override public SelectionResult selectMode(String id, String mode) {
                if (!SessionHostPermissionOptions.isSelectable(mode)) {
                    return SelectionResult.rejected("permission mode is not available");
                }
                if (!gate.trySetMode(PermissionGate.parseMode(mode))) {
                    return SelectionResult.rejected("Cannot set permission mode to "
                        + mode + " because the session was not launched with "
                        + "--dangerously-skip-permissions");
                }
                return SelectionResult.accepted(SessionHostPermissionOptions.build(gate));
            }
        };
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .headless(new GatewayHeadlessSessions() {})
            .permissionMode(port)
            .build();
        server.start();
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "a session", 3,
                Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
        registry.activateLocal(session);

        // Wire the gate's changes onto the mirror exactly as the CLI composition
        // root does, so the frame path under test is the production one.
        MirrorHub mirror = server.mirror();
        unsubscribe = gate.addModeListener(_ -> mirror.publishPermissionMode(
            sessionId, SessionHostPermissionOptions.build(gate)));
    }

    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}