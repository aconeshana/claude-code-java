package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayWorkspaceFilePort.TextFile;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The remote half of the file preview: a session another process hosts must
 * open its files the same way a local one does, or the preview reads as an
 * unknown file for a file that plainly exists.
 *
 * <p>The owning process reports its working directory over the registration the
 * gateway already scans, and the file is read from there — no Session Link
 * frame carries content, because the owner's own working directory is the file's
 * home. Containment is the same rule as a local read: the path must resolve
 * inside the reported root once symbolic links are followed.
 */
class RemoteWorkspaceFilePortTest {

    private final SessionLinkCodec codec = new SessionLinkCodec();
    private Path root;
    private Path socketPath;
    private ServerSocketChannel server;
    private SocketChannel peer;
    private InputStream peerInput;

    @BeforeEach
    void listen() throws IOException {
        root = Files.createTempDirectory(Path.of("/tmp"), "rwfp");
        socketPath = root.resolve("w.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (peer != null) peer.close();
        server.close();
        Files.deleteIfExists(socketPath);
        deleteTree(root);
    }

    /**
     * The preview of a file in the work directory the owner reported: the
     * gateway's own port resolves nothing for a session it does not host, so
     * the answer has to come from that directory.
     */
    @Test
    @Timeout(30)
    void readsAFileInsideTheRemoteWorkDir() throws Exception {
        Path work = Files.createTempDirectory(Path.of("/tmp"), "rwfp-work");
        try {
            Files.createDirectories(work.resolve("src"));
            Files.writeString(work.resolve("src/App.java"), "class App {}\n");
            Thread host = hostListing("s-1", work.toString());
            try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
                RemoteWorkspaceFilePort port =
                    new RemoteWorkspaceFilePort(GatewayWorkspaceFilePort.none(), pool);

                Optional<TextFile> file = port.read("s-1", "src/App.java");

                assertThat(file).isPresent();
                assertThat(file.get().kind()).isEqualTo(TextFile.Kind.TEXT);
                assertThat(file.get().text()).isEqualTo("class App {}\n");
                assertThat(file.get().path()).isEqualTo("src/App.java");
            }
            host.join(5_000);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * A path that escapes the reported root is refused exactly as a local read
     * refuses it. The work directory is the only root this port has, so
     * {@code ../..} must not reach anything outside it.
     */
    @Test
    @Timeout(30)
    void refusesAPathThatEscapesTheRemoteWorkDir() throws Exception {
        Path work = Files.createTempDirectory(Path.of("/tmp"), "rwfp-work");
        try {
            Path outside = work.getParent().resolve("rwfp-secret.txt");
            Files.writeString(outside, "must not be served\n");
            Thread host = hostListing("s-1", work.toString());
            try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
                RemoteWorkspaceFilePort port =
                    new RemoteWorkspaceFilePort(GatewayWorkspaceFilePort.none(), pool);
                String escape = "../" + outside.getFileName();

                assertThat(port.read("s-1", escape)).isEmpty();
                assertThat(port.read("s-1", "../../etc/passwd")).isEmpty();
            }
            host.join(5_000);
            Files.deleteIfExists(outside);
        } finally {
            deleteTree(work);
        }
    }

    /** A symlink out of the root is refused the way a {@code ..} segment is. */
    @Test
    @Timeout(30)
    void refusesAsymlinkPointingOutsideTheRemoteWorkDir() throws Exception {
        Path work = Files.createTempDirectory(Path.of("/tmp"), "rwfp-work");
        try {
            Path outside = work.getParent().resolve("rwfp-target.txt");
            Files.writeString(outside, "must not be served\n");
            Files.createSymbolicLink(work.resolve("link.txt"), outside);
            Thread host = hostListing("s-1", work.toString());
            try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
                RemoteWorkspaceFilePort port =
                    new RemoteWorkspaceFilePort(GatewayWorkspaceFilePort.none(), pool);

                assertThat(port.read("s-1", "link.txt")).isEmpty();
            }
            host.join(5_000);
            Files.deleteIfExists(outside);
        } finally {
            deleteTree(work);
        }
    }

    /**
     * A session no live process claims answers empty rather than throwing: the
     * webui renders the same "unknown file" it renders for a genuinely absent
     * path.
     */
    @Test
    void answersEmptyForASessionNoProcessOwns() {
        try (RemoteWorkerPool pool = poolOf(root.resolve("empty-workers"))) {
            RemoteWorkspaceFilePort port =
                new RemoteWorkspaceFilePort(GatewayWorkspaceFilePort.none(), pool);

            assertThat(port.read("nobody", "src/App.java")).isEmpty();
            assertThat(port.read(null, "src/App.java")).isEmpty();
        }
    }

    /**
     * A file this process can serve itself keeps its local answer: the remote
     * read is a fallback, not an override.
     */
    @Test
    @Timeout(30)
    void prefersTheLocalAnswerWhenTheSessionResolvesLocally() throws Exception {
        Path work = Files.createTempDirectory(Path.of("/tmp"), "rwfp-work");
        try {
            Files.writeString(work.resolve("remote.txt"), "remote\n");
            Thread host = hostListing("s-1", work.toString());
            try (RemoteWorkerPool pool = poolOf(root.resolve("workers"))) {
                RemoteWorkspaceFilePort port = new RemoteWorkspaceFilePort(
                    (_, path) -> Optional.of(new TextFile(
                        path, TextFile.Kind.TEXT, "local wins")), pool);

                assertThat(port.read("s-1", "remote.txt"))
                    .map(TextFile::text).contains("local wins");
            }
            host.join(5_000);
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------ harness

    /**
     * A registration outlives this call: the pool rescans the directory on
     * every read, and a closed handle would delete the worker between the
     * ownership check and the read. The test's temp root removes it.
     */
    private Thread hostListing(String sessionId, String workDir) throws IOException {
        new WebWorkerDirectory(root.resolve("workers")).register(new WebWorkerRegistration(
            ProcessHandle.current().pid(), socketPath.toString(), "tok", workDir, "test"));
        return Thread.ofVirtual().start(() -> serve(sessionId, workDir));
    }

    private void serve(String sessionId, String workDir) {
        try {
            peer = server.accept();
            peerInput = Channels.newInputStream(peer);
            SessionLinkFrame hello = readFrame();
            reply(hello, JsonUtils.getMapper().createObjectNode());
            SessionLinkFrame list = readFrame();
            var listing = JsonUtils.getMapper().createObjectNode();
            listing.putArray("sessions").addObject().put("id", sessionId).put("work_dir", workDir);
            reply(list, listing);
            while (true) {
                SessionLinkFrame frame = readFrame();
                reply(frame, JsonUtils.getMapper().createObjectNode());
            }
        } catch (IOException _) {
            // The test closes the pool; the fake host ends with it.
        }
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

    private void reply(SessionLinkFrame request, ObjectNode payload)
            throws IOException {
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

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}