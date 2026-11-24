package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionlink.SessionLinkEndpoint;
import com.claudecode.runtime.sessionlink.SessionLinkServer;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end interop test verifying that {@link GatewayServer} and {@link RemoteWorkerPool}
 * discover, mirror, and submit turns to a decoupled TUI worker registered over the Windows
 * {@code tcp://127.0.0.1:<ephemeralPort>} loopback fallback transport.
 */
class RemoteWorkerTcpFallbackTest {

    private static final String GATEWAY_TOKEN = "w".repeat(48);
    private static final String WORKER_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final MediaType JSON = MediaType.get("application/json");

    @TempDir
    Path tempDir;

    private final OkHttpClient http = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(15))
        .build();
    private SessionLinkServer workerLinkServer;
    private WebWorkerDirectory.Registration workerReg;
    private GatewayServer gateway;

    @AfterEach
    void tearDown() {
        if (gateway != null) gateway.close();
        if (workerReg != null) workerReg.close();
        if (workerLinkServer != null) workerLinkServer.close();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void gatewayDiscoversAndDrivesDecoupledWorkerOverLoopbackTcpFallback() throws Exception {
        List<String> workerReceivedPrompts = new CopyOnWriteArrayList<>();
        String remoteSessionId = "win-tcp-session-1";
        SessionEventHub workerHub = new SessionEventHub(new NoopSink(), _ -> {});
        SessionHostSession remoteTuiSession = new SessionHostSession(
            new SessionHostInfo(remoteSessionId, tempDir.toString(), "Windows TUI", 2, Instant.now(), "main"),
            workerHub,
            submission -> {
                workerReceivedPrompts.add(submission.prompt());
                workerHub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(), null, "m1"));
                workerHub.onMessage(new SDKMessage.Assistant(
                    new AssistantMessage(UUID.randomUUID().toString(),
                        new AssistantContent(null, List.of(new TextBlock("WINDOWS_TCP_LINK_OK")), null)),
                    new Usage(8, 4, 0, 0), "m1"));
                workerHub.onTurnComplete(new TurnOutcome(
                    false, false, false, false, false, 8L, null, null, null, null));
                return CompletableFuture.completedFuture(null);
            });
        SessionHostRegistry workerRegistry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(remoteTuiSession);
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });
        workerRegistry.activateLocal(remoteTuiSession);

        // Force adaptive fallback via an overlong >100-byte socket path (exact Windows enterprise profile case)
        Path overlongSocket = tempDir.resolve("C_Users_LongEnterpriseUser_" + "z".repeat(85)).resolve("link.sock");
        workerLinkServer = new SessionLinkServer(
            SessionLinkServer.Config.adaptive(overlongSocket, WORKER_TOKEN),
            workerRegistry,
            new InteractionCoordinator(() -> remoteSessionId));
        workerLinkServer.start();
        assertThat(workerLinkServer.boundEndpoint()).isInstanceOf(SessionLinkEndpoint.TcpLoopback.class);

        WebWorkerDirectory workersDir = new WebWorkerDirectory(tempDir.resolve("web-workers"));
        workerReg = workersDir.register(new WebWorkerRegistration(
            ProcessHandle.current().pid(),
            workerLinkServer.boundEndpoint().toRegistrationString(),
            WORKER_TOKEN,
            tempDir.toString(),
            "0.3.4"));

        SessionHostRegistry emptyGatewayRegistry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("gateway has no local TUI");
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });
        gateway = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), GATEWAY_TOKEN, emptyGatewayRegistry)
            .webWorkers(workersDir)
            .remoteSessionState(true)
            .build();
        gateway.start();
        String gwBase = "http://127.0.0.1:" + gateway.port();

        // 1. GET /api/sessions discovers the TCP-fallback worker session and tags remote=true
        try (Response listResp = http.newCall(new Request.Builder()
                .url(gwBase + "/api/sessions")
                .header("Authorization", "Bearer " + GATEWAY_TOKEN)
                .get()
                .build()).execute()) {
            assertThat(listResp.code()).isEqualTo(200);
            JsonNode projects = JsonUtils.parseTree(listResp.body().string()).path("projects");
            assertThat(projects).hasSize(1);
            JsonNode sessions = projects.get(0).path("sessions");
            assertThat(sessions).hasSize(1);
            assertThat(sessions.get(0).path("id").asText()).isEqualTo(remoteSessionId);
            assertThat(sessions.get(0).path("remote").asBoolean()).isTrue();
            assertThat(sessions.get(0).path("interactive").asBoolean()).isTrue();
        }

        // 2. Subscribe to MirrorHub, POST /v1/messages (202 Accepted), and verify live reply arrives over TCP SessionLink
        List<MirrorHub.MirrorFrame> mirroredFrames = new CopyOnWriteArrayList<>();
        try (AutoCloseable _ = gateway.mirror().subscribe(mirroredFrames::add)) {
            try (Response turnResp = http.newCall(new Request.Builder()
                    .url(gwBase + "/v1/messages")
                    .header("Authorization", "Bearer " + GATEWAY_TOKEN)
                    .post(RequestBody.create("""
                        {"model":"m1","stream":true,"metadata":{"session_id":"%s"},
                         "messages":[{"role":"user","content":"hello windows worker"}]}
                        """.formatted(remoteSessionId), JSON))
                    .build()).execute()) {
                assertThat(turnResp.code()).isEqualTo(202);
            }
            assertThat(workerReceivedPrompts).containsExactly("hello windows worker");

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline
                    && mirroredFrames.stream()
                        .noneMatch(f -> Strings.CS.contains(f.data(), "WINDOWS_TCP_LINK_OK"))) {
                Thread.sleep(20);
            }
            assertThat(mirroredFrames)
                .anyMatch(f -> Strings.CS.contains(f.data(), "WINDOWS_TCP_LINK_OK"));
        }
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
