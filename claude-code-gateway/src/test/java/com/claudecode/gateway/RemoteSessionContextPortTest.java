package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.sessionlink.SessionLinkCodec;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The remote half of the context surface: a session another process hosts
 * must answer the same reads a local one does, or read as unknown.
 *
 * <p>Two sources are exercised — the Session Link for the model seat and its
 * changes, the transcript for usage and metrics — because the webui meter,
 * the stats pills, and the timeline all address one session id and none of
 * them may go blank just because the engine lives in another process.
 */
class RemoteSessionContextPortTest {

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private Path root;
    private Path socketPath;
    private ServerSocketChannel server;
    private SocketChannel peer;
    private InputStream peerInput;

    @BeforeEach
    void listen() throws IOException {
        root = Files.createTempDirectory(Path.of("/tmp"), "rscp");
        socketPath = root.resolve("w.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (peer != null) peer.close();
        server.close();
        Files.deleteIfExists(socketPath);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * The seat a webui client sees for a TUI session: the model catalogue and
     * the effort level both come from the owning process, so the composer
     * cannot show a blank seat for a session that is plainly running.
     */
    @Test
    @Timeout(30)
    void forwardsTheRemoteSelectionAndItsEffort() throws Exception {
        Thread host = hostListing("s-1", "/work", List.of());
        try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(
                new GatewaySessionContextPort() {}, pool, new GatewaySessionMessagesPort() {},
                (_, _) -> Optional.empty());

            Optional<GatewaySessionContextPort.ModelSelection> selection = port.selection("s-1");

            assertThat(selection).isPresent();
            assertThat(selection.get().current()).isEqualTo("claude-sonnet-5-5");
            assertThat(selection.get().models()).hasSize(1);
            assertThat(selection.get().models().getFirst().label()).isEqualTo("Sonnet 5.5");
            assertThat(selection.get().effortCurrent()).isEqualTo("high");
            assertThat(selection.get().effortChoices()).containsExactly("low", "high");
        } finally {
            host.join(5_000);
        }
    }

    /**
     * A model change is applied by the owner and read back, so the caller sees
     * the state the TUI {@code /model} picker would show — not an echo.
     */
    @Test
    @Timeout(30)
    void appliesAModelChangeOnTheHostAndReportsItsState() throws Exception {
        Thread host = hostListing("s-1", "/work", List.of((frame, out) -> {
            if (!Strings.CS.equals(frame.name(), "model.set")) return false;
            assertThat(frame.payload().path("model").asText()).isEqualTo("claude-opus-5-5");
            out.with(modelPayload("claude-opus-5-5"));
            return true;
        }));
        try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(
                new GatewaySessionContextPort() {}, pool, new GatewaySessionMessagesPort() {},
                (_, _) -> Optional.empty());

            GatewaySessionContextPort.SelectionResult result =
                port.selectModel("s-1", "claude-opus-5-5");

            assertThat(result.accepted()).isTrue();
            assertThat(result.selection().current()).isEqualTo("claude-opus-5-5");
            assertThat(result.selection().effortCurrent()).isEqualTo("high");
        } finally {
            host.join(5_000);
        }
    }

