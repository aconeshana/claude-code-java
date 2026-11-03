package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The shared response helpers every gateway route answers through. These pin
 * the wire facts the handlers used to inline: the error envelope's field order,
 * the status and {@code Content-Type} of a JSON reply, and — the one that is
 * easy to get wrong — which exchange the launch-token rejection closes.
 */
class GatewayResponsesTest {

    private static final String TOKEN = "x".repeat(48);

    @Test
    void errorBodyKeepsTypeBeforeMessageAndEscapesTheMessage() {
        ObjectNode body = GatewayResponses.errorBody("invalid_request", "a \"quoted\" value");
        assertThat(body.toString()).isEqualTo(
            "{\"error\":{\"type\":\"invalid_request\",\"message\":\"a \\\"quoted\\\" value\"}}");
    }

    @Test
    void respondJsonWritesStatusContentTypeAndUtf8Bytes() throws Exception {
        FakeExchange exchange = new FakeExchange();

        GatewayResponses.respondJson(exchange, 409, GatewayResponses.errorBody("already_responded", "done"));

        assertThat(exchange.status).hasValue(409);
        assertThat(exchange.headers.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(exchange.written.toString(StandardCharsets.UTF_8)).isEqualTo(
            "{\"error\":{\"type\":\"already_responded\",\"message\":\"done\"}}");
        assertThat(exchange.closed.get()).as("body stream closed by try-with-resources").isTrue();
    }

    @Test
    void anAuthenticatedRequestIsLeftToTheHandler() throws Exception {
        FakeExchange exchange = new FakeExchange().withToken(TOKEN);

        boolean rejected = GatewayResponses.rejectIfUnauthenticated(
            new GatewayAuthFilter(TOKEN), exchange);

        assertThat(rejected).isFalse();
        assertThat(exchange.closed.get())
            .as("a long-lived SSE exchange must stay open for its handler")
            .isFalse();
        assertThat(exchange.status).hasValue(0);
    }

    @Test
    void anUnauthenticatedRequestIsAnsweredAndClosedHere() throws Exception {
        FakeExchange exchange = new FakeExchange();

        boolean rejected = GatewayResponses.rejectIfUnauthenticated(
            new GatewayAuthFilter(TOKEN), exchange);

        assertThat(rejected).isTrue();
        assertThat(exchange.status).hasValue(401);
        assertThat(exchange.written.toString(StandardCharsets.UTF_8)).isEqualTo(
            "{\"error\":{\"type\":\"authentication_required\","
            + "\"message\":\"Provide the launch token as Authorization: Bearer or ?token=\"}}");
        assertThat(exchange.closed.get()).as("rejection owns and closes the exchange").isTrue();
    }

    /** The minimum exchange surface these helpers touch. */
    private static final class FakeExchange extends HttpExchange {
        private final Headers headers = new Headers();
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger status = new AtomicInteger();
        private String token;

        FakeExchange withToken(String value) {
            this.token = value;
            return this;
        }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return headers; }
        @Override public URI getRequestURI() {
            return URI.create(token == null ? "/api/sessions" : "/api/sessions?token=" + token);
        }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed.set(true); }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override public void write(int b) { written.write(b); }
                @Override public void write(byte[] b, int off, int len) { written.write(b, off, len); }
                @Override public void close() { closed.set(true); }
            };
        }
        @Override public void sendResponseHeaders(int code, long length) { status.set(code); }
        @Override public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }
        @Override public int getResponseCode() { return status.get(); }
        @Override public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream in, OutputStream out) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}