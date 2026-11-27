package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Verifies server-side atomic steer ({@code metadata.steer = true}) over HTTP
 * {@code POST /v1/messages}: cancels the running turn, awaits {@link InFlightGuard}
 * release without blind client sleeps, and starts the new turn cleanly.
 */
final class InFlightSteerTest {

    private static final String TOKEN =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void steerCancelsInFlightTurnAwaitsReleaseAndExecutesNewTurn() throws Exception {
        String sessionId = "33333333-3333-4333-8333-000000000001";
        SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
        CountDownLatch firstTurnStarted = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> lastPrompt = new AtomicReference<>();

        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/tmp/work", "", 0, Instant.now(), "main"),
            hub,
            submission -> {
                String prompt = submission.prompt();
                lastPrompt.set(prompt);
                if (Strings.CS.equals("slow first turn", prompt)) {
                    hub.onTurnStart(UserInput.of(prompt, prompt, null, "default"));
                    firstTurnStarted.countDown();
                    // First turn stays in flight until cancelLocal unwinds it.
                } else {
                    Thread.ofVirtual().start(() -> {
                        hub.onTurnStart(UserInput.of(prompt, prompt, null, "default"));
                        AssistantMessage message = new AssistantMessage(
                            UUID.randomUUID().toString(),
                            new AssistantContent(null, List.of(new TextBlock("steered reply")), null));
                        hub.onMessage(new SDKMessage.Assistant(message, Usage.EMPTY, "claude-sonnet-4-6"));
                        hub.onTurnComplete(new TurnOutcome(false, false, 5L, null, null, null, "default"));
                    });
                }
                return CompletableFuture.completedFuture(null);
            });

        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used");
            }

            @Override public List<SessionHostInfo> list() {
                return List.of(session.info());
            }
        });
        registry.activateLocal(session);

        GatewaySessionCancelPort cancelPort = targetId -> {
            if (!sessionId.equals(targetId)) return false;
            cancelled.set(true);
            // Simulate a turn taking 220ms to unwind (longer than the old 120ms client sleep).
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(220);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    hub.onTurnComplete(new TurnOutcome(true, false, 220L, null, null, null, "default"));
                }
            });
            return true;
        };

        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
                .cancel(cancelPort)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();

            // Start first slow turn asynchronously.
            String firstBody = JsonUtils.toJson(Map.of(
                "model", "claude-sonnet-4-6",
                "stream", false,
                "metadata", Map.of("session_id", sessionId),
                "messages", List.of(Map.of("role", "user", "content", "slow first turn"))));
            CompletableFuture<HttpResponse<String>> firstCall = client.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(firstBody))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(firstTurnStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Non-steer submit while busy still returns 429 immediately.
            String busyBody = JsonUtils.toJson(Map.of(
                "model", "claude-sonnet-4-6",
                "stream", false,
                "metadata", Map.of("session_id", sessionId),
                "messages", List.of(Map.of("role", "user", "content", "should fail 429"))));
            HttpResponse<String> busyResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(busyBody))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(busyResp.statusCode()).isEqualTo(429);

            // Single steer submit atomically cancels the slow turn, awaits release, and succeeds.
            String steerBody = JsonUtils.toJson(Map.of(
                "model", "claude-sonnet-4-6",
                "stream", false,
                "metadata", Map.of("session_id", sessionId, "steer", true),
                "messages", List.of(Map.of("role", "user", "content", "steered prompt"))));
            HttpResponse<String> steerResp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(steerBody))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(cancelled.get()).isTrue();
            assertThat(steerResp.statusCode()).isEqualTo(200);
            assertThat(steerResp.body()).contains("steered reply");
            assertThat(lastPrompt.get()).isEqualTo("steered prompt");

            HttpResponse<String> firstResp = firstCall.get(5, TimeUnit.SECONDS);
            assertThat(firstResp.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void inFlightGuardAwaitAndAcquireTimesOutWhenNotReleased() throws Exception {
        InFlightGuard guard = new InFlightGuard();
        assertThat(guard.tryAcquire("s1")).isTrue();
        assertThat(guard.awaitAndAcquire("s1", Duration.ofMillis(25))).isFalse();
        guard.release("s1");
        assertThat(guard.awaitAndAcquire("s1", Duration.ofMillis(50))).isTrue();
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
