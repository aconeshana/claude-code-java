package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StdioTransport} using a simple echo-like process.
 * Uses a bash script that reads a line from stdin and echoes back a JSON-RPC response.
 */
class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sendRequestAndReceiveResponse() throws Exception {
        // Use bash to create a simple JSON-RPC echo server:
        // reads one line, extracts the id, returns a response with that id
        McpServerConfig config = new McpServerConfig(
            "echo-server",
            "bash",
            List.of("-c", "while IFS= read -r line; do " +
                "id=$(echo \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/'); " +
                "echo \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":$id,\\\"result\\\":{\\\"echo\\\":true}}\"; " +
                "done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            assertTrue(transport.isConnected());

            ObjectNode params = MAPPER.createObjectNode();
            params.put("test", "value");

            JsonNode result = transport.sendRequest("test/method", params);
            assertNotNull(result);
            assertTrue(result.has("echo"));
            assertTrue(result.get("echo").asBoolean());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void multipleRequests() throws Exception {
        McpServerConfig config = new McpServerConfig(
            "multi-echo",
            "bash",
            List.of("-c", "while IFS= read -r line; do " +
                "id=$(echo \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/'); " +
                "echo \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":$id,\\\"result\\\":{\\\"count\\\":$id}}\"; " +
                "done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();

            JsonNode r1 = transport.sendRequest("method1", null);
            JsonNode r2 = transport.sendRequest("method2", null);

            assertNotNull(r1);
            assertNotNull(r2);

            assertEquals(0, r1.get("count").asInt());
            assertEquals(1, r2.get("count").asInt());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void toolsCallAddsProgressTokenMatchingRequestIdWithoutMutatingCallerParams() throws Exception {
        String script = """
            import json,sys
            for line in sys.stdin:
             request=json.loads(line)
             print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':\
            {'requestId':request['id'],'params':request.get('params')}}), flush=True)
            """;
        McpServerConfig config = new McpServerConfig(
            "progress-token",
            "python3",
            List.of("-u", "-c", script),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", "echo_marker");
            params.putObject("_meta").put("claudecode/toolUseId", "toolu_197");

            JsonNode result = transport.sendRequest("tools/call", params);

            assertEquals(0, result.get("requestId").asInt());
            assertEquals(0, result.at("/params/_meta/progressToken").asInt());
            assertEquals("toolu_197",
                result.at("/params/_meta/claudecode~1toolUseId").asText());
            assertFalse(params.at("/_meta").has("progressToken"),
                "transport metadata must not mutate the caller-owned params node");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void closeStopsProcess() throws Exception {
        McpServerConfig config = new McpServerConfig(
            "close-test",
            "bash",
            List.of("-c", "while IFS= read -r line; do echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}'; done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        transport.start();
        assertTrue(transport.isConnected());

        transport.close();
        assertFalse(transport.isConnected());
    }

    @Test
    void sendRequestBeforeStartThrows() {
        McpServerConfig config = new McpServerConfig(
            "not-started", "echo", List.of(), Map.of(), false, "stdio");

        StdioTransport transport = new StdioTransport(config);
        // Not started, so not connected
        assertThrows(McpException.class, () -> transport.sendRequest("test", null));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void errorResponseThrows() throws Exception {
        // Server that always returns an error
        McpServerConfig config = new McpServerConfig(
            "error-server",
            "bash",
            List.of("-c", "while IFS= read -r line; do " +
                "id=$(echo \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/'); " +
                "echo \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":$id,\\\"error\\\":{\\\"code\\\":-1,\\\"message\\\":\\\"test error\\\"}}\"; " +
                "done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            McpException ex = assertThrows(McpException.class,
                () -> transport.sendRequest("fail", null));
            assertTrue(Strings.CS.contains(ex.getMessage(), "test error"));
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void environmentVariablesArePassed() throws Exception {
        // Server that echoes an env var in the response
        McpServerConfig config = new McpServerConfig(
            "env-server",
            "bash",
            List.of("-c", "while IFS= read -r line; do " +
                "id=$(echo \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/'); " +
                "echo \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":$id,\\\"result\\\":{\\\"val\\\":\\\"$MCP_TEST_VAR\\\"}}\"; " +
                "done"),
            Map.of("MCP_TEST_VAR", "hello123"),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            JsonNode result = transport.sendRequest("env/check", null);
            assertEquals("hello123", result.get("val").asText());
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void notificationBeforeResponseDoesNotCorruptPairing() throws Exception {
        // Server that emits a NOTIFICATION line first, then the real response —
        // the old ask-then-read design parsed the notification as the response.
        McpServerConfig config = new McpServerConfig(
            "notif-first",
            "bash",
            List.of("-c", "while IFS= read -r line; do " +
                "id=$(echo \"$line\" | sed 's/.*\"id\":\\([0-9]*\\).*/\\1/'); " +
                "echo '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}'; " +
                "echo \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":$id,\\\"result\\\":{\\\"ok\\\":true}}\"; " +
                "done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            CountDownLatch notified = new CountDownLatch(1);
            transport.onNotification("notifications/tools/list_changed", _ -> notified.countDown());

            JsonNode r1 = transport.sendRequest("m1", null);
            assertTrue(r1.get("ok").asBoolean(), "response must be the id-matched line, not the notification");
            JsonNode r2 = transport.sendRequest("m2", null);
            assertTrue(r2.get("ok").asBoolean(), "second request must not read a stale buffered line");
            assertTrue(notified.await(2, TimeUnit.SECONDS),
                "interleaved notification must reach its handler");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void notificationBetweenRequestsIsDelivered() throws Exception {
        // Server that pushes a notification on startup, before any request —
        // previously nothing ever read it.
        McpServerConfig config = new McpServerConfig(
            "notif-push",
            "bash",
            List.of("-c",
                "echo '{\"jsonrpc\":\"2.0\",\"method\":\"notifications/prompts/list_changed\",\"params\":{\"n\":1}}'; " +
                "while IFS= read -r line; do :; done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        try {
            transport.start();
            CountDownLatch notified = new CountDownLatch(1);
            transport.onNotification("notifications/prompts/list_changed", _ -> notified.countDown());
            assertTrue(notified.await(2, TimeUnit.SECONDS),
                "unsolicited notification must be delivered without an in-flight request");
        } finally {
            transport.close();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void requestTimesOutOnSilentServer() throws Exception {
        // Server that reads requests but never answers.
        McpServerConfig config = new McpServerConfig(
            "silent",
            "bash",
            List.of("-c", "while IFS= read -r line; do :; done"),
            Map.of(),
            false,
            "stdio"
        );

        StdioTransport transport = new StdioTransport(config);
        transport.requestTimeoutOverrideMs = 300;
        try {
            transport.start();
            McpException ex = assertThrows(McpException.class,
                () -> transport.sendRequest("never/answered", null));
            assertTrue(Strings.CS.contains(ex.getMessage(), "timed out"), ex.getMessage());
        } finally {
            transport.close();
        }
    }
}
