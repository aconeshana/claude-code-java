package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.gateway.GatewayCcConnectPort;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliCcConnectManagerTest {

    @TempDir Path tempDir;

    @Test
    void configuredChannelsParsesBindKeyAndMultiTargets() throws Exception {
        Path config = tempDir.resolve("cc-connect.toml");
        Files.writeString(config, """
            [[projects]]
            name = "demo"
            [projects.agent]
            type = "sessionhost"
            [projects.agent.options]
            bind_session_key = "feishu:oc_123:ou_456"
            [projects.agent.options.collaboration_targets]
            feishu = "feishu:oc_123:ou_456"
            slack = "slack:C1:U1"
            [[projects.platforms]]
            type = "feishu"
            [projects.platforms.options]
            allow_chat = "oc_123"
            """);

        assertTrue(CcConnectSupport.targetConfigured(config));
        assertEquals(List.of("feishu", "slack"), CcConnectSupport.configuredChannels(config));
    }

    @Test
    void tuiBridgeAutoStartsGatewayAndReportsAttachDetachAndSetup() throws Exception {
        AtomicReference<GatewayCcConnectPort.AttachRequest> lastAttach = new AtomicReference<>();
        AtomicReference<GatewayCcConnectPort.DetachRequest> lastDetach = new AtomicReference<>();
        GatewayCcConnectPort stubPort = new GatewayCcConnectPort() {
            @Override
            public Snapshot snapshot() {
                return new Snapshot(true, true, false, tempDir.resolve("cc-connect.toml").toString(),
                    List.of("feishu"), false, List.of());
            }

            @Override
            public Snapshot attach(AttachRequest request) {
                lastAttach.set(request);
                return snapshot();
            }

            @Override
            public Snapshot detach(DetachRequest request) {
                lastDetach.set(request);
                return snapshot();
            }

            @Override
            public SetupResult setup(SetupRequest request, Consumer<String> progress) {
                progress.accept("step-1");
                return new SetupResult(true, "feishu", "ready");
            }
        };

        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest req) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });

        Path recordFile = tempDir.resolve("web-gateway.json");
        String token = "0123456789abcdef0123456789abcdef";
        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), token, registry)
                .ccConnect(stubPort)
                .build()) {
            server.start();
            String url = "http://127.0.0.1:" + server.port() + "/?token=" + token;
            GatewaySupervisorPort supervisor = () -> {
                try {
                    WebGatewayRecord.write(recordFile, new WebGatewayRecord(
                        ProcessHandle.current().pid(), url, "test", System.currentTimeMillis()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return new GatewaySupervisorPort.Started(url);
            };

            WebGatewayCollaborationBridge bridge = new WebGatewayCollaborationBridge(
                supervisor, recordFile, tempDir.resolve("missing.toml"), tempDir.toString(), 9999L);

            // Attach triggers supervisor.start() and POSTs to /api/cc-connect/attach.
            bridge.attachChannel("sess-abc", "feishu").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(lastAttach.get());
            assertEquals(9999L, lastAttach.get().pid());
            assertEquals("sess-abc", lastAttach.get().sessionId());
            assertEquals("feishu", lastAttach.get().channel());
            assertEquals("tui", lastAttach.get().origin());

            // Now that web-gateway.json is live, configuredChannels reads from Web daemon.
            assertTrue(bridge.configured());
            assertEquals(List.of("feishu"), bridge.configuredChannels());

            // Detach POSTs to /api/cc-connect/detach.
            bridge.detachChannel("sess-abc").toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertNotNull(lastDetach.get());
            assertEquals(9999L, lastDetach.get().pid());

            // Setup streams NDJSON progress and returns result.
            List<String> progress = new ArrayList<>();
            CollaborationSetupPort.Result result = bridge.setup(
                new CollaborationSetupPort.Request(CollaborationSetupPort.Mode.CREATE, "", new char[0]),
                progress::add).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("feishu", result.channel());
            assertEquals(List.of("step-1"), progress);
        }
    }
}
