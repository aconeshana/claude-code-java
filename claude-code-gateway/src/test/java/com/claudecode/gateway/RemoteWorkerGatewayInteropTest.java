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

    @Test
    @Timeout(20)
    void modelSelectionForARemoteSessionIsUnsupportedAndNotForwarded() throws Exception {
        startGatewayAgainstWorkerDirectory();

        try (Response response = post("/api/session/context",
                "{\"session_id\":\"s-1\",\"model\":\"sonnet\"}")) {
            assertThat(response.code()).isEqualTo(501);
            assertThat(response.body().string()).contains("\"type\":\"unsupported\"");
        }
        assertThat(received).doesNotContain("session.model", "session.context");
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
                var payload = JsonUtils.getMapper().createObjectNode();
                if (Strings.CS.equals(request.name(), "session.list")) {
                    payload.putArray("sessions").addObject()
                        .put("id", "s-1").put("work_dir", "/work").put("summary", "hello");
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
