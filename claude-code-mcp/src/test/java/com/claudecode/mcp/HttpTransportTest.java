package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;
import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpTransportTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void usesInjectedOkHttpAndCarriesSessionAndConfiguredHeaders() throws Exception {
        List<String> sessionHeaders = new ArrayList<>();
        List<String> authHeaders = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            String response = "{\"jsonrpc\":\"2.0\",\"id\":"
                + request.get("id").asInt() + ",\"result\":{\"ok\":true}}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
            respond(exchange, 200, response);
        });
        server.start();

        HttpTransport transport = new HttpTransport(config(Map.of(
            "Authorization", "Bearer configured")), new McpOAuthProvider(), new OkHttpClient());
        transport.connect();

        assertTrue(transport.sendRequest("tools/list", null).get("ok").asBoolean());
        assertTrue(transport.sendRequest("tools/list", null).get("ok").asBoolean());
        assertEquals(2, sessionHeaders.size());
        assertNull(sessionHeaders.getFirst());
        assertEquals("session-1", sessionHeaders.get(1));
        assertEquals(List.of("Bearer configured", "Bearer configured"), authHeaders);
    }

    @Test
    void toolsCallStartsAtZeroAndAddsMatchingProgressToken() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            requests.add(request);
            String response = "{\"jsonrpc\":\"2.0\",\"id\":"
                + request.get("id").asInt() + ",\"result\":{\"ok\":true}}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, response);
        });
        server.start();

        HttpTransport transport = new HttpTransport(
            config(Map.of()), new McpOAuthProvider(), new OkHttpClient());
        transport.connect();
        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("name", "echo_marker");
        params.putObject("_meta").put("claudecode/toolUseId", "toolu_http");

        assertTrue(transport.sendRequest("tools/call", params).get("ok").asBoolean());

        assertEquals(0, requests.getFirst().get("id").asInt());
        assertEquals(0, requests.getFirst().at("/params/_meta/progressToken").asInt());
        assertEquals("toolu_http",
            requests.getFirst().at("/params/_meta/claudecode~1toolUseId").asText());
        assertFalse(params.at("/_meta").has("progressToken"));
    }

    @Test
    void parsesMatchingJsonRpcFrameFromEventStreamResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            int id = request.get("id").asInt();
            String body = "data: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"result\":{\"matched\":true}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            respond(exchange, 200, body);
        });
        server.start();

        HttpTransport transport = new HttpTransport(
            config(Map.of("Authorization", "Bearer configured")),
            new McpOAuthProvider(), new OkHttpClient());
        transport.connect();

        JsonNode result = transport.sendRequest("tools/list", null);
        assertTrue(result.get("matched").asBoolean());
        assertFalse(result.has("id"));
    }

    @Test
    void returnsMatchingSseFrameWithoutWaitingForTheServerToCloseTheStream() throws Exception {
        CountDownLatch releaseStream = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/mcp", exchange -> {
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            int id = request.get("id").asInt();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                    + "data: {\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"result\":{\"matched\":true}}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.flush();
                await(releaseStream);
            }
        });
        server.start();

        HttpTransport transport = new HttpTransport(
            config(Map.of("Authorization", "Bearer configured")),
            new McpOAuthProvider(), new OkHttpClient());
        transport.connect();

        try {
            JsonNode result = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> transport.sendRequest("tools/call", null));
            assertTrue(result.get("matched").asBoolean());
        } finally {
            releaseStream.countDown();
        }
    }

    @Test
    void rejectsSuccessfulResponseWithEmptyBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        HttpTransport transport = new HttpTransport(
            config(Map.of()), new McpOAuthProvider(), new OkHttpClient());
        transport.connect();

        McpException error = assertThrows(McpException.class,
            () -> transport.sendRequest("tools/list", null));
        assertTrue(Strings.CS.contains(error.getMessage(), "empty body"));
    }

    private McpServerConfig config(Map<String, String> headers) {
        return new McpServerConfig(
            "test", "", List.of(), Map.of(), false, "http",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", headers);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test peer");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for test peer", e);
        }
    }
}
