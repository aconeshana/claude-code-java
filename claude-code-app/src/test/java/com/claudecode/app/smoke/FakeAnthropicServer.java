package com.claudecode.app.smoke;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * A loopback stand-in for the Anthropic Messages API, serving one fixed streaming turn.
 */
final class FakeAnthropicServer implements AutoCloseable {

    /** The only text the served turn emits, and therefore the stdout proof a turn completed. */
    static final String MARKER = "pong";

    private static final String MESSAGES_PATH = "/v1/messages";

    /**
     * One complete streaming turn. Assembled once because it never varies: a case that needs a
     * different response is testing the client, which is not what a startup smoke covers.
     */
    private static final String TURN = String.join("\n\n",
        event("message_start", """
            {"type":"message_start","message":{"id":"msg_smoke","type":"message",\
            "role":"assistant","model":"claude-opus-5","content":[],"stop_reason":null,\
            "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}"""),
        event("content_block_start", """
            {"type":"content_block_start","index":0,\
            "content_block":{"type":"text","text":""}}"""),
        event("content_block_delta", """
            {"type":"content_block_delta","index":0,\
            "delta":{"type":"text_delta","text":"%s"}}""".formatted(MARKER)),
        event("content_block_stop", """
            {"type":"content_block_stop","index":0}"""),
        event("message_delta", """
            {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},\
            "usage":{"output_tokens":1}}"""),
        event("message_stop", """
            {"type":"message_stop"}""")) + "\n\n";

    private final HttpServer server;
    private final ConcurrentLinkedQueue<String> requestedPaths = new ConcurrentLinkedQueue<>();

    private FakeAnthropicServer(HttpServer server) {
        this.server = server;
    }

    static FakeAnthropicServer start() throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeAnthropicServer fake = new FakeAnthropicServer(server);
        server.createContext("/", fake::handle);
        // Cases run one process at a time, but a single launch may open several connections.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return fake;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Paths reached that the harness did not plan for, in first-seen order. */
    List<String> unexpectedPaths() {
        return requestedPaths.stream().distinct().filter(path -> !MESSAGES_PATH.equals(path)).toList();
    }

    int turnsServed() {
        return (int) requestedPaths.stream().filter(MESSAGES_PATH::equals).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);
            drain(exchange);
            boolean messages = MESSAGES_PATH.equals(path);
            exchange.getResponseHeaders().set("Content-Type",
                messages ? "text/event-stream" : "application/json");
            byte[] body = (messages ? TURN : "{}").getBytes(StandardCharsets.UTF_8);
            // Length 0 asks for a chunked response, which is what makes this a stream rather than
            // one buffered blob the client might legitimately reject.
            exchange.sendResponseHeaders(200, messages ? 0 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
                out.flush();
            }
        }
    }

    /**
     * An unread request body leaves the connection unusable for keep-alive, which surfaces as a
     * hang in the process under test rather than as an error here.
     */
    private static void drain(HttpExchange exchange) throws IOException {
        try (var body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
