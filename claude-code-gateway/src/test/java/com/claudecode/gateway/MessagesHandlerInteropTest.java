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
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
 * Interop tests for the Messages protocol face over real loopback HTTP.
 * The submitted turn runs against a scripted session hub so the full chain —
 * parse, idempotency, subscription, translation, SSE framing — is exercised
 * exactly as an external client sees it.
 */
class MessagesHandlerInteropTest {

    private static final String TOKEN = "b".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private final String sessionId = UUID.randomUUID().toString();
    private final RecordingPrimarySink primary = new RecordingPrimarySink();
    private final SessionEventHub hub = new SessionEventHub(primary, _ -> {});
    private final List<String> submittedPrompts = new CopyOnWriteArrayList<>();
    private final AtomicInteger submitCount = new AtomicInteger();

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
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void streamsOneTurnAsAnthropicFramesAndStopsAtMessageStop() throws Exception {
        startServer();
        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();

        // The scripted session: when the submission arrives, run one turn
        // synchronously on the submitting thread.
        installSession(submission -> {
            hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                null, "default"));
            hub.onMessage(textMessage("Hello via gateway"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 4L, null, null, null, null));
            return CompletableFuture.completedFuture(null);
        });

        EventSource source = postMessages(
            "{\"model\":\"claude-sonnet-5\",\"max_tokens\":1024,\"stream\":true,"
                + "\"metadata\":{\"user_id\":\"retry-key-1\"},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"say hi\"}]}",
            events);

        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("message_start");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("content_block_start");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("Hello via gateway");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("content_block_stop");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("message_delta");
        assertThat(events.poll(5, TimeUnit.SECONDS)).contains("message_stop");
        source.cancel();
    }

    @Test
    @Timeout(20)
    void retriedPostWithSameIdempotencyKeySubmitsOnlyOnce() throws Exception {
        startServer();
        installSession(submission -> {
            submitCount.incrementAndGet();
            hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                null, "default"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 1L, null, null, null, null));
            return CompletableFuture.completedFuture(null);
        });

        String requestBody = "{\"model\":\"m\",\"max_tokens\":1,\"stream\":true,"
            + "\"metadata\":{\"user_id\":\"same-key\"},"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"once\"}]}";
        // First POST streams the turn to completion.
        LinkedBlockingQueue<String> first = new LinkedBlockingQueue<>();
        EventSource s1 = postMessages(requestBody, first);
        pollUntil(first, "message_stop");
        s1.cancel();
        // Retry with the same key: the ledger short-circuits; the turn is not
        // re-run. The retry still opens a stream with its own message_start
        // shell, but no turn content frames ever arrive on it.
        LinkedBlockingQueue<String> retry = new LinkedBlockingQueue<>();
        EventSource s2 = postMessages(requestBody, retry);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = retry.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) break;
            assertThat(frame).as("retry must not replay turn content")
                .doesNotContain("content_block")
                .doesNotContain("message_stop");
        }
        assertThat(submitCount.get()).isEqualTo(1);
        s2.cancel();
    }

    @Test
    @Timeout(20)
    void turnInFlightRejectsConcurrentSubmissionWith429() throws Exception {
        startServer();
        CompletableFuture<Void> release = new CompletableFuture<>();
        installSession(submission -> {
            submitCount.incrementAndGet();
            hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                null, "default"));
            // Hold the turn open until the test releases it.
            return release.thenApply(_ -> null);
        });

        LinkedBlockingQueue<String> first = new LinkedBlockingQueue<>();
        EventSource s1 = postMessages(
            "{\"model\":\"m\",\"max_tokens\":1,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"first\"}]}",
            first);
        awaitSubmission();

        try (Response second = client.newCall(new Request.Builder()
                .url(url("/v1/messages"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(
                    "{\"model\":\"m\",\"max_tokens\":1,\"stream\":true,"
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"second\"}]}",
                    JSON))
                .build()).execute()) {
            assertThat(second.code()).isEqualTo(429);
            assertThat(second.body().string()).contains("already in flight");
        }

        release.complete(null);
        hub.onTurnComplete(new TurnOutcome(
            false, false, false, false, false, 1L, null, null, null, null));
        s1.cancel();
    }

    @Test
    @Timeout(20)
    void disconnectingDoesNotCancelTheTurn() throws Exception {
        startServer();
        CompletableFuture<Void> turnDrained = new CompletableFuture<>();
        installSession(submission -> {
            hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                null, "default"));
            hub.onMessage(textMessage("before disconnect"));
            return turnDrained.thenApply(_ -> null);
        });

        LinkedBlockingQueue<String> events = new LinkedBlockingQueue<>();
        EventSource source = postMessages(
            "{\"model\":\"m\",\"max_tokens\":1,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hang\"}]}",
            events);
        pollUntil(events, "before disconnect");

        // The client walks away mid-turn.
        source.cancel();
        // The turn continues on the host: it still completes and records.
        hub.onMessage(textMessage("after disconnect"));
        hub.onTurnComplete(new TurnOutcome(
            false, false, false, false, false, 9L, null, null, null, null));
        turnDrained.complete(null);

        // A fresh submission is accepted: the in-flight slot was released by
        // turn completion, not by the disconnect.
        CompletableFuture<Void> secondTurn = new CompletableFuture<>();
        installSession(submission -> {
            hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(),
                null, "default"));
            hub.onTurnComplete(new TurnOutcome(
                false, false, false, false, false, 2L, null, null, null, null));
            secondTurn.complete(null);
            return CompletableFuture.completedFuture(null);
        });
        LinkedBlockingQueue<String> next = new LinkedBlockingQueue<>();
        EventSource s2 = postMessages(
            "{\"model\":\"m\",\"max_tokens\":1,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"next\"}]}",
            next);
        pollUntil(next, "message_stop");
        s2.cancel();
    }

    @Test
    @Timeout(20)
    void invalidRequestsReturnAnthropicErrorJson() throws Exception {
        startServer();
        installSession(_ -> CompletableFuture.completedFuture(null));

        try (Response noUser = client.newCall(new Request.Builder()
                .url(url("/v1/messages"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(
                    "{\"model\":\"m\",\"max_tokens\":1,\"messages\":[]}", JSON))
                .build()).execute()) {
            assertThat(noUser.code()).isEqualTo(400);
            assertThat(noUser.body().string()).contains("invalid_request_error");
        }
        try (Response badJson = client.newCall(new Request.Builder()
                .url(url("/v1/messages"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create("not json", JSON))
                .build()).execute()) {
            assertThat(badJson.code()).isEqualTo(400);
        }
    }

    /** Drains frames until one containing the expected text arrives. */
    private static void pollUntil(LinkedBlockingQueue<String> events, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String frame = events.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && Strings.CS.contains(frame, expected)) return;
        }
        throw new AssertionError("frame containing '" + expected + "' did not arrive");
    }

    private void awaitSubmission() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (submitCount.get() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("submission did not arrive");
            }
            Thread.sleep(20);
        }
    }

    private EventSource postMessages(String body, LinkedBlockingQueue<String> events) {
        return EventSources.createFactory(client).newEventSource(
            new Request.Builder()
                .url(url("/v1/messages"))
                .header("Authorization", "Bearer " + TOKEN)
                .post(RequestBody.create(body, JSON))
                .build(),
            new EventSourceListener() {
                @Override public void onEvent(EventSource source, String id, String type,
                        String data) {
                    events.add((type == null ? "" : type) + "|" + data);
                }

                @Override public void onFailure(EventSource source, Throwable t,
                        Response response) {
                    if (response != null && response.code() >= 400) {
                        try {
                            events.add("http-error|" + response.body().string());
                        } catch (IOException _) {
                            // Surface what we can.
                        }
                    }
                }
            });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private void startServer() throws IOException {
        server = new GatewayServer(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry);
        server.start();
    }

    /** Registers the active session with a scripted submitter. */
    private void installSession(
            Function<SessionHostSubmission, CompletableFuture<Void>> submitter) {
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "", 0, Instant.now(), ""),
            hub, submitter::apply);
        registry.activateLocal(session);
    }

    private static final class RecordingPrimarySink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }

    private SDKMessage.Assistant textMessage(String text) {
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID().toString(),
            new AssistantContent(null, List.of(new TextBlock(text)), null));
        return new SDKMessage.Assistant(message, Usage.EMPTY, "claude-sonnet-5");
    }
}
