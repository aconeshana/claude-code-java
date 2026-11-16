package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.claudecode.core.serialization.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionLinkClientTest {

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private Path socketPath;
    private ServerSocketChannel server;
    private SocketChannel peer;

    @BeforeEach
    void listen() throws IOException {
        socketPath = Files.createTempDirectory(Path.of("/tmp"), "slc").resolve("s.sock");
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
    void completesRequestWithResponseAndForwardsEvents() throws Exception {
        BlockingQueue<SessionLinkFrame> events = new ArrayBlockingQueue<>(4);
        SessionLinkClient client = new SessionLinkClient(socketPath, "tok", events::add);
        Thread host = Thread.ofVirtual().start(this::serveHelloThenList);
        client.connect();

        SessionLinkFrame listed = client.request("session.list", null,
            JsonUtils.getMapper().createObjectNode()).get(5, TimeUnit.SECONDS);

        assertEquals("ok", listed.payload().get("status").asText());
        SessionLinkFrame event = events.poll(5, TimeUnit.SECONDS);
        assertEquals("turn.started", event.name());
        host.join(5_000);
        client.close();
    }

    @Test
    void errorFrameFailsRequestWithRemoteCode() throws Exception {
        SessionLinkClient client = new SessionLinkClient(socketPath, "tok", _ -> {});
        Thread host = Thread.ofVirtual().start(() -> {
            try {
                acceptPeer();
                SessionLinkFrame hello = readFrames(1).getFirst();
                writeFrame(SessionLinkFrame.response(
                    hello.id(), hello.name(), null, JsonUtils.getMapper().createObjectNode()));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        client.connect();
        host.join(5_000);

        Thread refuse = Thread.ofVirtual().start(() -> {
            try {
                SessionLinkFrame subscribe = readFrames(1).getFirst();
                writeFrame(SessionLinkFrame.error(
                    subscribe.id(), subscribe.name(), "invalid_request", "nope"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        CompletionException failure = assertThrows(CompletionException.class,
            () -> client.request("session.subscribe", "s1", null).join());
        assertEquals("nope", failure.getCause().getMessage());
        assertEquals("invalid_request",
            ((SessionLinkClient.RemoteException) failure.getCause()).code());
        refuse.join(5_000);
        client.close();
    }

    @Test
    void aListenerThatThrowsOnOneEventKeepsTheLinkOpen() throws Exception {
        // The listener runs on the reader thread. If its failure escaped the read
        // loop, the client would close and the request below — answered only after
        // the rejected event — would never complete.
        List<String> seen = new ArrayList<>();
        SessionLinkClient client = new SessionLinkClient(socketPath, "tok", frame -> {
            seen.add(frame.name());
            throw new IllegalStateException("projection could not apply " + frame.name());
        });
        Thread host = Thread.ofVirtual().start(() -> {
            try {
                acceptPeer();
                SessionLinkFrame hello = readFrames(1).getFirst();
                writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                    JsonUtils.getMapper().createObjectNode()));
                SessionLinkFrame list = readFrames(1).getFirst();
                // An event the listener rejects, then the response to the request.
                writeFrame(SessionLinkFrame.event("output.text", "s1",
                    JsonUtils.getMapper().createObjectNode()));
                writeFrame(SessionLinkFrame.response(list.id(), list.name(), null,
                    JsonUtils.getMapper().createObjectNode().put("status", "ok")));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        client.connect();

        SessionLinkFrame response = client.request("session.list", null,
            JsonUtils.getMapper().createObjectNode()).get(5, TimeUnit.SECONDS);

        assertEquals("ok", response.payload().get("status").asText());
        assertEquals(List.of("output.text"), seen);
        host.join(5_000);
        client.close();
    }

    private void serveHelloThenList() {
        try {
            acceptPeer();
            SessionLinkFrame hello = readFrames(1).getFirst();
            writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                JsonUtils.getMapper().createObjectNode()));
            SessionLinkFrame list = readFrames(1).getFirst();
            writeFrame(SessionLinkFrame.event("turn.started", "s1",
                JsonUtils.getMapper().createObjectNode()));
            var payload = JsonUtils.getMapper().createObjectNode().put("status", "ok");
            writeFrame(SessionLinkFrame.response(list.id(), list.name(), null, payload));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void acceptPeer() throws IOException {
        peer = server.accept();
    }

    private List<SessionLinkFrame> readFrames(int count) throws IOException {
        List<SessionLinkFrame> frames = new ArrayList<>();
        var input = Channels.newInputStream(peer);
        while (frames.size() < count) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != '\n') {
                if (next < 0) throw new IOException("peer closed");
                line.write(next);
            }
            frames.add(codec.decode(line.toByteArray()));
        }
        return frames;
    }

    private void writeFrame(SessionLinkFrame frame) throws IOException {
        byte[] encoded = codec.encode(frame);
        ByteBuffer buffer = ByteBuffer.allocate(encoded.length + 1);
        buffer.put(encoded).put((byte) '\n').flip();
        while (buffer.hasRemaining()) peer.write(buffer);
    }
}
