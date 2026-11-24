package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionlink.SessionLinkCodec;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Orthogonal 3-endpoint Session Ownership & Resume Matrix (M1–M6) plus G4
 * empty live TUI snapshot regression tests against a real {@link GatewayServer}
 * and {@link RemoteWorkerPool}.
 */
@Timeout(20)
class SessionOwnershipMatrixInteropTest {

    private static final String TOKEN = "m".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private Path root;
    private Path workerDir;
    private Path socketPath;
    private ServerSocketChannel workerServer;
    private volatile SocketChannel workerPeer;

    private final StubHeadless headless = new StubHeadless();
    private GatewayServer gateway;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory(Path.of("/tmp"), "own-matrix-");
        workerDir = root.resolve("workers");
        socketPath = root.resolve("tui.sock");
        workerServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        workerServer.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (gateway != null) gateway.close();
        if (workerPeer != null) workerPeer.close();
        if (workerServer != null) workerServer.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        deleteRecursively(root);
    }

    private void startGateway() throws IOException {
        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override
                public CompletionStage<SessionHostSession> activate(SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used");
                }

                @Override
                public List<SessionHostInfo> list() {
                    return List.of();
                }
            });
        gateway = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .headless(headless)
            .webWorkers(new WebWorkerDirectory(workerDir))
            .build();
        gateway.start();
    }

    private AutoCloseable startFakeTuiWorker(String sessionId, String workDir) throws IOException {
        WebWorkerDirectory dir = new WebWorkerDirectory(workerDir);
        AutoCloseable registration = dir.register(new WebWorkerRegistration(
            ProcessHandle.current().pid(), socketPath.toString(), "worker-tok", workDir, "tui"));
        Thread.ofVirtual().start(() -> {
            while (workerServer.isOpen()) {
                try {
                    SocketChannel peer = workerServer.accept();
                    workerPeer = peer;
                    Thread.ofVirtual().start(() -> serveWorkerPeer(peer, sessionId, workDir));
                } catch (IOException _) {
                    break;
                }
            }
        });
        return registration;
    }

    private void serveWorkerPeer(SocketChannel peer, String sessionId, String workDir) {
        try (SocketChannel ch = peer) {
            InputStream in = Channels.newInputStream(ch);
            while (ch.isOpen() && !Thread.currentThread().isInterrupted()) {
                SessionLinkFrame req = readFrame(in);
                if (req == null) break;
                if (Strings.CS.equals(req.name(), "hello")) {
                    writeFrame(ch, SessionLinkFrame.response(
                        req.id(), req.name(), null, JsonUtils.getMapper().createObjectNode()));
                } else if (Strings.CS.equals(req.name(), "session.list")) {
                    var payload = JsonUtils.getMapper().createObjectNode();
                    var session = payload.putArray("sessions").addObject();
                    session.put("id", sessionId)
                        .put("work_dir", workDir)
                        .put("summary", "TUI session")
                        .put("message_count", 0)
                        .put("modified_at", "2026-11-24T00:00:00Z")
                        .put("git_branch", "main");
                    writeFrame(ch, SessionLinkFrame.response(
                        req.id(), req.name(), null, payload));
                } else {
                    writeFrame(ch, SessionLinkFrame.response(
                        req.id(), req.name(), null, JsonUtils.getMapper().createObjectNode()));
                }
            }
        } catch (IOException _) {
            // Socket closed on teardown or M6 worker exit.
        }
    }

    private void awaitTuiSessionInCatalog(String sessionId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode catalog = getJson("/api/sessions");
            if (findSession(catalog, sessionId) != null) return;
            Thread.sleep(50);
        }
        throw new AssertionError("TUI session " + sessionId + " did not appear in /api/sessions");
    }

    @Test
    void m1_openOnlineTuiSessionAttachesWithoutMintingHeadless() throws Exception {
        String tuiSessionId = "tui-live-m1";
        try (AutoCloseable ignored = startFakeTuiWorker(tuiSessionId, "/work/tui")) {
            startGateway();
            awaitTuiSessionInCatalog(tuiSessionId);

            // Catalog must expose tui_attached: true and tui_pid.
            JsonNode catalog = getJson("/api/sessions");
            JsonNode row = findSession(catalog, tuiSessionId);
            assertThat(row).isNotNull();
            assertThat(row.path("tui_attached").asBoolean()).isTrue();
            assertThat(row.path("tui_pid").asLong()).isEqualTo(ProcessHandle.current().pid());
            assertThat(row.path("interactive").asBoolean()).isTrue();

            // M1: POST /api/sessions/open attaches to the online TUI without creating headless.
            JsonNode opened = postJson("/api/sessions/open",
                "{\"session_id\":\"" + tuiSessionId + "\"}");
            assertThat(opened.path("session_id").asText()).isEqualTo(tuiSessionId);
            assertThat(opened.path("owner").asText()).isEqualTo("tui");
            assertThat(opened.path("headless").asBoolean()).isFalse();
            assertThat(opened.path("resumed").asBoolean()).isFalse();
            assertThat(opened.path("tui_attached").asBoolean()).isTrue();
            assertThat(opened.path("tui_pid").asLong()).isEqualTo(ProcessHandle.current().pid());
            assertThat(headless.openCalls.get()).isZero();
            assertThat(headless.find(tuiSessionId)).isEmpty();
        }
    }

    @Test
    void g4_emptyLiveTuiSessionSnapshotReturns200EmptyList() throws Exception {
        String tuiSessionId = "tui-empty-g4";
        try (AutoCloseable ignored = startFakeTuiWorker(tuiSessionId, "/work/tui")) {
            startGateway();
            awaitTuiSessionInCatalog(tuiSessionId);

            Request req = new Request.Builder()
                .url(url("/api/sessions/" + tuiSessionId + "/messages"))
                .header("Authorization", "Bearer " + TOKEN)
                .get()
                .build();
            try (Response resp = client.newCall(req).execute()) {
                assertThat(resp.code()).isEqualTo(200);
                JsonNode body = JsonUtils.getMapper().readTree(resp.body().string());
                assertThat(body.path("session_id").asText()).isEqualTo(tuiSessionId);
                assertThat(body.path("messages").isArray()).isTrue();
                assertThat(body.path("messages")).isEmpty();
            }
        }
    }

    @Test
    void m2_openActiveHeadlessSessionIsIdempotent() throws Exception {
        startGateway();

        JsonNode first = postJson("/api/sessions/open",
            "{\"session_id\":\"hl-m2\",\"project_path\":\"/work/a\"}");
        JsonNode second = postJson("/api/sessions/open",
            "{\"session_id\":\"hl-m2\",\"project_path\":\"/work/a\"}");

        assertThat(first.path("owner").asText()).isEqualTo("headless");
        assertThat(first.path("headless").asBoolean()).isTrue();
        assertThat(second.path("owner").asText()).isEqualTo("headless");
        assertThat(second.path("headless").asBoolean()).isTrue();
        assertThat(second.path("resumed").asBoolean()).isFalse();
        assertThat(headless.hubs).containsOnlyKeys("hl-m2");
    }

    @Test
    void m3_openInactiveDiskSessionRestoresHeadless() throws Exception {
        headless.diskSessionIds.add("disk-m3");
        startGateway();

        JsonNode opened = postJson("/api/sessions/open",
            "{\"session_id\":\"disk-m3\",\"project_path\":\"/work/other\"}");
        assertThat(opened.path("session_id").asText()).isEqualTo("disk-m3");
        assertThat(opened.path("owner").asText()).isEqualTo("headless");
        assertThat(opened.path("headless").asBoolean()).isTrue();
        assertThat(opened.path("resumed").asBoolean()).isTrue();
    }

    @Test
    void m4_openWithoutSessionIdMintsFreshHeadless() throws Exception {
        startGateway();

        JsonNode opened = postJson("/api/sessions/open", "{\"project_path\":\"/work/hero\"}");
        assertThat(opened.path("session_id").asText()).isNotBlank();
        assertThat(opened.path("owner").asText()).isEqualTo("headless");
        assertThat(opened.path("headless").asBoolean()).isTrue();
        assertThat(opened.path("resumed").asBoolean()).isFalse();
    }

    @Test
    void m5_tuiClaimingHeadlessSessionTriggersAutomaticHandoff() throws Exception {
        startGateway();

        // 1. Web UI opens session as Headless first.
        String sharedId = "handoff-m5";
        JsonNode openedHeadless = postJson("/api/sessions/open",
            "{\"session_id\":\"" + sharedId + "\",\"project_path\":\"/work/shared\"}");
        assertThat(openedHeadless.path("owner").asText()).isEqualTo("headless");
        assertThat(headless.find(sharedId)).isPresent();

        // 2. TUI connects and claims the same session (e.g. TUI/Feishu /resume).
        try (AutoCloseable ignored = startFakeTuiWorker(sharedId, "/work/shared")) {
            // Wait for RemoteWorkerPool's periodic refresh (or trigger catalog poll) to see it.
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && headless.find(sharedId).isPresent()) {
                getJson("/api/sessions");
                Thread.sleep(50);
            }

            // Headless handle must have been automatically closed on TUI claim.
            assertThat(headless.find(sharedId)).isEmpty();
            assertThat(headless.closeCalls.get()).isGreaterThanOrEqualTo(1);

            // Subsequent open must route to the TUI owner.
            JsonNode afterHandoff = postJson("/api/sessions/open",
                "{\"session_id\":\"" + sharedId + "\"}");
            assertThat(afterHandoff.path("owner").asText()).isEqualTo("tui");
            assertThat(afterHandoff.path("headless").asBoolean()).isFalse();
        }
    }

    @Test
    void m6_tuiExitDegradesSessionCleanlyToHeadlessResumable() throws Exception {
        String sessionId = "tui-exit-m6";
        AutoCloseable reg = startFakeTuiWorker(sessionId, "/work/tui");
        startGateway();
        awaitTuiSessionInCatalog(sessionId);

        // Simulate TUI exiting: remove registration and close peer socket.
        reg.close();
        if (workerPeer != null) workerPeer.close();
        workerServer.close();

        // Wait until RemoteWorkerPool drops the dead worker.
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode catalog = getJson("/api/sessions");
            JsonNode row = findSession(catalog, sessionId);
            if (row == null || !row.path("tui_attached").asBoolean()) break;
            Thread.sleep(50);
        }

        // Now opening the same session must fall back cleanly to Headless (M3).
        JsonNode reopened = postJson("/api/sessions/open",
            "{\"session_id\":\"" + sessionId + "\",\"project_path\":\"/work/tui\"}");
        assertThat(reopened.path("owner").asText()).isEqualTo("headless");
        assertThat(reopened.path("headless").asBoolean()).isTrue();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + gateway.port() + path;
    }

    private JsonNode getJson(String path) throws IOException {
        Request req = new Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer " + TOKEN)
            .get()
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            return JsonUtils.getMapper().readTree(resp.body().string());
        }
    }

    private JsonNode postJson(String path, String jsonBody) throws IOException {
        Request req = new Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(jsonBody, JSON))
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            return JsonUtils.getMapper().readTree(resp.body().string());
        }
    }

    private static JsonNode findSession(JsonNode catalog, String sessionId) {
        for (JsonNode project : catalog.path("projects")) {
            for (JsonNode session : project.path("sessions")) {
                if (Strings.CS.equals(session.path("id").asText(), sessionId)) {
                    return session;
                }
            }
        }
        return null;
    }

    private SessionLinkFrame readFrame(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return codec.decode(buf.toByteArray());
    }

    private void writeFrame(SocketChannel peer, SessionLinkFrame frame) throws IOException {
        byte[] encoded = codec.encode(frame);
        ByteBuffer buf = ByteBuffer.allocate(encoded.length + 1);
        buf.put(encoded).put((byte) '\n').flip();
        while (buf.hasRemaining()) peer.write(buf);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static final class StubHeadless implements GatewayHeadlessSessions {
        final AtomicInteger openCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        final Set<String> diskSessionIds = ConcurrentHashMap.newKeySet();
        final ConcurrentMap<String, SessionEventHub> hubs = new ConcurrentHashMap<>();

        @Override
        public Optional<SessionHostSession> find(String sessionId) {
            SessionEventHub hub = hubs.get(sessionId);
            if (hub == null) return Optional.empty();
            return Optional.of(session(sessionId, hub));
        }

        @Override
        public Opened open(OpenRequest request) {
            openCalls.incrementAndGet();
            String id = StringUtils.isBlank(request.sessionId())
                ? UUID.randomUUID().toString() : request.sessionId();
            boolean alreadyOpen = hubs.containsKey(id);
            SessionEventHub hub = hubs.computeIfAbsent(id,
                _ -> new SessionEventHub(new NoopSink(), _ -> {}));
            boolean resumed = !alreadyOpen && diskSessionIds.contains(id);
            String path = StringUtils.isBlank(request.projectPath())
                ? "/work/default" : request.projectPath();
            return new Opened(session(id, hub), path, resumed);
        }

        @Override
        public List<SessionListing> list() {
            return hubs.keySet().stream().sorted()
                .map(id -> new SessionListing(id, "/work/default", Instant.EPOCH))
                .toList();
        }

        @Override
        public boolean close(String sessionId) {
            boolean removed = hubs.remove(sessionId) != null;
            if (removed) closeCalls.incrementAndGet();
            return removed;
        }

        private SessionHostSession session(String id, SessionEventHub hub) {
            return new SessionHostSession(
                new SessionHostInfo(id, "/work/default", "", 0, Instant.now(), ""),
                hub,
                _ -> CompletableFuture.completedFuture(null));
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
