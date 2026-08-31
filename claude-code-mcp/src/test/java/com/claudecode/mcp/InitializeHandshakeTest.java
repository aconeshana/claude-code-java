package com.claudecode.mcp;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@code initialize} handshake performed by
 * {@link McpClientManager#connect}. Uses a scripted transport that records
 * every outbound message so we can verify the exact wire order + shape and
 * the response caching on {@link McpConnection}.
 */
class InitializeHandshakeTest {

    private static ObjectNode obj() { return JsonUtils.getMapper().createObjectNode(); }

    // ── outbound handshake shape ─────────────────────────────────────────────

    @Test
    void connect_sendsInitializeRequest_withDeclaredCapabilities_andClientInfo() {
        ScriptedTransport t = new ScriptedTransport();
        // Server acks initialize with its own capability set.
        t.scriptResponse("initialize", _ -> {
            ObjectNode res = obj();
            res.put("protocolVersion", "2024-11-05");
            var caps = res.putObject("capabilities");
            caps.putObject("tools");
            caps.putObject("prompts");
            var info = res.putObject("serverInfo");
            info.put("name", "mock-server");
            info.put("version", "1.0.0");
            return res;
        });

        var mgr = new HarnessClientManager(t);
        mgr.connect(new McpServerConfig("srv", "", List.of(), Map.of(),
            false, "http", "http://x/", Map.of()));

        List<ScriptedTransport.Sent> sent = t.sent;
        assertFalse(sent.isEmpty(), "handshake must send at least the initialize request");
        ScriptedTransport.Sent init = sent.getFirst();
        assertEquals("initialize", init.method,
            "initialize must be the very first outbound message");
        assertEquals("2025-11-25", init.params.get("protocolVersion").asText(),
            "protocol version must match the released 2.1.197 client");

        // capability consumed by the client, not a client capability.
        JsonNode caps = init.params.get("capabilities");
        assertNotNull(caps);
        assertTrue(caps.has("roots"), "must advertise roots capability (M3-C wired the handler)");
        assertTrue(caps.has("elicitation"), "2.1.197 advertises elicitation support");
        assertFalse(caps.has("prompts"), "prompts is not a client capability");
        assertFalse(caps.has("sampling"), "sampling not implemented (Java or TS)");
        JsonNode clientInfo = init.params.get("clientInfo");
        assertEquals("claude-code", clientInfo.get("name").asText());
        assertEquals("Claude Code", clientInfo.get("title").asText());
        assertEquals("2.1.197", clientInfo.get("version").asText());
        assertEquals("Anthropic's agentic coding tool",
            clientInfo.get("description").asText());
        assertEquals("https://claude.com/claude-code",
            clientInfo.get("websiteUrl").asText());
    }

    @Test
    void connect_sendsInitializedNotification_afterResponse() {
        ScriptedTransport t = new ScriptedTransport();
        t.scriptResponse("initialize", _ -> emptyInitResult());

        HarnessClientManager mgr = new HarnessClientManager(t);
        mgr.connect(new McpServerConfig("srv", "", List.of(), Map.of(),
            false, "http", "http://x/", Map.of()));

        // Sent sequence: [request initialize, notification notifications/initialized].
        assertEquals(2, t.sent.size());
        assertEquals("initialize", t.sent.getFirst().method);
        assertTrue(t.sent.getFirst().isRequest, "initialize must be sent as a request");
        assertEquals("notifications/initialized", t.sent.get(1).method);
        assertFalse(t.sent.get(1).isRequest,
            "notifications/initialized must be a notification (no id), per JSON-RPC + MCP spec");
    }

    // ── response caching on McpConnection ────────────────────────────────────

    @Test
    void connect_cachesServerCapabilities_andProtocolVersion() {
        ScriptedTransport t = new ScriptedTransport();
        t.scriptResponse("initialize", _ -> {
            ObjectNode res = obj();
            res.put("protocolVersion", "2025-06-18");   // server picked a newer version
            var caps = res.putObject("capabilities");
            caps.putObject("tools").put("listChanged", true);
            caps.putObject("resources").put("subscribe", false);
            var info = res.putObject("serverInfo");
            info.put("name", "mock");
            info.put("version", "v42");
            return res;
        });

        HarnessClientManager mgr = new HarnessClientManager(t);
        mgr.connect(new McpServerConfig("srv", "", List.of(),
            Map.of(), false, "http", "http://x/", Map.of()));
        McpConnection conn = mgr.getConnection("srv").orElseThrow();

        assertEquals("2025-06-18", conn.getProtocolVersion(),
            "server-agreed protocol version must be surfaced");
        assertTrue(conn.hasCapability("tools"));
        assertTrue(conn.hasCapability("resources"));
        assertFalse(conn.hasCapability("elicitation"),
            "hasCapability must reflect server's actual declaration, not what client asked for");
        assertNotNull(conn.getServerInfo());
        assertEquals("mock", conn.getServerInfo().get("name").asText());
    }


    @Test
    void connect_rejectsInitializeFailure_andRemovesConnection() {
        ScriptedTransport t = new ScriptedTransport();
        t.scriptResponse("initialize", _ -> {
            throw new McpException("simulated: server does not implement initialize");
        });

        HarnessClientManager mgr = new HarnessClientManager(t);
        McpServerConfig config = new McpServerConfig("srv", "", List.of(),
            Map.of(), false, "http", "http://x/", Map.of());

        assertThrows(McpException.class, () -> mgr.connect(config));
        assertTrue(mgr.getConnection("srv").isEmpty(),
            "failed initialize must not leave a degraded pseudo-connection");
        assertEquals(1, t.sent.size());
        assertEquals("initialize", t.sent.getFirst().method);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JsonNode emptyInitResult() {
        ObjectNode res = obj();
        res.put("protocolVersion", "2025-11-25");
        res.putObject("capabilities");
        var info = res.putObject("serverInfo");
        info.put("name", "mock");
        info.put("version", "1.0.0");
        return res;
    }

    /**
     * Wraps {@link McpClientManager} but replaces transport creation with a
     * pre-built scripted transport, exercising the real manager-owned connect
     * lifecycle without needing a server process.
     */
    private static final class HarnessClientManager extends McpClientManager {
        private final ScriptedTransport transport;
        HarnessClientManager(ScriptedTransport t) { this.transport = t; }
        @Override
        McpTransport createTransport(McpServerConfig config) { return transport; }
    }

    /**
     * Records every outbound request / notification and replies via a
     * per-method callback. Notifications are recorded but never asked to
     * reply (fire-and-forget in the wire protocol).
     */
    static final class ScriptedTransport implements McpTransport {
        final List<Sent> sent = new ArrayList<>();
        final Map<String, Function<JsonNode, JsonNode>> responders = new ConcurrentHashMap<>();

        void scriptResponse(String method, Function<JsonNode, JsonNode> reply) {
            responders.put(method, reply);
        }

        @Override
        public JsonNode sendRequest(String method, JsonNode params) {
            sent.add(new Sent(method, params, true));
            var responder = responders.get(method);
            if (responder == null) return obj();
            return responder.apply(params);
        }

        @Override
        public void sendNotification(String method, JsonNode params) {
            sent.add(new Sent(method, params, false));
        }

        @Override public boolean isConnected() { return true; }
        @Override public void close() { }

        record Sent(String method, JsonNode params, boolean isRequest) {}
    }
}
