package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionlink.SessionLinkCodec;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the gateway's HTTP surface against a fake interactive process that
 * is registered only in the worker directory: its sessions must appear in the
 * session list and its cancel must be forwarded over the Session Link.
 */
class RemoteWorkerGatewayInteropTest {

    private static final String TOKEN = "b".repeat(48);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<SessionLinkFrame> submitted = new CopyOnWriteArrayList<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(30)).build();
    private Path root;
    private Path socketPath;
    private ServerSocketChannel listener;
    private SocketChannel peer;
    private InputStream peerInput;
    private GatewayServer server;

    @BeforeEach
    void startFakeInteractiveProcess() throws IOException {
        root = Files.createTempDirectory(Path.of("/tmp"), "rwg");
        socketPath = root.resolve("w.sock");
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listener.bind(UnixDomainSocketAddress.of(socketPath));
        Thread.ofVirtual().start(this::answerEveryRequest);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        if (peer != null) peer.close();
        listener.close();
        Files.deleteIfExists(socketPath);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    @Timeout(20)
    void remoteSessionsAreListedAndTaggedAsRemote() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            String body = response.body().string();
            assertThat(body).contains("\"id\":\"s-1\"");
            assertThat(body).contains("\"remote\":true");
            // A remote worker's session is served by a live process, which is what
            // entitles the client to offer the permission mode and model selectors.
            assertThat(body).contains("\"interactive\":true");
        }
    }

    @Test
    @Timeout(20)
    void cancelIsForwardedToTheProcessHostingTheSession() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = post("/api/session/cancel", "{\"session_id\":\"s-1\"}")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).contains("\"cancelling\":true");
        }
        assertThat(received).contains("turn.cancel");
    }

    @Test
    @Timeout(20)
    void cancelForAnUnknownRemoteSessionIsNotFound() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = post("/api/session/cancel", "{\"session_id\":\"missing\"}")) {
            assertThat(response.code()).isEqualTo(404);
        }
        assertThat(received).doesNotContain("turn.cancel");
    }

    /**
     * A remote session's seat is edited by its owning process, not by this
     * gateway: the change is forwarded over the link and the owner's answer is
     * what the caller sees, so the webui and the TUI's own picker edit one
     * selection.
     */
    @Test
    @Timeout(20)
    void modelSelectionForARemoteSessionIsForwardedAndAnsweredByItsHost() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = post("/api/session/context",
                "{\"session_id\":\"s-1\",\"model\":\"sonnet\"}")) {
            assertThat(response.code()).isEqualTo(200);
            // The seat is the host's answer, not an echo of the request.
            assertThat(response.body().string()).contains("\"current\":\"claude-sonnet-5-5\"");
        }
        assertThat(received).contains("model.set", "effort.get");
    }

    @Test
    @Timeout(20)
    void turnSubmittedToARemoteSessionIsAcceptedAndForwarded() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = post("/v1/chat/completions",
                "{\"model\":\"sonnet\",\"stream\":false,"
                    + "\"metadata\":{\"session_id\":\"s-1\"},"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"hello remote\"}]}")) {
            assertThat(response.code()).isEqualTo(202);
        }
        assertThat(received).contains("turn.submit");
    }

    /**
     * A picture pasted into the webui composer reaches the TUI that hosts the
     * session: the image block travels over the link as an inline image the
     * host turns into an {@code [Image #N]} chip, not just the text beside it.
     */
    @Test
    @Timeout(20)
    void imagesSubmittedToARemoteSessionAreForwardedWithTheText() throws Exception {
        startGatewayAgainstWorkerDirectory();
        String png = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});

        try (Response response = post("/v1/messages", messagesBody(
                "{\"type\":\"text\",\"text\":\"look\"},"
                    + "{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                    + "\"media_type\":\"image/png\",\"data\":\"" + png + "\"}}"))) {
            assertThat(response.code()).isEqualTo(202);
        }
        var payload = submitted.getFirst().payload();
        assertThat(payload.path("prompt").asText()).isEqualTo("look");
        assertThat(payload.path("images")).hasSize(1);
        assertThat(payload.path("images").get(0).path("mime_type").asText()).isEqualTo("image/png");
        assertThat(payload.path("images").get(0).path("data").asText()).isEqualTo(png);
        assertThat(payload.path("attachments")).isEmpty();
    }

    /**
     * The composer lets a picture be sent with no text at all; the turn is
     * still valid and carries an empty prompt beside the image.
     */
    @Test
    @Timeout(20)
    void anImageAloneIsAValidTurnForARemoteSession() throws Exception {
        startGatewayAgainstWorkerDirectory();
        String png = Base64.getEncoder().encodeToString(new byte[] {4, 5});

        try (Response response = post("/v1/messages", messagesBody(
                "{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                    + "\"media_type\":\"image/jpeg\",\"data\":\"" + png + "\"}}"))) {
            assertThat(response.code()).isEqualTo(202);
        }
        var payload = submitted.getFirst().payload();
        assertThat(payload.path("prompt").asText()).isEmpty();
        assertThat(payload.path("images").get(0).path("mime_type").asText()).isEqualTo("image/jpeg");
    }

    private static String messagesBody(String contentBlocks) {
        return "{\"model\":\"sonnet\",\"max_tokens\":1,\"stream\":true,"
            + "\"metadata\":{\"session_id\":\"s-1\"},"
            + "\"messages\":[{\"role\":\"user\",\"content\":[" + contentBlocks + "]}]}";
    }

    @Test
    @Timeout(20)
    void remoteMessagesWithoutATranscriptAtTheReportedWorkDirAreNotFound() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/sessions/s-1/messages?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(404);
        }
    }

    private void startGatewayAgainstWorkerDirectory() throws IOException {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        directory.register(new WebWorkerRegistration(
            ProcessHandle.current().pid(), socketPath.toString(), "tok", "/work", "test"));
        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override public CompletableFuture<SessionHostSession> activate(
                        SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used by these tests");
                }
                @Override public List<SessionHostInfo> list() {
                    return List.of();
                }
            });
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .webWorkers(directory)
            .remoteSessionState(true)
            .build();
        server.start();
    }

    private Response post(String path, String json) throws IOException {
        return client.newCall(new Request.Builder()
            .url(url(path + "?token=" + TOKEN))
            .post(RequestBody.create(json, JSON))
            .build()).execute();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    /** Plays the TUI side of the link: records every request and answers it. */
    private void answerEveryRequest() {
        try {
            peer = listener.accept();
            peerInput = Channels.newInputStream(peer);
            while (true) {
                SessionLinkFrame request = readFrame();
                received.add(request.name());
                if (Strings.CS.equals(request.name(), "turn.submit")) submitted.add(request);
                var payload = JsonUtils.getMapper().createObjectNode();
                if (Strings.CS.equals(request.name(), "session.list")) {
                    payload.putArray("sessions").addObject()
                        .put("id", "s-1").put("work_dir", "/work").put("summary", "hello");
                } else if (Strings.CS.equals(request.name(), "model.set")) {
                    // The owner applies the change and answers with the seat
                    // it now reports, the way a TUI /model picker would.
                    payload.put("current", "claude-sonnet-5-5");
                    payload.putArray("models").addObject()
                        .put("name", "claude-sonnet-5-5").put("label", "Sonnet 5.5")
                        .put("default", true);
                } else if (Strings.CS.equals(request.name(), "model.get")) {
                    payload.put("current", "claude-sonnet-5-5");
                    payload.putArray("models").addObject()
                        .put("name", "claude-sonnet-5-5").put("label", "Sonnet 5.5")
                        .put("default", true);
                } else if (Strings.CS.equals(request.name(), "effort.get")) {
                    payload.put("current", "high").put("effective", "high");
                    payload.putArray("efforts").add("low").add("high");
                }
                writeFrame(SessionLinkFrame.response(
                    request.id(), request.name(), null, payload));
            }
        } catch (IOException _) {
            // The gateway closed the link; the test is finished with this peer.
        }
    }

    private SessionLinkFrame readFrame() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int next;
        while ((next = peerInput.read()) != '\n') {
            if (next < 0) throw new IOException("peer closed");
            line.write(next);
        }
        return codec.decode(line.toByteArray());
    }

    private void writeFrame(SessionLinkFrame frame) throws IOException {
        byte[] encoded = codec.encode(frame);
        ByteBuffer buffer = ByteBuffer.allocate(encoded.length + 1);
        buffer.put(encoded).put((byte) '\n').flip();
        while (buffer.hasRemaining()) peer.write(buffer);
    }
}
