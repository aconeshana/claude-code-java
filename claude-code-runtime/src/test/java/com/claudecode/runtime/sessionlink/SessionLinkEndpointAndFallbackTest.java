package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLinkEndpointAndFallbackTest {

    private static final String VALID_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void parsesUnixAndLoopbackTcpEndpointsWhileRejectingNonLoopbackHosts() {
        Path rawUnix = tempDir.resolve("link.sock").toAbsolutePath();
        var parsedRaw = assertInstanceOf(
            SessionLinkEndpoint.UnixDomain.class,
            SessionLinkEndpoint.parse(rawUnix.toString()));
        assertEquals(rawUnix, parsedRaw.path());
        assertEquals("unix://" + rawUnix, parsedRaw.toSidecarUri());

        var parsedUri = assertInstanceOf(
            SessionLinkEndpoint.UnixDomain.class,
            SessionLinkEndpoint.parse("unix://" + rawUnix));
        assertEquals(rawUnix, parsedUri.path());

        var parsedIpv4 = assertInstanceOf(
            SessionLinkEndpoint.TcpLoopback.class,
            SessionLinkEndpoint.parse("tcp://127.0.0.1:54321"));
        assertEquals(54321, parsedIpv4.address().getPort());
        assertTrue(parsedIpv4.address().getAddress().isLoopbackAddress());
        assertEquals("tcp://127.0.0.1:54321", parsedIpv4.toRegistrationString());

        var parsedLocalhost = assertInstanceOf(
            SessionLinkEndpoint.TcpLoopback.class,
            SessionLinkEndpoint.parse("tcp://localhost:54322"));
        assertEquals("tcp://127.0.0.1:54322", parsedLocalhost.toRegistrationString());

        var parsedIpv6 = assertInstanceOf(
            SessionLinkEndpoint.TcpLoopback.class,
            SessionLinkEndpoint.parse("tcp://[::1]:54323"));
        assertEquals(54323, parsedIpv6.address().getPort());
        assertTrue(parsedIpv6.address().getAddress().isLoopbackAddress());

        for (String forbidden : List.of(
                "",
                "   ",
                "relative/link.sock",
                "tcp://0.0.0.0:19845",
                "tcp://192.168.1.50:19845",
                "tcp://10.0.0.8:19845",
                "tcp://evil.example.com:19845",
                "tcp://127.0.0.1:0",
                "tcp://127.0.0.1:70000",
                "tcp://127.0.0.1:notaport")) {
            assertThrows(IllegalArgumentException.class,
                () -> SessionLinkEndpoint.parse(forbidden),
                "Expected rejection for: " + forbidden);
        }
    }

    @Test
    void adaptiveServerFallsBackToLoopbackTcpWhenSocketPathExceeds100Bytes() throws Exception {
        Path deepDir = tempDir.resolve("windows-enterprise-profile-" + "x".repeat(85));
        Path overlongSocket = deepDir.resolve("link.sock");
        assertFalse(SessionLinkEndpoint.withinUnixPathLimit(overlongSocket));

        SessionHostRegistry registry = singleSessionRegistry("s-long-path");
        InteractionCoordinator interactions = new InteractionCoordinator(() -> "s-long-path");

        try (SessionLinkServer server = new SessionLinkServer(
                SessionLinkServer.Config.adaptive(overlongSocket, VALID_TOKEN),
                registry, interactions)) {
            server.start();
            var bound = assertInstanceOf(
                SessionLinkEndpoint.TcpLoopback.class, server.boundEndpoint());
            assertTrue(bound.address().getPort() > 0);
            assertTrue(Strings.CS.startsWith(bound.toRegistrationString(), "tcp://127.0.0.1:"));

            // Unauthenticated / wrong-token peer is still rejected on TCP loopback
            try (SessionLinkClient badClient = new SessionLinkClient(
                    SessionLinkEndpoint.parse(bound.toRegistrationString()),
                    "f".repeat(64), _ -> {})) {
                assertThrows(IOException.class, badClient::connect);
            }

            // Authenticated peer performs full SessionLink RPC + live event stream over TCP loopback
            BlockingQueue<SessionLinkFrame> events = new ArrayBlockingQueue<>(16);
            try (SessionLinkClient client = new SessionLinkClient(
                    SessionLinkEndpoint.parse(bound.toRegistrationString()),
                    VALID_TOKEN, events::add)) {
                client.connect();
                SessionLinkFrame listResp = client.request(
                    "session.list", null, JsonUtils.getMapper().createObjectNode())
                    .get(5, TimeUnit.SECONDS);
                assertEquals("s-long-path",
                    listResp.payload().path("sessions").get(0).path("id").asText());

                client.request("session.subscribe", "s-long-path",
                    JsonUtils.getMapper().createObjectNode()).get(5, TimeUnit.SECONDS);
                client.request("turn.submit", "s-long-path",
                    JsonUtils.getMapper().createObjectNode()
                        .put("prompt", "ping over tcp fallback")
                        .put("message_id", "m-1"))
                    .get(5, TimeUnit.SECONDS);

                boolean sawAssistantText = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    SessionLinkFrame ev = events.poll(200, TimeUnit.MILLISECONDS);
                    if (ev != null && Strings.CS.contains(ev.payload().toString(), "tcp-fallback-ok")) {
                        sawAssistantText = true;
                        break;
                    }
                }
                assertTrue(sawAssistantText, "Expected mirrored assistant text over TCP loopback fallback");
            }
        }
    }

    @Test
    void adaptiveServerFallsBackToLoopbackTcpWhenAfUnixIsUnsupportedOnPlatform() throws Exception {
        Path normalSocket = tempDir.resolve("short.sock");
        SessionHostRegistry registry = singleSessionRegistry("s-no-afunix");
        InteractionCoordinator interactions = new InteractionCoordinator(() -> "s-no-afunix");

        // Non-adaptive config still surfaces IOException when AF_UNIX is unsupported
        try (SessionLinkServer strictServer = new SessionLinkServer(
                new SessionLinkServer.Config(normalSocket, VALID_TOKEN),
                registry,
                interactions,
                new SessionCollaborationController(registry),
                () -> { throw new UnsupportedOperationException("afunix.sys disabled"); })) {
            assertThrows(IOException.class, strictServer::start);
        }

        // Adaptive config seamlessly binds 127.0.0.1:<ephemeral>
        try (SessionLinkServer adaptiveServer = new SessionLinkServer(
                SessionLinkServer.Config.adaptive(normalSocket, VALID_TOKEN),
                registry,
                interactions,
                new SessionCollaborationController(registry),
                () -> { throw new UnsupportedOperationException("afunix.sys disabled"); })) {
            adaptiveServer.start();
            var bound = assertInstanceOf(
                SessionLinkEndpoint.TcpLoopback.class, adaptiveServer.boundEndpoint());
            try (SessionLinkClient client = new SessionLinkClient(
                    bound, VALID_TOKEN, _ -> {})) {
                client.connect();
                SessionLinkFrame listed = client.request(
                    "session.list", null, JsonUtils.getMapper().createObjectNode())
                    .get(5, TimeUnit.SECONDS);
                assertEquals("s-no-afunix",
                    listed.payload().path("sessions").get(0).path("id").asText());
            }
        }
    }

    @Test
    void windowsNtfsOwnerOnlyAclAndWorkerDirectoryValidationEnforceLocalTrustBoundary() throws Exception {
        UserPrincipal ownerSid = () -> "DESKTOP-WIN11\\alice";
        StubAclView fileAcl = new StubAclView(ownerSid);
        OwnerOnlyPermissions.applyOwnerOnlyAcl(fileAcl, false);
        assertEquals(1, fileAcl.entries.size());
        AclEntry fileEntry = fileAcl.entries.getFirst();
        assertEquals(AclEntryType.ALLOW, fileEntry.type());
        assertEquals(ownerSid, fileEntry.principal());
        assertEquals(EnumSet.allOf(AclEntryPermission.class), fileEntry.permissions());
        assertTrue(fileEntry.flags().isEmpty());

        StubAclView dirAcl = new StubAclView(ownerSid);
        OwnerOnlyPermissions.applyOwnerOnlyAcl(dirAcl, true);
        assertEquals(1, dirAcl.entries.size());
        assertEquals(
            Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT),
            dirAcl.entries.getFirst().flags());

        // WebWorkerDirectory accepts tcp://127.0.0.1:<port> and purges tampered non-loopback entries
        WebWorkerDirectory dir = new WebWorkerDirectory(tempDir.resolve("web-workers"));
        long myPid = ProcessHandle.current().pid();
        try (var ignored = dir.register(new WebWorkerRegistration(
                myPid, "tcp://127.0.0.1:55123", VALID_TOKEN, "/work", "test"))) {
            List<WebWorkerRegistration> live = dir.scan();
            assertEquals(1, live.size());
            assertEquals("tcp://127.0.0.1:55123", live.getFirst().socket());
        }

        // Write a tampered registration pointing off-loopback -> scan() must discard & delete it
        Path forged = dir.directory().resolve(myPid + ".json");
        Files.writeString(forged, JsonUtils.toJson(new WebWorkerRegistration(
            myPid, "tcp://192.168.1.99:55123", VALID_TOKEN, "/work", "test")));
        assertTrue(dir.scan().isEmpty());
        assertFalse(Files.exists(forged));
    }

    private static SessionHostRegistry singleSessionRegistry(String sessionId) {
        SessionEventHub hub = new SessionEventHub(new NoopSink(), _ -> {});
        SessionHostSession session = new SessionHostSession(
            new SessionHostInfo(sessionId, "/work", "demo", 1, Instant.now(), "main"),
            hub,
            submission -> {
                hub.onTurnStart(UserInput.of(submission.prompt(), submission.prompt(), null, "m1"));
                hub.onMessage(new SDKMessage.Assistant(
                    new AssistantMessage(UUID.randomUUID().toString(),
                        new AssistantContent(null, List.of(new TextBlock("tcp-fallback-ok")), null)),
                    new Usage(4, 2, 0, 0), "m1"));
                hub.onTurnComplete(new TurnOutcome(
                    false, false, false, false, false, 4L, null, null, null, null));
                return CompletableFuture.completedFuture(null);
            });
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.completedFuture(session);
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of(session.info());
            }
        });
        registry.activateLocal(session);
        return registry;
    }

    private static final class StubAclView implements AclFileAttributeView {
        private UserPrincipal owner;
        private List<AclEntry> entries = new ArrayList<>();

        StubAclView(UserPrincipal owner) {
            this.owner = owner;
        }

        @Override public String name() { return "acl"; }
        @Override public UserPrincipal getOwner() { return owner; }
        @Override public void setOwner(UserPrincipal owner) { this.owner = owner; }
        @Override public List<AclEntry> getAcl() { return List.copyOf(entries); }
        @Override public void setAcl(List<AclEntry> acl) { this.entries = new ArrayList<>(acl); }
    }

    private static final class NoopSink implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