    /** A host-side rejection surfaces as the caller's displayable reason. */
    @Test
    @Timeout(30)
    void surfacesTheHostRejectionAsTheSelectionError() throws Exception {
        Thread host = hostListing("s-1", "/work", List.of((frame, out) -> {
            if (!Strings.CS.equals(frame.name(), "model.set")) return false;
            out.reject("unknown model: nope");
            return true;
        }));
        try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(
                new GatewaySessionContextPort() {}, pool, new GatewaySessionMessagesPort() {},
                (_, _) -> Optional.empty());

            GatewaySessionContextPort.SelectionResult result = port.selectModel("s-1", "nope");

            assertThat(result.accepted()).isFalse();
            assertThat(result.error()).isEqualTo("unknown model: nope");
        } finally {
            host.join(5_000);
        }
    }

    /**
     * Usage and metrics come from the transcript the owner wrote, located by
     * the work directory the owner reported — the same file the TUI resumes
     * from, so the meter and the stats pills agree with the TUI.
     */
    @Test
    @Timeout(30)
    void readsUsageAndMetricsFromTheTranscriptInTheReportedWorkDir() throws Exception {
        Path work = Files.createTempDirectory(Path.of("/tmp"), "rscp-work");
        Files.writeString(work.resolve("s-1.jsonl"), "{\"type\":\"user\"}\n");
        Thread host = hostListing("s-1", work.toString(), List.of());
        List<Message> rows = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        SessionMetricsSnapshot metrics = new SessionMetricsSnapshot(
            true, 3, 7, 10, 20, 30, 4, 50, 60, 70, 80, 90, 100);
        try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(
                new GatewaySessionContextPort() {},
                pool,
                new GatewaySessionMessagesPort() {
                    @Override public Optional<List<Message>> messagesInWorkDir(
                            String sessionId, String workDir) {
                        asked.add(sessionId + "@" + workDir);
                        return Optional.of(rows);
                    }
                },
                (sessionId, workDir) -> {
                    asked.add("metrics:" + sessionId + "@" + workDir);
                    return Optional.of(metrics);
                });

            assertThat(port.messages("s-1")).contains(rows);
            assertThat(port.metrics("s-1")).contains(metrics);

            // The fold is addressed by the work directory the owner reported:
            // this process's own project holds none of that transcript, so a
            // fold given anything else reports every metric as absent.
            assertThat(asked).contains("s-1@" + work, "metrics:s-1@" + work)
                .allMatch(entry -> Strings.CS.endsWith(entry, "@" + work));
        } finally {
            host.join(5_000);
            try (var files = Files.list(work)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(work);
        }
    }

    /**
     * The dashboard lists remote sessions alongside the local ones, so the
     * overview and the timeline ledger see a session that belongs to another
     * process.
     */
    @Test
    @Timeout(30)
    void mergesRemoteSessionsIntoTheLiveList() throws Exception {
        Thread host = hostListing("s-1", "/work", List.of());
        try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(
                new GatewaySessionContextPort() {
                    @Override public List<LiveSession> liveSessions() {
                        return List.of(new LiveSession("local", "mine", "/mine", Instant.EPOCH));
                    }
                }, pool, new GatewaySessionMessagesPort() {}, (_, _) -> Optional.empty());

            assertThat(port.liveSessions())
                .extracting(GatewaySessionContextPort.LiveSession::id)
                .containsExactly("local", "s-1");
        } finally {
            host.join(5_000);
        }
    }

    /** A session this process hosts itself keeps answering locally. */
    @Test
    void answersLocalSessionsWithoutTouchingTheLink() {
        GatewaySessionContextPort local = new GatewaySessionContextPort() {
            @Override public Optional<ModelSelection> selection(String sessionId) {
                return Optional.of(new ModelSelection("local-model", List.of(), "", "", List.of()));
            }
        };
        try (RemoteWorkerPool pool = poolOf(root.resolve("empty-workers"))) {
            RemoteSessionContextPort port = new RemoteSessionContextPort(local, pool,
                new GatewaySessionMessagesPort() {}, (_, _) -> Optional.empty());

            assertThat(port.selection("local").orElseThrow().current()).isEqualTo("local-model");
        }

    }

    // ------------------------------------------------------------ harness

    /** A frame the fake host answers itself instead of replying generically. */
    @FunctionalInterface
    private interface Interceptor {
        boolean on(SessionLinkFrame frame, Reply reply) throws IOException;

        /** Answer one intercepted frame, as a value or as a refusal. */
        interface Reply {
            void with(ObjectNode payload) throws IOException;

            /** Answer with the error frame a host sends when it refuses a change. */
            void reject(String message) throws IOException;
        }
    }

    /**
     * A registration outlives this call: the pool rescans the directory on
     * every read, and a closed handle would delete the worker between the
     * ownership check and the forward. The test's temp root removes it.
     */
    private Thread hostListing(String sessionId, String workDir, List<Interceptor> interceptors)
            throws IOException {
        new WebWorkerDirectory(root.resolve("workers")).register(new WebWorkerRegistration(
            ProcessHandle.current().pid(), socketPath.toString(), "tok", workDir, "test"));
        return Thread.ofVirtual().start(() -> serve(sessionId, workDir, interceptors));
    }

    private void serve(String sessionId, String workDir, List<Interceptor> interceptors) {
        try {
            peer = server.accept();
            peerInput = Channels.newInputStream(peer);
            SessionLinkFrame hello = readFrame();
            reply(hello, JsonUtils.getMapper().createObjectNode());
            SessionLinkFrame list = readFrame();
            ObjectNode listing = JsonUtils.getMapper().createObjectNode();
            listing.putArray("sessions").addObject().put("id", sessionId).put("work_dir", workDir);
            reply(list, listing);
            while (true) {
                SessionLinkFrame frame = readFrame();
                if (Strings.CS.equals(frame.name(), "session.subscribe")) {
                    reply(frame, JsonUtils.getMapper().createObjectNode());
                    continue;
                }
                boolean handled = false;
                Interceptor.Reply answer = new Interceptor.Reply() {
                    @Override public void with(ObjectNode payload) throws IOException {
                        reply(frame, payload);
                    }

                    @Override public void reject(String message) throws IOException {
                        writeFrame(SessionLinkFrame.error(frame.id(), frame.name(),
                            "invalid_request", message));
                    }
                };
                for (Interceptor interceptor : interceptors) {
                    handled = interceptor.on(frame, answer);
                    if (handled) break;
                }
                if (handled) continue;
                if (Strings.CS.equals(frame.name(), "model.get")) {
                    reply(frame, modelPayload("claude-sonnet-5-5"));
                } else if (Strings.CS.equals(frame.name(), "effort.get")) {
                    ObjectNode effort = JsonUtils.getMapper().createObjectNode();
                    effort.put("current", "high").put("effective", "high");
                    effort.putArray("efforts").add("low").add("high");
                    reply(frame, effort);
                } else {
                    reply(frame, JsonUtils.getMapper().createObjectNode());
                }
            }
        } catch (IOException _) {
            // The test closes the pool; the fake host ends with it.
        }
    }

    private static ObjectNode modelPayload(String current) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("current", current);
        ObjectNode row = payload.putArray("models").addObject();
        row.put("name", current).put("label", "Sonnet 5.5").put("description", "balanced")
            .put("alias", "sonnet").put("default", true);
        return payload;
    }

    private RemoteWorkerPool poolOf(Path workers) {
        return new RemoteWorkerPool(new WebWorkerDirectory(workers), new MirrorHub(registry()));
    }

    private static SessionHostRegistry registry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    }

    private void reply(SessionLinkFrame request, ObjectNode payload) throws IOException {
        writeFrame(SessionLinkFrame.response(request.id(), request.name(), null, payload));
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
