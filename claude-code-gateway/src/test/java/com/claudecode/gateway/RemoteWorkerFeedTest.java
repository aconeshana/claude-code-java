package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionlink.SessionLinkCodec;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteWorkerFeedTest {

    private final SessionLinkCodec codec = new SessionLinkCodec();
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
    private Path socketPath;
    private ServerSocketChannel server;
    private SocketChannel peer;
    private InputStream peerInput;

    @BeforeEach
    void listen() throws IOException {
        socketPath = Files.createTempDirectory(Path.of("/tmp"), "rwf").resolve("s.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (peer != null) peer.close();
        server.close();
        Files.deleteIfExists(socketPath);
        Files.deleteIfExists(socketPath.getParent());
    }

    @Test
    void activationSubscribesAndForwardsSessionEventsToMirror() throws Exception {
        MirrorHub hub = new MirrorHub(registry);
        RemoteWorkerFeed feed = new RemoteWorkerFeed(hub, socketPath, "tok");
        Thread host = Thread.ofVirtual().start(() -> {
            try {
                peer = server.accept();
                peerInput = Channels.newInputStream(peer);
                SessionLinkFrame hello = readFrame();
                writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                    JsonUtils.getMapper().createObjectNode()));
                writeFrame(SessionLinkFrame.event("session.activated", "remote-1",
                    JsonUtils.getMapper().createObjectNode().put("id", "remote-1")));
                SessionLinkFrame subscribe = readFrame();
                assertThat(subscribe.name()).isEqualTo("session.subscribe");
                assertThat(subscribe.sessionId()).isEqualTo("remote-1");
                writeFrame(SessionLinkFrame.response(subscribe.id(), subscribe.name(), "remote-1",
                    JsonUtils.getMapper().createObjectNode()));
                writeFrame(SessionLinkFrame.event("output.text", "remote-1",
                    JsonUtils.getMapper().createObjectNode().put("content", "hi")));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });

        feed.start();
        awaitReplay(hub, "output.text");
        host.join(Duration.ofSeconds(5).toMillis());
        feed.close();

        var frame = hub.replayAfter(0).stream()
            .filter(f -> Strings.CS.equals(f.event(), "output.text")).findFirst().orElseThrow();
        assertThat(frame.data()).contains("\"session_id\":\"remote-1\"");
        assertThat(frame.data()).contains("\"content\":\"hi\"");
    }

    private static void awaitReplay(MirrorHub hub, String event) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (hub.replayAfter(0).stream().anyMatch(f -> f.event().equals(event))) return;
            Thread.sleep(20);
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
