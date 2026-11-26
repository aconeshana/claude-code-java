package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
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
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteWorkerPoolTest {

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private Path root;
    private Path socketPath;
    private ServerSocketChannel server;
    private SocketChannel peer;
    private InputStream peerInput;

    @BeforeEach
    void listen() throws IOException {
        root = Files.createTempDirectory(Path.of("/tmp"), "rwp");
        socketPath = root.resolve("w.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (peer != null) peer.close();
        server.close();
        Files.deleteIfExists(socketPath);
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(root);
    }

    @Test
    void listsSessionsHostedByARegisteredWorker() throws Exception {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        try (var ignored = directory.register(new WebWorkerRegistration(
                ProcessHandle.current().pid(), socketPath.toString(), "tok", "/work", "test"))) {
            Thread host = Thread.ofVirtual().start(() -> {
                try {
                    peer = server.accept();
                    peerInput = Channels.newInputStream(peer);
                    SessionLinkFrame hello = readFrame();
                    writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame list = readFrame();
                    assertThat(list.name()).isEqualTo("session.list");
                    var payload = JsonUtils.getMapper().createObjectNode();
                    var session = payload.putArray("sessions").addObject();
                    session.put("id", "s-1").put("work_dir", "/work")
                        .put("summary", "hello").put("message_count", 3)
                        .put("modified_at", "2026-11-10T00:00:00Z").put("git_branch", "main");
                    writeFrame(SessionLinkFrame.response(list.id(), list.name(), null, payload));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });

            SessionHostRegistry registry = new SessionHostRegistry(
                new SessionHostRegistry.Activator() {
                    @Override public CompletableFuture<SessionHostSession> activate(
                            SessionOpenRequest request) {
                        throw new UnsupportedOperationException("not used by this test");
                    }
                    @Override public List<SessionHostInfo> list() {
                        return List.of();
                    }
                });
            try (RemoteWorkerPool pool = new RemoteWorkerPool(directory, new MirrorHub(registry))) {
                List<SessionHostInfo> sessions = pool.liveSessions();

                assertThat(sessions).hasSize(1);
                SessionHostInfo info = sessions.getFirst();
                assertThat(info.id()).isEqualTo("s-1");
                assertThat(info.workDir()).isEqualTo("/work");
                assertThat(info.summary()).isEqualTo("hello");
                assertThat(info.messageCount()).isEqualTo(3);
                assertThat(info.gitBranch()).isEqualTo("main");
                assertThat(info.modifiedAt()).isNotNull();
            }
            host.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void submitForwardsTurnToTheWorkerListingTheSession() throws Exception {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        try (var ignored = directory.register(new WebWorkerRegistration(
                ProcessHandle.current().pid(), socketPath.toString(), "tok", "/work", "test"))) {
            Thread host = Thread.ofVirtual().start(() -> {
                try {
                    peer = server.accept();
                    peerInput = Channels.newInputStream(peer);
                    SessionLinkFrame hello = readFrame();
                    writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame list = readFrame();
                    var payload = JsonUtils.getMapper().createObjectNode();
                    payload.putArray("sessions").addObject().put("id", "s-1").put("work_dir", "/work");
                    writeFrame(SessionLinkFrame.response(list.id(), list.name(), null, payload));
                    SessionLinkFrame subscribe = readFrame();
                    assertThat(subscribe.name()).isEqualTo("session.subscribe");
                    writeFrame(SessionLinkFrame.response(subscribe.id(), subscribe.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame submit = readFrame();
                    assertThat(submit.name()).isEqualTo("turn.submit");
                    assertThat(submit.sessionId()).isEqualTo("s-1");
                    assertThat(submit.payload().path("prompt").asText()).isEqualTo("hi there");
                    assertThat(submit.payload().path("message_id").asText()).isEqualTo("m-9");
                    writeFrame(SessionLinkFrame.response(submit.id(), submit.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });

            SessionHostRegistry registry = new SessionHostRegistry(
                new SessionHostRegistry.Activator() {
                    @Override public CompletableFuture<SessionHostSession> activate(
                            SessionOpenRequest request) {
                        throw new UnsupportedOperationException("not used by this test");
                    }
                    @Override public List<SessionHostInfo> list() {
                        return List.of();
                    }
                });
            try (RemoteWorkerPool pool = new RemoteWorkerPool(directory, new MirrorHub(registry))) {
                var accepted = pool.submit("s-1",
                    new SessionHostSubmission("hi there", "m-9", List.of(), List.of()));

                assertThat(accepted).isPresent();
                assertThat(accepted.get().get(Duration.ofSeconds(5).toMillis(),
                    TimeUnit.MILLISECONDS).name()).isEqualTo("turn.submit");
                assertThat(pool.submit("missing",
                    new SessionHostSubmission("x", "m-0", List.of(), List.of()))).isEmpty();
            }
            host.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void respondInteractionForwardsTheAnswerToTheWorkerListingTheSession() throws Exception {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        try (var ignored = directory.register(new WebWorkerRegistration(
                ProcessHandle.current().pid(), socketPath.toString(), "tok", "/work", "test"))) {
            Thread host = Thread.ofVirtual().start(() -> {
                try {
                    peer = server.accept();
                    peerInput = Channels.newInputStream(peer);
                    SessionLinkFrame hello = readFrame();
                    writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame list = readFrame();
                    var payload = JsonUtils.getMapper().createObjectNode();
                    payload.putArray("sessions").addObject().put("id", "s-1").put("work_dir", "/work");
                    writeFrame(SessionLinkFrame.response(list.id(), list.name(), null, payload));
                    SessionLinkFrame subscribe = readFrame();
                    writeFrame(SessionLinkFrame.response(subscribe.id(), subscribe.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame answer = readFrame();
                    assertThat(answer.name()).isEqualTo("interaction.respond");
                    assertThat(answer.sessionId()).isEqualTo("s-1");
                    assertThat(answer.payload().path("request_id").asText()).isEqualTo("r-7");
                    assertThat(answer.payload().path("behavior").asText()).isEqualTo("deny");
                    assertThat(answer.payload().path("message").asText()).isEqualTo("no");
                    writeFrame(SessionLinkFrame.response(answer.id(), answer.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });

            SessionHostRegistry registry = new SessionHostRegistry(
                new SessionHostRegistry.Activator() {
                    @Override public CompletableFuture<SessionHostSession> activate(
                            SessionOpenRequest request) {
                        throw new UnsupportedOperationException("not used by this test");
                    }
                    @Override public List<SessionHostInfo> list() {
                        return List.of();
                    }
                });
            try (RemoteWorkerPool pool = new RemoteWorkerPool(directory, new MirrorHub(registry))) {
                var answer = JsonUtils.getMapper().createObjectNode()
                    .put("request_id", "r-7").put("behavior", "deny").put("message", "no");
                var accepted = pool.respondInteraction("s-1", answer);

                assertThat(accepted).isPresent();
                assertThat(accepted.get().get(Duration.ofSeconds(5).toMillis(),
                    TimeUnit.MILLISECONDS).name())
                    .isEqualTo("interaction.respond");
                assertThat(pool.respondInteraction("missing", answer)).isEmpty();
            }
            host.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Test
    void watchingFollowsAWorkerThatStartsLaterAndAnnouncesItsComingAndGoing() throws Exception {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        MirrorHub hub = new MirrorHub(emptyRegistry());
        BlockingQueue<String> announcements = new LinkedBlockingQueue<>();
        CompletableFuture<String> subscribed = new CompletableFuture<>();
        Thread host;
        try (var sink = hub.subscribe(frame -> {
                if (Strings.CS.equals("sessions.changed", frame.event())) {
                    announcements.add(frame.data());
                }
            });
             RemoteWorkerPool pool = new RemoteWorkerPool(directory, hub)) {
            pool.startWatching(Duration.ofMillis(50));
            Thread.sleep(300);
            assertThat(announcements).as("an unchanged empty directory is never announced").isEmpty();

            // The worker answers every listing for as long as the link stays up; no client of the
            // pool ever asks, so only the watcher can be what lists and attaches it.
            host = Thread.ofVirtual().start(() -> {
                try {
                    peer = server.accept();
                    peerInput = Channels.newInputStream(peer);
                    while (true) {
                        SessionLinkFrame request = readFrame();
                        var payload = JsonUtils.getMapper().createObjectNode();
                        if (Strings.CS.equals("session.list", request.name())) {
                            payload.putArray("sessions").addObject()
                                .put("id", "s-1").put("work_dir", "/work");
                        }
                        if (Strings.CS.equals("session.subscribe", request.name())) {
                            subscribed.complete(request.sessionId());
                        }
                        writeFrame(SessionLinkFrame.response(
                            request.id(), request.name(), null, payload));
                    }
                } catch (IOException _) {
                    // The pool closed the link.
                }
            });
            try (var registration = directory.register(new WebWorkerRegistration(
                    ProcessHandle.current().pid(), socketPath.toString(), "tok", "/work", "test"))) {
                assertThat(subscribed.get(5, TimeUnit.SECONDS)).isEqualTo("s-1");
                assertThat(announcements.poll(5, TimeUnit.SECONDS))
                    .as("the new session is announced").isNotNull();
            }
            assertThat(announcements.poll(5, TimeUnit.SECONDS))
                .as("the departed session is announced").isNotNull();
        }
        // Closing the pool ends the link, which is what lets the fake host return.
        host.join(Duration.ofSeconds(5).toMillis());
    }

    @Test
    void notifiesListenerImmediatelyWhenWorkerSocketCloses() throws Exception {
        WebWorkerDirectory directory = new WebWorkerDirectory(root.resolve("workers"));
        long workerPid = ProcessHandle.current().pid();
        CompletableFuture<Long> disconnectedPid = new CompletableFuture<>();
        CompletableFuture<Void> closePeer = new CompletableFuture<>();
        try (var ignored = directory.register(new WebWorkerRegistration(
                workerPid, socketPath.toString(), "tok", "/work", "test"))) {
            Thread host = Thread.ofVirtual().start(() -> {
                try {
                    peer = server.accept();
                    peerInput = Channels.newInputStream(peer);
                    SessionLinkFrame hello = readFrame();
                    writeFrame(SessionLinkFrame.response(hello.id(), hello.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    SessionLinkFrame list = readFrame();
                    writeFrame(SessionLinkFrame.response(list.id(), list.name(), null,
                        JsonUtils.getMapper().createObjectNode()));
                    closePeer.get(5, TimeUnit.SECONDS);
                    peer.close();
                } catch (Exception _) {
                    // Test teardown.
                }
            });

            try (RemoteWorkerPool pool = new RemoteWorkerPool(directory, new MirrorHub(emptyRegistry()))) {
                pool.setOnWorkerDisconnected(disconnectedPid::complete);
                pool.liveSessions();
                assertThat(disconnectedPid).isNotDone();

                closePeer.complete(null);
                assertThat(disconnectedPid.get(2, TimeUnit.SECONDS)).isEqualTo(workerPid);
            }
            host.join(Duration.ofSeconds(5).toMillis());
        }
    }

    private static SessionHostRegistry emptyRegistry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
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
