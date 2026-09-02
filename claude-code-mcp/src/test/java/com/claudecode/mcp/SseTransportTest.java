package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseTransportTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void receivesLiveSseResponseForPostedRequestUsingInjectedOkHttp() throws Exception {
        AtomicReference<OutputStream> eventStream = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> postedRequest = new AtomicReference<>();
        CountDownLatch streamReady = new CountDownLatch(1);
        CountDownLatch responseSent = new CountDownLatch(1);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/sse", exchange -> {
            if (Strings.CS.equals("GET", exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream output = exchange.getResponseBody();
                eventStream.set(output);
                output.write(("event: endpoint\ndata: http://127.0.0.1:"
                    + server.getAddress().getPort() + "/sse\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.flush();
                streamReady.countDown();
                await(responseSent);
                output.close();
                return;
            }

            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            postedRequest.set(request);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();

            await(streamReady);
            String event = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                + request.get("id").asInt() + ",\"result\":{\"ok\":true}}\n\n";
            synchronized (eventStream.get()) {
                eventStream.get().write(event.getBytes(StandardCharsets.UTF_8));
                eventStream.get().flush();
            }
            responseSent.countDown();
        });
        server.start();

        McpServerConfig config = new McpServerConfig(
            "legacy-sse", "", List.of(), Map.of(), false, "sse",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/sse",
            Map.of("Authorization", "Bearer configured"));
        SseTransport transport = new SseTransport(
            config, new McpOAuthProvider(), new OkHttpClient(), new OkHttpClient());
        transport.connect();

        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("name", "echo_marker");
        params.putObject("_meta").put("claudecode/toolUseId", "toolu_sse");
        JsonNode result = transport.sendRequest("tools/call", params);

        assertTrue(result.get("ok").asBoolean());
        assertEquals("Bearer configured", authorization.get());
        assertEquals(0, postedRequest.get().get("id").asInt());
        assertEquals(0, postedRequest.get().at("/params/_meta/progressToken").asInt());
        assertEquals("toolu_sse",
            postedRequest.get().at("/params/_meta/claudecode~1toolUseId").asText());
        assertTrue(params.at("/_meta/progressToken").isMissingNode());
        transport.close();
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

    // ── endpoint negotiation (released-197 SSEClientTransport.start() parity) ──

    private McpServerConfig config(String path) {
        return new McpServerConfig("legacy-sse", "", List.of(), Map.of(), false, "sse",
            "http://127.0.0.1:" + server.getAddress().getPort() + path, Map.of());
    }

    private SseTransport transport(McpServerConfig cfg, Duration endpointTimeout) {
        return new SseTransport(cfg, new McpOAuthProvider(), new OkHttpClient(),
            new OkHttpClient(), endpointTimeout);
    }

    private HttpServer newServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        return server;
    }

    /** GET streams an {@code endpoint} event (optionally delayed); POST gets a 404. */
    private HttpHandler sseStream(String endpointData, long delayMillis,
                                  AtomicReference<OutputStream> eventStream,
                                  CountDownLatch release) {
        return exchange -> {
            if (!Strings.CS.equals("GET", exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            eventStream.set(output);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            if (endpointData != null) {
                output.write(("event: endpoint\ndata: " + endpointData + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            await(release);
            output.close();
        };
    }

    /** Answers a posted JSON-RPC request with a result delivered over the SSE stream. */
    private HttpHandler messagesEndpoint(AtomicReference<OutputStream> eventStream,
                                         AtomicReference<String> postedPath,
                                         CountDownLatch responseSent) {
        return exchange -> {
            postedPath.set(exchange.getRequestURI().getPath());
            JsonNode request = JsonUtils.getMapper().readTree(exchange.getRequestBody());
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            String event = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":"
                + request.get("id").asInt() + ",\"result\":{\"ok\":true}}\n\n";
            synchronized (eventStream.get()) {
                eventStream.get().write(event.getBytes(StandardCharsets.UTF_8));
                eventStream.get().flush();
            }
            responseSent.countDown();
        };
    }

    @Test
    void connectBlocksUntilEndpointEventThenPostsToNegotiatedEndpoint() throws Exception {
        AtomicReference<OutputStream> eventStream = new AtomicReference<>();
        AtomicReference<String> postedPath = new AtomicReference<>();
        CountDownLatch responseSent = new CountDownLatch(1);

        newServer();
        server.createContext("/sse", sseStream(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/messages",
            300, eventStream, responseSent));
        server.createContext("/messages",
            messagesEndpoint(eventStream, postedPath, responseSent));
        server.start();

        SseTransport transport = transport(config("/sse"), Duration.ofSeconds(10));
        long start = System.nanoTime();
        transport.connect();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        JsonNode result = transport.sendRequest("tools/list", null);
        assertTrue(result.get("ok").asBoolean());
        assertEquals("/messages", postedPath.get(),
            "first POST must target the negotiated endpoint, never the SSE stream URL");
        assertTrue(elapsedMs >= 250,
            "connect() must block until the endpoint event arrives, took " + elapsedMs + "ms");
        transport.close();
    }

    @Test
    void relativeEndpointResolvesAgainstSseUrl() throws Exception {
        AtomicReference<OutputStream> eventStream = new AtomicReference<>();
        AtomicReference<String> postedPath = new AtomicReference<>();
        CountDownLatch responseSent = new CountDownLatch(1);

        newServer();
        server.createContext("/sse", sseStream("/messages", 0, eventStream, responseSent));
        server.createContext("/messages",
            messagesEndpoint(eventStream, postedPath, responseSent));
        server.start();

        SseTransport transport = transport(config("/sse"), Duration.ofSeconds(10));
        transport.connect();
        JsonNode result = transport.sendRequest("tools/list", null);
        assertTrue(result.get("ok").asBoolean());
        assertEquals("/messages", postedPath.get());
        transport.close();
    }

    @Test
    void connectFailsFastWhenSseStreamReturns404() throws Exception {
        newServer();
        server.createContext("/sse", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        SseTransport transport = transport(config("/sse"), Duration.ofSeconds(30));
        long start = System.nanoTime();
        McpException failure = assertThrows(McpException.class, transport::connect);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(failure.getMessage().contains("404"), failure.getMessage());
        assertTrue(elapsedMs < 10_000,
            "stream failure must surface immediately, not after the endpoint timeout: "
                + elapsedMs + "ms");
    }

    @Test
    void connectTimesOutWhenEndpointEventNeverArrives() throws Exception {
        AtomicReference<OutputStream> eventStream = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);

        newServer();
        server.createContext("/sse", sseStream(null, 0, eventStream, release));
        server.start();

        SseTransport transport = transport(config("/sse"), Duration.ofMillis(400));
        long start = System.nanoTime();
        McpException failure = assertThrows(McpException.class, transport::connect);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(failure.getMessage().contains("Timed out"), failure.getMessage());
        assertTrue(elapsedMs >= 350 && elapsedMs < 5_000, "took " + elapsedMs + "ms");
        release.countDown();
    }

    @Test
    void crossOriginEndpointIsRejected() throws Exception {
        AtomicReference<OutputStream> eventStream = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);

        newServer();
        server.createContext("/sse", sseStream(
            "http://127.0.0.1:1/evil", 0, eventStream, release));
        server.start();

        SseTransport transport = transport(config("/sse"), Duration.ofSeconds(10));
        McpException failure = assertThrows(McpException.class, transport::connect);
        assertTrue(failure.getMessage().contains("origin"), failure.getMessage());
        release.countDown();
    }
}
