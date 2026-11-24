package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionlink.SessionLinkClient;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.claudecode.runtime.sessionlink.SessionLinkServer;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end orthogonal cross-verification of 3-way synchronization across:
 * <ul>
 *   <li>TUI host ({@link SessionLinkServer} + {@link SessionCollaborationController} + {@link InteractionCoordinator})</li>
 *   <li>Web daemon ({@link GatewayServer} + {@code RemoteWorkerPool} + {@code MirrorHub})</li>
 *   <li>IM / CC Connector peer ({@link SessionLinkClient} advertising {@code ["feishu"]})</li>
 * </ul>
 * Covers Web/IM/TUI message origination, 3-way output broadcast, and first-responder-wins
 * permission approval/denial from Web, IM, and TUI.
 */
class ThreeWayCollaborationSyncTest {

    private static final String LINK_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String WEB_TOKEN = "fedcba9876543210fedcba9876543210";

    @TempDir Path tempDir;

    @Test
    void threeWayMessageSyncAndFirstResponderPermissionMatrix() throws Exception {
        String sessionId = "sess-3way-001";
        SessionSink primary = new SessionSink() {
            @Override public void onTurnStart(UserInput input) {}
            @Override public void onMessage(SDKMessage msg) {}
            @Override public void onError(Throwable error, boolean userCancel) {}
            @Override public void onTurnComplete(TurnOutcome outcome) {}
            @Override public void onIdle() {}
        };
        SessionEventHub eventHub = new SessionEventHub(primary, _ -> {});
        List<String> receivedPrompts = new CopyOnWriteArrayList<>();

        SessionHostInfo info = new SessionHostInfo(
            sessionId, tempDir.toString(), "Three-Way Sync Demo", 0, Instant.now(), "main");
        SessionHostSession tuiSession = new SessionHostSession(info, eventHub, submission -> {
            receivedPrompts.add(submission.prompt());
            eventHub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(), java.util.Map.of(), ""));
            eventHub.onMessage(assistantText("Echo[" + submission.prompt() + "]"));
            eventHub.onTurnComplete(new TurnOutcome(true, false, 10L, "", java.util.Map.of(), ""));
            return CompletableFuture.completedFuture(null);
        });

        SessionHostRegistry tuiRegistry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest req) {
                return CompletableFuture.completedFuture(tuiSession);
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });
        tuiRegistry.activateLocal(tuiSession);

        InteractionCoordinator tuiInteractions = new InteractionCoordinator(() -> sessionId);
        AtomicReference<InteractionRequest<PermissionAskContext, PermissionAskCallback.Result>>
            localPendingPerm = new AtomicReference<>();
        tuiInteractions.register(InteractionFeatures.PERMISSION, new InteractionPresenter<>() {
            @Override public InteractionEndpoint endpoint() { return InteractionEndpoint.LOCAL; }
            @Override public InteractionSupport support() { return InteractionSupport.SUPPORTED; }
            @Override public boolean available(String sid) { return true; }
            @Override public void present(
                    InteractionRequest<PermissionAskContext, PermissionAskCallback.Result> req) {
                localPendingPerm.set(req);
            }
        });

        SessionCollaborationController tuiCollab = new SessionCollaborationController(tuiRegistry);
        Path socketDir = CcConnectSupport.createSocketDirectory();
        Path linkSocket = socketDir.resolve("link.sock");
        Path workersDir = tempDir.resolve("web-workers");
        WebWorkerDirectory workerDirectory = new WebWorkerDirectory(workersDir);

        SessionHostRegistry emptyWebRegistry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest req) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });

        BlockingQueue<SessionLinkFrame> imFrames = new LinkedBlockingQueue<>();
        BlockingQueue<String> webSseEvents = new LinkedBlockingQueue<>();

        try (SessionLinkServer tuiLink = new SessionLinkServer(
                new SessionLinkServer.Config(linkSocket, LINK_TOKEN),
                tuiRegistry, tuiInteractions, tuiCollab);
             WebWorkerDirectory.Registration ignoredReg = workerDirectory.register(
                new WebWorkerRegistration(ProcessHandle.current().pid(), linkSocket.toString(),
                    LINK_TOKEN, tempDir.toString(), "test"));
             GatewayServer webGateway = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), WEB_TOKEN, emptyWebRegistry)
                .webWorkers(workerDirectory)
                .interactions(new InteractionCoordinator(() -> {
                    throw new IllegalStateException("no local asks on web daemon");
                }))
                .build()) {

            tuiLink.start();
            webGateway.start();

            // 1. Connect simulated IM (cc-connect) client to TUI SessionLinkServer advertising "feishu".
            try (SessionLinkClient imClient = new SessionLinkClient(linkSocket, LINK_TOKEN, imFrames::add)) {
                imClient.connect();
                // Wait for Gateway's RemoteWorkerPool to discover & subscribe to the TUI session.
                HttpClient http = HttpClient.newHttpClient();
                String baseUrl = "http://127.0.0.1:" + webGateway.port();
                awaitSessionDiscovered(http, baseUrl, sessionId);

                // Subscribe Web UI SSE (/api/events) on a background virtual thread.
                Thread sseReader = Thread.ofVirtual().start(() -> readSse(http, baseUrl, webSseEvents));
                try {
                    // Subscribe IM client to the TUI session.
                    imClient.request("session.subscribe", sessionId, JsonUtils.getMapper().createObjectNode())
                        .get(3, TimeUnit.SECONDS);

                    // --- ORTHOGONAL TEST A: Message sent from WEB UI reaches TUI + Web SSE + IM ---
                    HttpResponse<String> webSend = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                            .header("Authorization", "Bearer " + WEB_TOKEN)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"claude\",\"max_tokens\":64,\"metadata\":{\"session_id\":\""
                                    + sessionId + "\"},\"messages\":[{\"role\":\"user\",\"content\":\"from-web\"}]}"))
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                    assertTrue(webSend.statusCode() == 200 || webSend.statusCode() == 202,
                        "Expected 200/202, got " + webSend.statusCode());
                    assertTrue(awaitContains(receivedPrompts, "from-web"), "TUI should receive web prompt");
                    assertNotNull(awaitImFrame(imFrames, "output.text", "Echo[from-web]"),
                        "IM should receive output from web-originated turn");
                    assertNotNull(awaitSseLine(webSseEvents, "Echo[from-web]"),
                        "Web SSE should receive output from web-originated turn");

                    // --- ORTHOGONAL TEST B: Message sent from IM (Feishu) reaches TUI + Web SSE + IM ---
                    ObjectNode imSubmit = JsonUtils.getMapper().createObjectNode();
                    imSubmit.put("prompt", "from-feishu");
                    imSubmit.put("message_id", "feishu-msg-001");
                    imClient.request("turn.submit", sessionId, imSubmit).get(3, TimeUnit.SECONDS);
                    assertTrue(awaitContains(receivedPrompts, "from-feishu"), "TUI should receive IM prompt");
                    assertNotNull(awaitImFrame(imFrames, "output.text", "Echo[from-feishu]"),
                        "IM should receive output from IM-originated turn");
                    assertNotNull(awaitSseLine(webSseEvents, "Echo[from-feishu]"),
                        "Web SSE should receive output from IM-originated turn");

                    // --- ORTHOGONAL TEST C: Message sent locally on TUI reaches Web SSE + IM ---
                    tuiSession.submit(new SessionHostSubmission("from-tui", "tui-msg-001", List.of(), List.of()))
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertNotNull(awaitImFrame(imFrames, "output.text", "Echo[from-tui]"),
                        "IM should receive output from TUI-originated turn");
                    assertNotNull(awaitSseLine(webSseEvents, "Echo[from-tui]"),
                        "Web SSE should receive output from TUI-originated turn");

                    // --- ORTHOGONAL TEST D: Permission asked on TUI -> Approved from WEB UI -> IM dismissed ---
                    CompletableFuture<PermissionAskCallback.Result> perm1 = CompletableFuture.supplyAsync(() ->
                        tuiInteractions.request(InteractionFeatures.PERMISSION, PermissionAskContext.simple(
                            "Bash", JsonUtils.getMapper().createObjectNode().put("command", "echo hi"), "tool-1")));
                    SessionLinkFrame imPerm1 = awaitImFrame(imFrames, "interaction.requested", "Bash");
                    assertNotNull(imPerm1, "IM should receive permission ask");
                    String reqId1 = imPerm1.payload().path("request_id").asText();
                    assertNotNull(awaitSseLine(webSseEvents, reqId1), "Web SSE should receive permission.asked");

                    HttpResponse<String> webApprove = http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/api/permissions/respond"))
                            .header("Authorization", "Bearer " + WEB_TOKEN)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"request_id\":\"" + reqId1 + "\",\"session_id\":\"" + sessionId
                                    + "\",\"allowed\":true}"))
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, webApprove.statusCode());
                    assertTrue(perm1.get(3, TimeUnit.SECONDS).allowed(), "Web approval should allow");
                    assertNotNull(awaitImFrame(imFrames, "interaction.resolved", reqId1),
                        "IM should receive interaction.resolved when Web approves");

                    // --- ORTHOGONAL TEST E: Permission asked on TUI -> Approved from IM -> Web UI dismissed ---
                    CompletableFuture<PermissionAskCallback.Result> perm2 = CompletableFuture.supplyAsync(() ->
                        tuiInteractions.request(InteractionFeatures.PERMISSION, PermissionAskContext.simple(
                            "Edit", JsonUtils.getMapper().createObjectNode().put("file_path", "a.txt"), "tool-2")));
                    SessionLinkFrame imPerm2 = awaitImFrame(imFrames, "interaction.requested", "Edit");
                    assertNotNull(imPerm2);
                    String reqId2 = imPerm2.payload().path("request_id").asText();
                    assertNotNull(awaitSseLine(webSseEvents, reqId2));

                    ObjectNode imApprove = JsonUtils.getMapper().createObjectNode();
                    imApprove.put("request_id", reqId2);
                    imApprove.put("behavior", "allow");
                    imClient.request("interaction.respond", sessionId, imApprove).get(3, TimeUnit.SECONDS);
                    assertTrue(perm2.get(3, TimeUnit.SECONDS).allowed(), "IM approval should allow");
                    assertNotNull(awaitSseLine(webSseEvents, "permission.resolved"),
                        "Web SSE should receive permission.resolved when IM approves");

                    // --- ORTHOGONAL TEST F: Permission asked on TUI -> Denied locally on TUI -> Both dismissed ---
                    localPendingPerm.set(null);
                    CompletableFuture<PermissionAskCallback.Result> perm3 = CompletableFuture.supplyAsync(() ->
                        tuiInteractions.request(InteractionFeatures.PERMISSION, PermissionAskContext.simple(
                            "Write", JsonUtils.getMapper().createObjectNode().put("file_path", "b.txt"), "tool-3")));
                    SessionLinkFrame imPerm3 = awaitImFrame(imFrames, "interaction.requested", "Write");
                    assertNotNull(imPerm3);
                    String reqId3 = imPerm3.payload().path("request_id").asText();
                    long deadline = System.currentTimeMillis() + 2_000;
                    while (localPendingPerm.get() == null && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    assertNotNull(localPendingPerm.get());
                    assertTrue(tuiInteractions.respond(
                        InteractionFeatures.PERMISSION, reqId3, sessionId,
                        PermissionAskCallback.Result.deny(), InteractionEndpoint.LOCAL));
                    assertFalse(perm3.get(3, TimeUnit.SECONDS).allowed(), "Local TUI denial should deny");
                    assertNotNull(awaitImFrame(imFrames, "interaction.resolved", reqId3),
                        "IM should receive interaction.resolved when TUI denies");
                } finally {
                    sseReader.interrupt();
                }
            }
        } finally {
            try { java.nio.file.Files.deleteIfExists(linkSocket); } catch (Exception _) {}
            try { java.nio.file.Files.deleteIfExists(socketDir); } catch (Exception _) {}
        }
    }

    private static void awaitSessionDiscovered(HttpClient http, String baseUrl, String sessionId)
            throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/sessions"))
                    .header("Authorization", "Bearer " + WEB_TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 && Strings.CS.contains(resp.body(), sessionId)) return;
            Thread.sleep(100);
        }
        fail("Gateway did not discover remote TUI session " + sessionId);
    }

    private static void readSse(HttpClient http, String baseUrl, BlockingQueue<String> sink) {
        try {
            HttpResponse<java.io.InputStream> resp = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/events?token=" + WEB_TOKEN))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (StringUtils.isNotBlank(line)) sink.offer(line);
                }
            }
        } catch (Exception _) {
            // Stream closed on test shutdown.
        }
    }

    private static boolean awaitContains(List<String> list, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (list.contains(expected)) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private static SessionLinkFrame awaitImFrame(
            BlockingQueue<SessionLinkFrame> queue, String name, String needle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            SessionLinkFrame frame = queue.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null && Strings.CS.equals(name, frame.name())
                    && (needle == null || Strings.CS.contains(frame.payload().toString(), needle))) {
                return frame;
            }
        }
        return null;
    }

    private static String awaitSseLine(BlockingQueue<String> queue, String needle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            String line = queue.poll(100, TimeUnit.MILLISECONDS);
            if (line != null && Strings.CS.contains(line, needle)) return line;
        }
        return null;
    }

    private static SDKMessage assistantText(String text) {
        return new SDKMessage.Assistant(
            new AssistantMessage("assistant", AssistantContent.of(
                "message", List.of(new TextBlock(text)))), Usage.EMPTY);
    }
}
