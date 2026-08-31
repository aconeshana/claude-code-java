package com.claudecode.tools.mcp;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConnection;
import com.claudecode.mcp.McpPromptInfo;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.mcp.McpTransport;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.skills.SkillLoader;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class McpToolProviderTest {

    @Test
    void providerKeepsOAuthTransportColdUntilAuthenticationIsUsed() throws Exception {
        var provider = new McpToolProvider(new RecordingManager(), 25);

        Field oauth = McpToolProvider.class.getDeclaredField("oauthProvider");
        oauth.setAccessible(true);
        assertNull(oauth.get(provider));

        provider.close();
    }

    @Test
    void resourceCapabilityRegistersReleasedResourceToolsWithoutMcpSkillsGate() throws Exception {
        var manager = new RecordingManager();
        manager.resourceCapabilityNames = Set.of("resources");
        manager.releaseConnect.countDown();
        var registry = new ToolRegistry();
        var provider = new McpToolProvider(manager, 25);

        provider.initialize(new McpConfig(Map.of(
            "resources", server("resources", false))), registry);
        provider.whenReady().get(1, TimeUnit.SECONDS);

        assertEquals(resourceHelperNames(), registeredResourceHelperNames(registry));
        assertEquals(List.of("resources"), manager.resourcePrefetchNames);
        provider.close();
    }

    @Test
    void mcpSkillsGateDoesNotRegisterResourceToolsWithoutResourceCapability() throws Exception {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        var registry = new ToolRegistry();
        var provider = new McpToolProvider(manager, 25);
        provider.configureMcpSkills(true, new SkillLoader(), Path.of("/tmp/mcp-skill-cache"));

        provider.initialize(new McpConfig(Map.of(
            "tools-only", server("tools-only", false))), registry);
        provider.whenReady().get(1, TimeUnit.SECONDS);

        assertEquals(List.of(), registeredResourceHelperNames(registry));
        provider.close();
    }

    @Test
    void resourceHelpersRemainUntilLastResourceCapableServerDisconnects() throws Exception {
        var manager = new RecordingManager();
        manager.resourceCapabilityNames = Set.of("first", "second");
        manager.releaseConnect.countDown();
        var registry = new ToolRegistry();
        var provider = new McpToolProvider(manager, 25);

        provider.initialize(new McpConfig(Map.of(
            "first", server("first", false),
            "second", server("second", false))), registry);
        provider.whenReady().get(1, TimeUnit.SECONDS);
        assertEquals(resourceHelperNames(), registeredResourceHelperNames(registry));

        provider.toggleServer("first", false);
        assertEquals(resourceHelperNames(), registeredResourceHelperNames(registry));

        provider.toggleServer("second", false);
        assertEquals(List.of(), registeredResourceHelperNames(registry));
        provider.close();
    }

    private static List<String> resourceHelperNames() {
        return List.of(
            "ListMcpResourcesTool", "ReadMcpResourceDirTool", "ReadMcpResourceTool");
    }

    private static List<String> registeredResourceHelperNames(ToolRegistry registry) {
        return registry.getToolDefinitions().stream().map(StreamingClient.StreamRequest.ToolDef::name)
            .filter(resourceHelperNames()::contains)
            .sorted().toList();
    }

    @Test
    void providerIsAutoCloseableAndClosesItsManager() {
        var manager = new RecordingManager();

        try (var ignored = new McpToolProvider(manager)) {
            assertTrue(AutoCloseable.class.isAssignableFrom(McpToolProvider.class));
        }

        assertTrue(manager.closed);
    }

    @Test
    void runtimeViewDoesNotExposeProviderShutdown() {
        assertTrue(ManagedMcpRuntime.class.isAssignableFrom(McpToolProvider.class));
        assertFalse(AutoCloseable.class.isAssignableFrom(McpRuntime.class));
    }

    @Test
    void waitToolIsVisibleOnlyWhileAConfiguredServerIsPending() throws Exception {
        var manager = new RecordingManager();
        var registry = new ToolRegistry();
        var config = new McpConfig(Map.of("slow", server("slow", false)));

        try (var provider = new McpToolProvider(manager, 25)) {
            provider.initialize(config, registry);
            assertTrue(manager.connectStarted.await(1, TimeUnit.SECONDS));
            assertTrue(provider.hasPendingServers());
            assertTrue(registry.getToolDefinitions().stream()
                .anyMatch(tool -> Strings.CS.equals(tool.name(), "WaitForMcpServers")));

            WaitForMcpServersTool.WaitResult pending = provider.waitForServers(List.of());
            assertFalse(pending.ready());
            assertEquals(List.of("slow"), pending.stillPending());

            manager.releaseConnect.countDown();
            provider.whenReady().get(1, TimeUnit.SECONDS);
            assertFalse(provider.hasPendingServers());
            assertFalse(registry.getToolDefinitions().stream()
                .anyMatch(tool -> Strings.CS.equals(tool.name(), "WaitForMcpServers")));
        }
    }

    @Test
    void waitClassifiesConnectedFailedDisabledAndUnknownServers() throws Exception {
        var manager = new RecordingManager();
        manager.failNames = Set.of("failed");
        manager.releaseConnect.countDown();
        var registry = new ToolRegistry();
        var config = new McpConfig(Map.of(
            "connected", server("connected", false),
            "failed", server("failed", false),
            "disabled", server("disabled", true)));

        try (var provider = new McpToolProvider(manager, 100)) {
            provider.initialize(config, registry);
            provider.whenReady().get(1, TimeUnit.SECONDS);

            WaitForMcpServersTool.WaitResult result = provider.waitForServers(
                List.of("connected", "failed", "disabled", "missing"));

            assertFalse(result.ready());
            assertEquals(List.of("connected"), result.connected());
            assertEquals(List.of("failed"), result.failed());
            assertEquals(List.of(), result.stillPending());
            assertEquals(List.of(), result.needsAuth());
            assertEquals(List.of("disabled"), result.disabled());
            assertEquals(List.of("missing"), result.unknown());
        }
    }

    @Test
    void sdkDynamicServersReconnectToggleAndReplaceAgainstTheLiveRegistry() throws Exception {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        var provider = new McpToolProvider(manager, 100);
        provider.initialize(new McpConfig(Map.of("sdk", server("sdk", false))),
            new ToolRegistry());
        provider.whenReady().get(1, TimeUnit.SECONDS);

        provider.reconnectServer("sdk");
        assertEquals(2, manager.connectCount);
        assertEquals(1, manager.disconnectCount);

        provider.toggleServer("sdk", false);
        assertEquals("disabled", provider.snapshotServerStatuses().get("sdk"));
        provider.toggleServer("sdk", true);
        assertEquals("connected", provider.snapshotServerStatuses().get("sdk"));

        McpToolProvider.DynamicServerUpdate update = provider.setDynamicServers(
            Map.of("next", server("next", false)));
        assertEquals(List.of("next"), update.added());
        assertEquals(List.of("sdk"), update.removed());
        provider.waitForServers(List.of("next"));
        assertEquals("connected", provider.snapshotServerStatuses().get("next"));
        assertThrows(IllegalArgumentException.class,
            () -> provider.reconnectServer("missing"));
        provider.close();
    }

    @Test
    void dynamicServerConnectionFailuresAreReturnedBySetDynamicServers() {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.failNames = Set.of("broken");

        try (var provider = new McpToolProvider(manager, 100)) {
            provider.initialize(new McpConfig(Map.of()), new ToolRegistry());

            McpToolProvider.DynamicServerUpdate update = provider.setDynamicServers(
                Map.of("broken", server("broken", false)));

            assertEquals("failed broken", update.errors().get("broken"));
            assertEquals("failed", provider.snapshotServerStatuses().get("broken"));
        }
    }

    @Test
    void needsAuthServersAreAddedWithoutASetServersError() {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.authFailNames = Set.of("oauth");

        try (var provider = new McpToolProvider(manager, 100)) {
            provider.initialize(new McpConfig(Map.of()), new ToolRegistry());

            McpToolProvider.DynamicServerUpdate update = provider.setDynamicServers(
                Map.of("oauth", server("oauth", false)));

            assertEquals(List.of("oauth"), update.added());
            assertTrue(update.errors().isEmpty());
            assertEquals("needs-auth", provider.snapshotServerStatuses().get("oauth"));
        }
    }

    @Test
    void remoteNeedsAuthRegistersPerServerAuthenticationPseudoTool() {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.authFailNames = Set.of("oauth-http");
        var registry = new ToolRegistry();
        var remote = new McpServerConfig("oauth-http", null, List.of(), Map.of(), false,
            "http", "https://mcp.example.test", Map.of());

        try (var provider = new McpToolProvider(manager, 100)) {
            provider.initialize(new McpConfig(Map.of()), registry);
            provider.setDynamicServers(Map.of("oauth-http", remote));

            var definition = registry.getToolDefinitions().stream()
                .filter(tool ->Strings.CS.equals( tool.name(), "mcp__oauth-http__authenticate"))
                .findFirst().orElseThrow();
            assertTrue(Strings.CS.contains(definition.description(), "oauth-http"), definition.description());
            assertTrue(Strings.CS.contains(definition.description(), "authorization URL"));
        }
    }

    @Test
    void sdkHostedServersStaySeparateFromProcessDynamicServersAndUseSdkStatusShape()
            throws Exception {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.toolsByServer = Map.of("sdk-wire", List.of(new McpToolInfo(
            "sdk-wire", "sdk_echo", "Echo", JsonUtils.getMapper().createObjectNode())));
        manager.serverInfoByServer = Map.of("sdk-wire",
            JsonUtils.getMapper().createObjectNode()
                .put("name", "wire-sdk").put("title", "Wire SDK Server")
                .put("version", "1.0.0"));
        var registry = new ToolRegistry();
        var provider = new McpToolProvider(manager, 100);
        provider.initialize(new McpConfig(Map.of()), registry);
        provider.whenReady().get(1, TimeUnit.SECONDS);

        provider.setSdkServers(List.of("sdk-wire"));
        McpToolProvider.DynamicServerUpdate processUpdate = provider.setDynamicServers(
            Map.of("process-wire", server("process-wire", false)));
        provider.waitForServers(List.of("process-wire"));

        assertEquals(List.of("process-wire"), processUpdate.added());
        assertEquals(List.of(), processUpdate.removed(),
            "mcp_set_servers must not delete initialize.sdkMcpServers entries");
        assertEquals(Set.of("process-wire", "sdk-wire"),
            provider.snapshotServerStatuses().keySet());
        McpToolProvider.ServerStatusSnapshot sdk = provider.snapshotServerDetails().stream()
            .filter(status -> Strings.CS.equals(status.name(), "sdk-wire"))
            .findFirst().orElseThrow();
        assertEquals("connected", sdk.status());
        assertEquals("dynamic", sdk.scope());
        assertNull(sdk.serverInfo());
        assertNull(sdk.config());
        assertNull(sdk.capabilities());
        assertEquals("sdk_echo", sdk.tools().getFirst().name());
        assertTrue(sdk.tools().getFirst().annotations().isEmpty());
        McpToolProvider.ToolDisplaySnapshot display = provider.snapshotToolDisplays().stream()
            .filter(tool -> Strings.CS.equals(tool.toolName(), "mcp__sdk-wire__sdk_echo"))
            .findFirst().orElseThrow();
        assertEquals("Sdk Echo", display.displayName());
        assertEquals("sdk-wire", display.serverDisplayName(),
            "2.1.197 SDK clients use the configured bridge name, not serverInfo.name/title");
        assertTrue(registry.getToolDefinitions().stream()
            .anyMatch(tool -> Strings.CS.equals(tool.name(), "mcp__sdk-wire__sdk_echo")));
        provider.close();
    }

    @Test
    void sdkStatusSnapshotMatchesPendingAndConnectedWireShapes() throws Exception {
        var manager = new RecordingManager();
        ObjectNode rawAnnotations = JsonUtils.getMapper().createObjectNode();
        rawAnnotations.put("title", "Echo Marker");
        rawAnnotations.put("readOnlyHint", true);
        rawAnnotations.put("destructiveHint", false);
        rawAnnotations.put("openWorldHint", true);
        manager.toolsByServer = Map.of("wire", List.of(new McpToolInfo(
            "wire", "echo_marker", "Echo", JsonUtils.getMapper().createObjectNode(),
            rawAnnotations)));
        manager.serverInfoByServer = Map.of("wire", JsonUtils.getMapper().createObjectNode()
            .put("name", "wire-fake-mcp").put("version", "1.0.0"));
        ObjectNode capabilities = JsonUtils.getMapper().createObjectNode();
        capabilities.putObject("experimental").put("claude/channel", true);
        manager.serverCapabilitiesByServer = Map.of("wire", capabilities);
        var provider = new McpToolProvider(manager, 100);
        McpServerConfig server = new McpServerConfig(
            "wire", "/usr/bin/python3", List.of("fake.py"), Map.of(), false, "stdio");
        provider.initialize(new McpConfig(
            Map.of("wire", server), Map.of("wire", McpServerScope.PROJECT)),
            new ToolRegistry());
        assertTrue(manager.connectStarted.await(1, TimeUnit.SECONDS));

        McpToolProvider.ServerStatusSnapshot pending =
            provider.snapshotServerDetails().getFirst();
        assertEquals("wire", pending.name());
        assertEquals("pending", pending.status());
        assertEquals("project", pending.scope());
        assertEquals("stdio", pending.config().path("type").asText());
        assertEquals("/usr/bin/python3", pending.config().path("command").asText());
        assertEquals("fake.py", pending.config().path("args").get(0).asText());
        assertNull(pending.serverInfo());
        assertNull(pending.tools());

        manager.releaseConnect.countDown();
        provider.whenReady().get(1, TimeUnit.SECONDS);

        McpToolProvider.ServerStatusSnapshot connected =
            provider.snapshotServerDetails().getFirst();
        assertEquals("connected", connected.status());
        assertEquals("wire-fake-mcp", connected.serverInfo().path("name").asText());
        assertTrue(connected.capabilities().path("experimental")
            .path("claude/channel").asBoolean());
        assertEquals("echo_marker", connected.tools().getFirst().name());
        assertTrue(connected.tools().getFirst().annotations().path("readOnly").asBoolean());
        assertTrue(connected.tools().getFirst().annotations().path("openWorld").asBoolean());
        assertFalse(connected.tools().getFirst().annotations().has("destructive"));
        McpToolProvider.ToolDisplaySnapshot display =
            provider.snapshotToolDisplays().getFirst();
        assertEquals("mcp__wire__echo_marker", display.toolName());
        assertEquals("Echo Marker", display.displayName());
        assertEquals("wire-fake-mcp", display.serverDisplayName());

        manager.connectedNames.remove("wire");
        assertEquals(List.of(display), provider.snapshotToolDisplays(),
            "SDK tool_use_meta must survive a transient raw-transport drop");
        provider.close();
    }

    @Test
    void promptSyncPublishesStableSdkCommandSnapshotAndDisconnectClearsIt() throws Exception {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.promptsByServer = Map.of(
            "zeta", List.of(new McpPromptInfo("zeta", "second", "", List.of())),
            "alpha", List.of(new McpPromptInfo("alpha", "first", "", List.of())));
        var provider = new McpToolProvider(manager, 100);
        provider.initialize(new McpConfig(Map.of(
            "zeta", server("zeta", false),
            "alpha", server("alpha", false))), new ToolRegistry());
        provider.whenReady().get(1, TimeUnit.SECONDS);

        provider.syncPromptsToRegistry(_ -> { });

        assertEquals(List.of("mcp__alpha__first", "mcp__zeta__second"),
            provider.promptCommandNames());
        provider.toggleServer("alpha", false);
        assertEquals(List.of("mcp__zeta__second"), provider.promptCommandNames());
        provider.close();
    }

    @Test
    void connectionReadinessPreloadsPromptNamesForNormalSdkInit() throws Exception {
        var manager = new RecordingManager();
        manager.releaseConnect.countDown();
        manager.promptsByServer = Map.of(
            "alpha", List.of(new McpPromptInfo("alpha", "first", "", List.of())));
        var provider = new McpToolProvider(manager, 100);
        provider.initialize(new McpConfig(Map.of("alpha", server("alpha", false))),
            new ToolRegistry());

        provider.whenReady().get(1, TimeUnit.SECONDS);

        assertEquals(List.of("mcp__alpha__first"), provider.promptCommandNames());
        provider.close();
    }

    private static McpServerConfig server(String name, boolean disabled) {
        return new McpServerConfig(name, "fake", List.of(), Map.of(), disabled, "stdio");
    }

    private static final class RecordingManager extends McpClientManager {
        private boolean closed;
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch releaseConnect = new CountDownLatch(1);
        private volatile Set<String> failNames = Set.of();
        private volatile Set<String> authFailNames = Set.of();
        private volatile Set<String> resourceCapabilityNames = Set.of();
        private volatile Map<String, List<McpPromptInfo>> promptsByServer = Map.of();
        private volatile Map<String, List<McpToolInfo>> toolsByServer = Map.of();
        private volatile Map<String, JsonNode> serverInfoByServer = Map.of();
        private volatile Map<String, JsonNode> serverCapabilitiesByServer = Map.of();
        private final List<String> resourcePrefetchNames =
            new CopyOnWriteArrayList<>();
        private final Set<String> connectedNames = ConcurrentHashMap.newKeySet();
        private volatile int connectCount;
        private volatile int disconnectCount;

        @Override
        public void connect(McpServerConfig config) {
            connectCount++;
            connectStarted.countDown();
            try {
                if (!releaseConnect.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test connect was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (failNames.contains(config.name())) {
                throw new IllegalStateException("failed " + config.name());
            }
            if (authFailNames.contains(config.name())) {
                throw new IllegalStateException("401 Unauthorized");
            }
            connectedNames.add(config.name());
        }

        @Override
        public void disconnect(String serverId) {
            disconnectCount++;
            connectedNames.remove(serverId);
        }

        @Override
        public List<McpToolInfo> listToolsForServer(String serverId) {
            return toolsByServer.getOrDefault(serverId, List.of());
        }

        @Override
        public Set<String> getConnectedServerIds() {
            return Set.copyOf(connectedNames);
        }

        @Override
        public Optional<McpConnection> getConnection(String serverId) {
            if (!connectedNames.contains(serverId)) return Optional.empty();
            McpServerConfig config = server(serverId, false);
            return Optional.of(new McpConnection(config, new ConnectedTransport()) {
                @Override
                public JsonNode getServerInfo() {
                    return serverInfoByServer.get(serverId);
                }

                @Override
                public JsonNode getServerCapabilities() {
                    return serverCapabilitiesByServer.get(serverId);
                }

                @Override
                public boolean hasCapability(String key) {
                    return Strings.CS.equals("resources", key)
                        && resourceCapabilityNames.contains(serverId);
                }
            });
        }

        @Override
        public List<McpPromptInfo> listPromptsForServer(String serverId) {
            return promptsByServer.getOrDefault(serverId, List.of());
        }

        @Override
        public List<JsonNode> listResourcesForServer(String serverId) {
            resourcePrefetchNames.add(serverId);
            return List.of();
        }

        @Override
        public void close() {
            closed = true;
            connectedNames.clear();
            releaseConnect.countDown();
        }
    }

    private static final class ConnectedTransport implements McpTransport {
        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            throw new UnsupportedOperationException("not used by provider lifecycle tests");
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() { }
    }
}
