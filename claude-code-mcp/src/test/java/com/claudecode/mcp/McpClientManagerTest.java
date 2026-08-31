package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpClientManager}.
 * Uses a fake in-memory transport to avoid subprocess dependencies.
 */
class McpClientManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private McpClientManager manager;

    @BeforeEach
    void setUp() {
        manager = new McpClientManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    @Test
    void connectAndGetConnection() {
        McpServerConfig config = new McpServerConfig(
            "test-server", "echo", List.of(), null, false, "stdio");

        // We can't actually connect to "echo" as MCP, but we can test the manager
        // by verifying it creates a connection entry. The process will start and die quickly.
        // Instead, test with a fake transport via subclass.
        var testManager = new TestableClientManager();
        testManager.connect(config);
        McpConnection conn = testManager.getConnection("test-server").orElseThrow();

        assertNotNull(conn);
        assertEquals("test-server", conn.getServerId());
        assertTrue(testManager.getConnection("test-server").isPresent());
    }

    @Test
    void disconnectRemovesConnection() {
        var testManager = new TestableClientManager();
        McpServerConfig config = new McpServerConfig(
            "srv1", "cmd", List.of(), null, false, "stdio");

        testManager.connect(config);
        assertTrue(testManager.getConnection("srv1").isPresent());

        testManager.disconnect("srv1");
        assertFalse(testManager.getConnection("srv1").isPresent());
    }

    @Test
    void disconnectNonExistentIsNoOp() {
        assertDoesNotThrow(() -> manager.disconnect("nonexistent"));
    }

    @Test
    void getConnectionReturnsEmptyForUnknown() {
        assertEquals(Optional.empty(), manager.getConnection("unknown"));
    }

    @Test
    void borrowConnectionExposesNonOwningView() {
        var testManager = new TestableClientManager();
        testManager.connect(new McpServerConfig(
            "borrowed", "cmd", List.of(), Map.of(), false, "stdio"));

        McpConnectionView view = testManager.borrowConnection("borrowed").orElseThrow();

        assertEquals("borrowed", view.getServerId());
        assertFalse(AutoCloseable.class.isAssignableFrom(McpConnectionView.class));
        assertNotNull(view.sendRequest("tools/list", null));
    }

    @Test
    void connectDisabledServerThrows() {
        McpServerConfig config = new McpServerConfig(
            "disabled-srv", "cmd", List.of(), null, true, "stdio");

        assertThrows(McpException.class, () -> manager.connect(config));
    }

    @Test
    void listToolsAggregatesFromAllServers() {
        var testManager = new TestableClientManager();

        McpServerConfig config1 = new McpServerConfig(
            "srv1", "cmd1", List.of(), null, false, "stdio");
        McpServerConfig config2 = new McpServerConfig(
            "srv2", "cmd2", List.of(), null, false, "stdio");

        testManager.connect(config1);
        testManager.connect(config2);

        List<McpToolInfo> tools = testManager.listTools();
        // Each fake server returns 1 tool
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> Strings.CS.equals(t.serverId(), "srv1")));
        assertTrue(tools.stream().anyMatch(t -> Strings.CS.equals(t.serverId(), "srv2")));
    }

    @Test
    void callToolDelegatesToTransport() {
        var testManager = new TestableClientManager();
        McpServerConfig config = new McpServerConfig(
            "srv1", "cmd", List.of(), null, false, "stdio");
        testManager.connect(config);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("key", "value");

        JsonNode result = testManager.callTool("srv1", "test-tool", args);
        assertNotNull(result);
        assertEquals("ok", result.get("status").asText());
    }

    @Test
    void elicitationBridgeReceivesActiveToolUseIdsOnlyDuringTheToolCall() {
        var eliciting = new ElicitationClientManager();
        AtomicReference<Set<String>> observed = new AtomicReference<>();
        eliciting.setElicitationHandler((_, _, activeToolUseIds) -> {
            observed.set(activeToolUseIds);
            return MAPPER.createObjectNode().put("action", "cancel");
        });
        try {
            eliciting.connect(new McpServerConfig(
                "wire-elicit", "ignored", List.of(), Map.of(), false, "stdio"));

            JsonNode result = eliciting.callTool(
                "wire-elicit", "echo_marker", MAPPER.createObjectNode(), "toolu_elicit");

            assertEquals("cancel", result.path("elicitationAction").asText());
            assertEquals(Set.of("toolu_elicit"), observed.get());

            observed.set(null);
            eliciting.transport.requestHandlers.get("elicitation/create")
                .handle(MAPPER.createObjectNode());
            assertEquals(Set.of(), observed.get(),
                "completed tool calls must not leak ordering ids into later elicitations");
        } finally {
            eliciting.close();
        }
    }

    @Test
    void callToolOnDisconnectedServerThrows() {
        assertThrows(McpException.class,
            () -> manager.callTool("nonexistent", "tool", null));
    }

    @Test
    void sdkConnectionsUseTheReleasedEmptyClientCapabilitiesAndNoRootsHandler() {
        var sdkManager = new SdkHandshakeClientManager();
        try {
            sdkManager.connect(new McpServerConfig(
                "sdk-wire", null, List.of(), Map.of(), false, "sdk"));

            assertTrue(sdkManager.transport.initializeParams.path("capabilities").isEmpty());
            assertEquals("claude-code",
                sdkManager.transport.initializeParams.path("clientInfo").path("name").asText());
            assertFalse(sdkManager.transport.requestHandlers.containsKey("roots/list"));
            assertFalse(sdkManager.transport.requestHandlers.containsKey("elicitation/create"));
            assertEquals(List.of("initialize", "notifications/initialized"),
                sdkManager.transport.methods);
        } finally {
            sdkManager.close();
        }
    }

    @Test
    void callToolReconnectsAConfiguredServerAfterItsTransportCloses() throws Exception {
        var reconnecting = new ReconnectingClientManager();
        McpServerConfig config = new McpServerConfig(
            "drop-server", "ignored", List.of(), Map.of(), false, "stdio");
        try {
            reconnecting.connect(config);
            reconnecting.listToolsForServer("drop-server");
            ReconnectTransport first = reconnecting.transports.getFirst();
            first.close();

            JsonNode result = reconnecting.callTool(
                "drop-server", "echo_marker", MAPPER.createObjectNode());

            assertEquals(2, reconnecting.transports.size(),
                "the next operation should create a fresh stdio client like ensureConnectedClient");
            assertEquals(2, result.path("generation").asInt());
            reconnecting.listToolsForServer("drop-server");
            assertEquals(List.of("initialize", "notifications/initialized", "tools/call"),
                reconnecting.transports.get(1).methods,
                "reconnect re-handshakes and retains the server-name tool cache");
        } finally {
            reconnecting.close();
        }
    }

    @Test
    void urlElicitationIsHandledAndRetriedAtMostThreeTimes() {
        var eliciting = new UrlElicitationRetryManager();
        AtomicReference<Set<String>> observedIds = new AtomicReference<>();
        eliciting.setElicitationHandler((_, _, activeIds) -> {
            observedIds.set(activeIds);
            return MAPPER.createObjectNode().put("action", "accept");
        });
        try {
            eliciting.connect(new McpServerConfig(
                "url-elicit", "ignored", List.of(), Map.of(), false, "stdio"));

            JsonNode result = eliciting.callTool(
                "url-elicit", "needs_auth", MAPPER.createObjectNode(), "toolu_url");

            assertEquals("retried", result.path("status").asText());
            assertEquals(Set.of("toolu_url"), observedIds.get());
            assertEquals(2, eliciting.transport.toolCalls);
        } finally {
            eliciting.close();
        }
    }

    @Test
    void declinedUrlElicitationReturnsTerminalToolErrorWithoutRetry() {
        var eliciting = new UrlElicitationRetryManager();
        eliciting.setElicitationHandler((_, _, _) ->
            MAPPER.createObjectNode().put("action", "cancel"));
        try {
            eliciting.connect(new McpServerConfig(
                "url-elicit", "ignored", List.of(), Map.of(), false, "stdio"));

            JsonNode result = eliciting.callTool(
                "url-elicit", "needs_auth", MAPPER.createObjectNode(), "toolu_url_cancel");

            assertTrue(result.path("isError").asBoolean());
            assertEquals("cancel", result.path("elicitationAction").asText());
            assertTrue(Strings.CS.contains(result.path("content").get(0).path("text").asText(), "could not complete"));
            assertEquals(1, eliciting.transport.toolCalls,
                "decline/cancel must not retry the MCP tool call");
        } finally {
            eliciting.close();
        }
    }

    @Test
    void serverInstructionsRemainAnnouncedAcrossUnexpectedTransportClosure() throws Exception {
        var reconnecting = new ReconnectingClientManager();
        McpServerConfig config = new McpServerConfig(
            "drop-server", "ignored", List.of(), Map.of(), false, "stdio");
        try {
            reconnecting.connect(config);
            assertEquals(Map.of("drop-server", "WIRE197 reconnect instructions."),
                reconnecting.getServerInstructions());

            reconnecting.transports.getFirst().close();
            assertEquals(Map.of("drop-server", "WIRE197 reconnect instructions."),
                reconnecting.getServerInstructions(),
                "a transient process drop must not retract already-announced instructions");

            reconnecting.disconnect("drop-server");
            assertEquals(Map.of(), reconnecting.getServerInstructions(),
                "an explicit configuration disconnect should retract the instructions");
        } finally {
            reconnecting.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listPromptsSkipsRequestWhenServerDidNotDeclarePromptsCapability() {
        String script = """
            import json,sys
            for line in sys.stdin:
             request=json.loads(line)
             if 'id' not in request: continue
             method=request.get('method')
             if method == 'initialize': result={'protocolVersion':'2025-11-25',\
            'capabilities':{'tools':{}},'serverInfo':{'name':'no-prompts','version':'1'}}
             elif method == 'prompts/list': result={'prompts':[{'name':'must-not-fetch'}]}
             else: result={}
             print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
            """;
        McpServerConfig config = new McpServerConfig(
            "no-prompts", "python3", List.of("-u", "-c", script),
            Map.of(), false, "stdio");

        manager.connect(config);

        assertEquals(List.of(), manager.listPromptsForServer("no-prompts"),
            "prompts/list must be capability-gated like the official MCP SDK client");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void toolDiscoveryPreservesAnnotationsForRuntimeSemanticsAndSdkStatus() {
        String script = """
            import json,sys
            for line in sys.stdin:
             request=json.loads(line)
             if 'id' not in request: continue
             method=request.get('method')
             if method == 'initialize': result={'protocolVersion':'2025-11-25',\
            'capabilities':{'tools':{}},'serverInfo':{'name':'annotated','version':'1'}}
             elif method == 'tools/list': result={'tools':[{'name':'search','inputSchema':{},\
            'annotations':{'title':'Search','readOnlyHint':True,'openWorldHint':True}}]}
             else: result={}
             print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':result}), flush=True)
            """;
        McpServerConfig config = new McpServerConfig(
            "annotated", "python3", List.of("-u", "-c", script),
            Map.of(), false, "stdio");

        manager.connect(config);
        McpToolInfo tool = manager.listToolsForServer("annotated").getFirst();

        assertEquals("Search", tool.annotations().path("title").asText());
        assertTrue(tool.annotations().path("readOnlyHint").asBoolean());
        assertTrue(tool.annotations().path("openWorldHint").asBoolean());
    }

    @Test
    void closeDisconnectsAll() throws Exception {
        var testManager = new TestableClientManager();
        testManager.connect(new McpServerConfig("s1", "c", List.of(), null, false, "stdio"));
        testManager.connect(new McpServerConfig("s2", "c", List.of(), null, false, "stdio"));

        assertEquals(2, testManager.getConnectedServerIds().size());
        testManager.close();
        assertEquals(0, testManager.getConnectedServerIds().size());
    }

    @Test
    void managerIsAutoCloseableResourceOwner() {
        assertTrue(AutoCloseable.class.isAssignableFrom(McpClientManager.class));
    }

    @Test
    void runtimeViewCannotCloseManagerOwnedResources() {
        assertTrue(McpClientRuntime.class.isAssignableFrom(McpClientManager.class));
        assertFalse(AutoCloseable.class.isAssignableFrom(McpClientRuntime.class));
    }

    @Test
    void startTransport_closesTransportWhenStartupFails() throws Exception {
        Method startTransport = McpClientManager.class.getDeclaredMethod(
            "startTransport", McpTransport.class, Consumer.class);
        startTransport.setAccessible(true);
        FakeTransport transport = new FakeTransport();
        IllegalStateException startupFailure = new IllegalStateException("startup failed");
        Consumer<McpTransport> starter = _ -> { throw startupFailure; };

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> startTransport.invoke(null, transport, starter));

        assertSame(startupFailure, thrown.getCause());
        assertFalse(transport.isConnected(),
            "a transport whose startup fails must be closed before the error escapes");
    }

    // ── connectionSummary (backs /status's "MCP servers" row) ───────────────

    @Test
    void connectionSummary_noServers_returnsEmptyString() {
        assertEquals("", manager.connectionSummary());
    }

    @Test
    void connectionSummary_allConnected_omitsTotalCount() {
        var testManager = new TestableClientManager();
        testManager.connect(new McpServerConfig("s1", "c", List.of(), null, false, "stdio"));
        testManager.connect(new McpServerConfig("s2", "c", List.of(), null, false, "stdio"));

        assertEquals("2 connected", testManager.connectionSummary());
    }

    @Test
    void connectionSummary_someDisconnected_showsConnectedAndTotal() throws Exception {
        var testManager = new TestableClientManager();
        testManager.connect(new McpServerConfig("s1", "c", List.of(), null, false, "stdio"));
        testManager.connect(new McpServerConfig("s2", "c", List.of(), null, false, "stdio"));
        McpConnection dead = testManager.getConnection("s2").orElseThrow();
        dead.getTransport().close(); // still present in the map, just no longer connected

        assertEquals("1 connected, 2 total", testManager.connectionSummary());
    }

    /**
     * Testable subclass that uses a fake transport instead of real subprocesses.
     */
    static class TestableClientManager extends McpClientManager {
        @Override
        public void connect(McpServerConfig config) {
            if (config.disabled()) {
                throw new McpException("Cannot connect to disabled server '" + config.name() + "'");
            }
            disconnect(config.name());
            FakeTransport transport = new FakeTransport();
            // Use reflection-free approach: store in parent via public API
            // We need to access the parent's connections map, so we override connect entirely
            connectWithTransport(config, transport);
        }

        McpConnection connectWithTransport(McpServerConfig config, McpTransport transport) {
            // Call disconnect to clean up any existing connection
            disconnect(config.name());
            McpConnection connection = new McpConnection(config, transport);
            // We need to store this — use the parent's field via a workaround
            // Since connections is private, we'll use a local map
            getConnectionsMap().put(config.name(), connection);
            return connection;
        }

        private final Map<String, McpConnection> localConnections = new ConcurrentHashMap<>();

        Map<String, McpConnection> getConnectionsMap() {
            return localConnections;
        }

        @Override
        public Optional<McpConnection> getConnection(String serverId) {
            McpConnection conn = localConnections.get(serverId);
            if (conn != null && conn.isConnected()) {
                return Optional.of(conn);
            }
            return Optional.empty();
        }

        @Override
        public void disconnect(String serverId) {
            McpConnection conn = localConnections.remove(serverId);
            if (conn != null) {
                try { conn.close(); } catch (Exception _) {}
            }
        }

        @Override
        public Set<String> getConnectedServerIds() {
            Set<String> ids = new HashSet<>();
            for (var entry : localConnections.entrySet()) {
                if (entry.getValue().isConnected()) {
                    ids.add(entry.getKey());
                }
            }
            return ids;
        }

        @Override
        public String connectionSummary() {
            int total = localConnections.size();
            if (total == 0) return "";
            long connected = localConnections.values().stream().filter(McpConnection::isConnected).count();
            return connected == total
                ? connected + " connected"
                : connected + " connected, " + total + " total";
        }

        @Override
        public List<McpToolInfo> listTools() {
            List<McpToolInfo> allTools = new ArrayList<>();
            for (McpConnection conn : localConnections.values()) {
                if (!conn.isConnected()) continue;
                JsonNode result = conn.getTransport().sendRequest("tools/list", null);
                JsonNode toolsNode = result.get("tools");
                if (toolsNode != null && toolsNode.isArray()) {
                    for (JsonNode toolNode : toolsNode) {
                        allTools.add(new McpToolInfo(
                            conn.getServerId(),
                            toolNode.get("name").asText(),
                            toolNode.has("description") ? toolNode.get("description").asText() : "",
                            MAPPER.createObjectNode()));
                    }
                }
            }
            return allTools;
        }

        @Override
        public JsonNode callTool(String serverId, String toolName, JsonNode args) {
            McpConnection conn = localConnections.get(serverId);
            if (conn == null || !conn.isConnected()) {
                throw new McpException("No active connection to server '" + serverId + "'");
            }
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            if (args != null) params.set("arguments", args);
            return conn.getTransport().sendRequest("tools/call", params);
        }

        @Override
        public void close() {
            for (String id : new ArrayList<>(localConnections.keySet())) {
                disconnect(id);
            }
        }
    }

    /**
     * Fake transport that returns canned responses.
     */
    static class FakeTransport implements McpTransport {
        private boolean connected = true;

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            return switch (method) {
                case "tools/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    var tools = MAPPER.createArrayNode();
                    ObjectNode tool = MAPPER.createObjectNode();
                    tool.put("name", "fake-tool");
                    tool.put("description", "A fake tool");
                    tools.add(tool);
                    result.set("tools", tools);
                    yield result;
                }
                case "tools/call" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("status", "ok");
                    yield result;
                }
                case "resources/list" -> {
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("resources", MAPPER.createArrayNode());
                    yield result;
                }
                default -> MAPPER.createObjectNode();
            };
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
        }
    }

    static final class ReconnectingClientManager extends McpClientManager {
        final List<ReconnectTransport> transports = new ArrayList<>();

        @Override
        McpTransport createTransport(McpServerConfig config) {
            ReconnectTransport transport = new ReconnectTransport(transports.size() + 1);
            transports.add(transport);
            return transport;
        }
    }

    static final class SdkHandshakeClientManager extends McpClientManager {
        final SdkHandshakeTransport transport = new SdkHandshakeTransport();

        @Override
        McpTransport createTransport(McpServerConfig config) {
            return transport;
        }
    }

    static final class ElicitationClientManager extends McpClientManager {
        final ElicitationTransport transport = new ElicitationTransport();

        @Override
        McpTransport createTransport(McpServerConfig config) {
            return transport;
        }
    }

    static final class UrlElicitationRetryManager extends McpClientManager {
        final UrlElicitationRetryTransport transport = new UrlElicitationRetryTransport();

        @Override
        McpTransport createTransport(McpServerConfig config) {
            return transport;
        }
    }

    static final class UrlElicitationRetryTransport implements McpTransport {
        int toolCalls;
        boolean connected = true;

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            if (Strings.CS.equals("initialize", method)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "url-elicit").put("version", "1");
                return result;
            }
            if (Strings.CS.equals("tools/call", method)) {
                toolCalls++;
                if (toolCalls == 1) {
                    throw new McpException("{\"code\":-32042,\"data\":{\"elicitations\":["
                        + "{\"mode\":\"url\",\"url\":\"https://example.test/auth\","
                        + "\"elicitationId\":\"elicit-1\",\"message\":\"Open the URL\"}]}}");
                }
                return MAPPER.createObjectNode().put("status", "retried");
            }
            return MAPPER.createObjectNode();
        }

        @Override public void sendNotification(String method, JsonNode params) { }
        @Override public void onServerRequest(String method, ServerRequestHandler handler) { }
        @Override public boolean isConnected() { return connected; }
        @Override public void close() { connected = false; }
    }

    static final class ElicitationTransport implements McpTransport {
        final Map<String, ServerRequestHandler> requestHandlers = new ConcurrentHashMap<>();
        boolean connected = true;

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            if (Strings.CS.equals("initialize", method)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo")
                    .put("name", "wire-elicit").put("version", "1.0.0");
                return result;
            }
            if (Strings.CS.equals("tools/call", method)) {
                JsonNode elicitation = requestHandlers.get("elicitation/create")
                    .handle(MAPPER.createObjectNode().put("message", "Choose"));
                return MAPPER.createObjectNode().put(
                    "elicitationAction", elicitation.path("action").asText());
            }
            return MAPPER.createObjectNode();
        }

        @Override public void sendNotification(String method, JsonNode params) { }
        @Override public void onServerRequest(String method, ServerRequestHandler handler) {
            requestHandlers.put(method, handler);
        }
        @Override public boolean isConnected() { return connected; }
        @Override public void close() { connected = false; }
    }

    static final class SdkHandshakeTransport implements McpTransport {
        final List<String> methods = new ArrayList<>();
        final Map<String, ServerRequestHandler> requestHandlers = new ConcurrentHashMap<>();
        ObjectNode initializeParams;
        boolean connected = true;

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            methods.add(method);
            if (Strings.CS.equals("initialize", method)) {
                initializeParams = (ObjectNode) params.deepCopy();
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo")
                    .put("name", "wire-sdk").put("version", "1.0.0");
                return result;
            }
            return MAPPER.createObjectNode();
        }

        @Override
        public void sendNotification(String method, JsonNode params) {
            methods.add(method);
        }

        @Override
        public void onServerRequest(String method, ServerRequestHandler handler) {
            requestHandlers.put(method, handler);
        }

        @Override public boolean isConnected() { return connected; }
        @Override public void close() { connected = false; }
    }

    static final class ReconnectTransport implements McpTransport {
        final int generation;
        final List<String> methods = new ArrayList<>();
        boolean connected = true;

        ReconnectTransport(int generation) {
            this.generation = generation;
        }

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            methods.add(method);
            if (Strings.CS.equals("initialize", method)) {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", "2025-11-25");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "drop-server").put("version", "1");
                result.put("instructions", "WIRE197 reconnect instructions.");
                return result;
            }
            if (Strings.CS.equals("tools/call", method)) {
                return MAPPER.createObjectNode().put("generation", generation);
            }
            return MAPPER.createObjectNode();
        }

        @Override
        public void sendNotification(String method, JsonNode params) {
            methods.add(method);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
        }
    }
}
