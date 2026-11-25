package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.core.serialization.JsonUtils;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Serial;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client side of the Session Link protocol: one authenticated Unix-socket connection
 * to another interactive process's link server.
 *
 * <p>Requests are correlated by id and completed when the matching response or
 * error frame arrives; every other frame (events) is handed to the listener in
 * arrival order on the reader thread. The supervisor uses this to follow remote TUI
 * sessions without sharing their process.
 */
@Explanation("Authenticated Session Link client used by the claude web supervisor")
public final class SessionLinkClient implements AutoCloseable {

    private static final SessionLinkCodec CODEC = new SessionLinkCodec();

    private final SessionLinkEndpoint endpoint;
    private final String token;
    private final Consumer<SessionLinkFrame> listener;
    private final Map<String, CompletableFuture<SessionLinkFrame>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SocketChannel channel;

    public SessionLinkClient(Path socketPath, String token, Consumer<SessionLinkFrame> listener) {
        this(new SessionLinkEndpoint.UnixDomain(Objects.requireNonNull(socketPath, "socketPath")),
            token, listener);
    }

    public SessionLinkClient(
            SessionLinkEndpoint endpoint, String token, Consumer<SessionLinkFrame> listener) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.token = Objects.requireNonNull(token, "token");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Opens the socket and authenticates with {@code link.hello}. */
    public void connect() throws IOException {
        SocketChannel opened = openConnectedChannel(endpoint);
        channel = opened;
        Thread.ofVirtual().name("session-link-client-reader").start(() -> readLoop(opened));
        ObjectNode hello = JsonUtils.getMapper().createObjectNode();
        hello.put("client", "claude-web");
        hello.put("auth_token", token);
        hello.putArray("collaboration_channels");
        try {
            request("link.hello", null, hello).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("session-link hello interrupted", interrupted);
        } catch (ExecutionException failure) {
            close();
            throw new IOException("session-link hello rejected: " + failure.getCause().getMessage(),
                failure.getCause());
        }
    }

    private static SocketChannel openConnectedChannel(SessionLinkEndpoint endpoint) throws IOException {
        return switch (endpoint) {
            case SessionLinkEndpoint.UnixDomain(Path socketPath) -> {
                SocketChannel opened = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    opened.connect(UnixDomainSocketAddress.of(socketPath));
                    yield opened;
                } catch (IOException failure) {
                    opened.close();
                    throw failure;
                }
            }
            case SessionLinkEndpoint.TcpLoopback(InetSocketAddress address) -> {
                SocketChannel opened = SocketChannel.open(StandardProtocolFamily.INET);
                try {
                    try { opened.setOption(StandardSocketOptions.TCP_NODELAY, true); } catch (IOException _) {}
                    opened.connect(address);
                    yield opened;
                } catch (IOException failure) {
                    opened.close();
                    throw failure;
                }
            }
        };
    }

    /**
     * Sends one request. The future completes with the response frame, or fails with
     * {@link RemoteException} when the peer answers with an error frame.
     */
    public CompletableFuture<SessionLinkFrame> request(String name, String sessionId, JsonNode payload) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<SessionLinkFrame> reply = new CompletableFuture<>();
        pending.put(id, reply);
        try {
            write(SessionLinkFrame.request(id, name, sessionId, payload));
        } catch (IOException failure) {
            pending.remove(id);
            reply.completeExceptionally(failure);
        }
        return reply;
    }

    private synchronized void write(SessionLinkFrame frame) throws IOException {
        SocketChannel current = channel;
        if (current == null || closed.get()) throw new IOException("session-link client is closed");
        byte[] encoded = CODEC.encode(frame);
        ByteBuffer buffer = ByteBuffer.allocate(encoded.length + 1);
        buffer.put(encoded).put((byte) '\n').flip();
        while (buffer.hasRemaining()) current.write(buffer);
    }

    private void readLoop(SocketChannel socket) {
        try (InputStream input = new BufferedInputStream(Channels.newInputStream(socket))) {
            while (!closed.get()) {
                dispatch(CODEC.decode(readLine(input)));
            }
        } catch (IOException | RuntimeException _) {
            // Connection loss ends the reader; pending requests are failed below.
        } finally {
            close();
        }
    }

    private void dispatch(SessionLinkFrame frame) {
        switch (frame.kind()) {
            case RESPONSE -> complete(frame.replyTo(), frame);
            case ERROR -> {
                CompletableFuture<SessionLinkFrame> reply = pending.remove(frame.replyTo());
                if (reply != null) reply.completeExceptionally(RemoteException.of(frame));
            }
            case EVENT -> consume(frame);
            case REQUEST -> { /* The host never initiates requests on a client connection. */ }
        }
    }

    /**
     * Hands one event frame to the listener without letting a listener failure
     * end this connection.
     *
     * <p>The listener runs on the reader thread, and a failure there escapes the
     * read loop into its {@code finally}, closing the client: a single bad frame
     * would then break every later request on a link that is still physically
     * open — the host keeps writing, the client never reads again. An event is
     * advisory to the client's projection, not part of the request/response
     * protocol, so one it cannot apply is dropped instead of costing the link.
     */
    private void consume(SessionLinkFrame frame) {
        try {
            listener.accept(frame);
        } catch (RuntimeException _) {
            // Dropped: the next frame on this link may still apply cleanly.
        }
    }

    private void complete(String replyTo, SessionLinkFrame frame) {
        CompletableFuture<SessionLinkFrame> reply = pending.remove(replyTo);
        if (reply != null) reply.complete(frame);
    }

    private static byte[] readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                if (line.size() == 0) throw new EOFException();
                break;
            }
            if (next == '\n') break;
            if (next != '\r') line.write(next);
        }
        return line.toByteArray();
    }

    /**
     * Whether this client has been closed — as a caller asked, or because its
     * reader loop saw the connection end. A client never reopens: its pending
     * futures were failed and later requests fail immediately, so a holder that
     * caches one must dial a new client rather than reuse this one.
     */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        SocketChannel current = channel;
        if (current != null) {
            try {
                current.close();
            } catch (IOException _) {
                // Already closing; pending requests are failed below regardless.
            }
        }
        IOException gone = new IOException("session-link connection closed");
        for (CompletableFuture<SessionLinkFrame> reply : pending.values()) {
            reply.completeExceptionally(gone);
        }
        pending.clear();
    }

    /** A request the peer refused with an error frame. */
    public static final class RemoteException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String code;

        private RemoteException(String code, String message) {
            super(message);
            this.code = code;
        }

        static RemoteException of(SessionLinkFrame frame) {
            String code = frame.error() == null ? "unknown" : frame.error().code();
            String message = frame.error() == null ? "remote request failed" : frame.error().message();
            return new RemoteException(code, message);
        }

        public String code() {
            return code;
        }
    }
}
