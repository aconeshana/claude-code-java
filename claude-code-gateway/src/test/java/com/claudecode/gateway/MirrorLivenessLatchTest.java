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
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pins the mirror-liveness contract the permission interop tests depend on: a
 * subscriber proves its lane is usable from the server's own {@code
 * connection.ready} marker, never from waiting for a semantic frame.
 *
 * <p>Why this needs its own test: {@code session.activated} is published during
 * {@code startServer}, before the subscriber connects, and a fresh subscriber
 * starts live at the journal tip rather than replaying the ring — the comments
 * at {@code GatewayServer}'s lane setup say so explicitly. So a subscriber that
 * connects after activation receives no semantic frame until the first real
 * event. A readiness helper that waits for one passes on an unloaded machine
 * (by the time it looks, an event has arrived) and hangs when the machine is
 * busy — a load-dependent failure seen as a later missing {@code
 * permission.asked}. This test drives a connect cycle with nothing published
 * and asserts the marker arrives regardless.
 */
class MirrorLivenessLatchTest {

    private static final String TOKEN = "c".repeat(48);

    private final String sessionId = UUID.randomUUID().toString();
    private final SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by this test");
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
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * The readiness marker arrives on every connection with no semantic event
     * published, which is what makes it a sound liveness signal. Repeated so a
     * single lucky ordering cannot pass it.
     */
    @Test
    @Timeout(40)
    void connectionReadyMarkerArrivesWithNoSemanticFramePublished() throws Exception {
        for (int cycle = 0; cycle < 5; cycle++) {
            startServer();
            LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
            EventSource source = openMirror(events);

            String first = events.poll(5, TimeUnit.SECONDS);
            assertThat(first)
                .as("cycle %d: the lane must announce itself without any published event", cycle)
                .isNotNull();
            assertThat(first)
                .as("cycle %d: readiness is the server's own marker", cycle)
                .contains("connection.ready");

            source.cancel();
            server.close();
            server = null;
        }
    }

    /**
     * The activation frame published at start never reaches a later subscriber:
     * a fresh lane starts at the journal tip. This is the fact that made the old
     * "wait for any frame" helper hang, so it is pinned here rather than left as
     * folklore.
     */
    @Test
    @Timeout(30)
    void activationFrameIsNotReplayedToALaterSubscriber() throws Exception {
        startServer();
        // startServer publishes session.activated with no subscriber attached.
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = openMirror(events);

        String first = events.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first)
            .as("the first frame is the readiness marker, not the earlier activation")
            .contains("connection.ready");
        assertThat(first).doesNotContain("session.activated");

        // Nothing else follows until a real event is published.
        assertThat(events.poll(300, TimeUnit.MILLISECONDS))
            .as("no replayed activation frame should trail the marker")
            .isNull();

        source.cancel();
    }

    private EventSource openMirror(LinkedBlockingQueue<String> events) {
        return EventSources.createFactory(client).newEventSource(
            new Request.Builder()
                .url("http://127.0.0.1:" + server.port()
                    + "/api/events?token=" + TOKEN + "&session_id=" + sessionId)
                .build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource s, String id, String type,
                        String data) {
                    events.add((type == null ? "" : type) + "|" + data);
                }
            });
    }

    private void startServer() throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN,
            registry, new GatewaySessionCatalogPort() {},
            new GatewayHeadlessSessions() {}, null);
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