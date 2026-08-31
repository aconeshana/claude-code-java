package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
