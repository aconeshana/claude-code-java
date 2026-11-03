package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;

/**
 * The gateway's shared HTTP response construction: every {@code /api} handler
 * answers with the same JSON body shape, the same {@code Content-Type}, and the
 * same launch-token gate, so those three live here instead of being re-inlined
 * in each of the two dozen handler and route sites.
 *
 * <p>Bodies are built as Jackson {@link ObjectNode}s rather than pre-rendered
 * strings so a caller can extend a response (the sessions listing adds an
 * asynchronous suffix) without re-parsing JSON.
 */
final class GatewayResponses {

    /** The one message every unauthenticated route returns, verbatim. */
    private static final String AUTH_MESSAGE =
        "Provide the launch token as Authorization: Bearer or ?token=";

    private static final int UNAUTHORIZED = 401;

    private GatewayResponses() {}

    /** The gateway's error envelope: {@code {"error":{"type":…,"message":…}}}. */
    static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
    }

    /** Sends {@code body} as one complete {@code application/json} response. */
    static void respondJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /**
     * Answers the launch-token challenge for one route, reporting whether the
     * caller should stop.
     *
     * <p>An authenticated request is left untouched — its exchange belongs to
     * the downstream handler, which may keep it open as a long-lived SSE
     * stream. Only the rejection path consumes and closes the exchange here.
     *
     * @return {@code true} when the caller must return without handling the route
     */
    static boolean rejectIfUnauthenticated(GatewayAuthFilter auth, HttpExchange exchange)
            throws IOException {
        if (auth.authenticated(exchange)) return false;
        try (exchange) {
            respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required", AUTH_MESSAGE));
        }
        return true;
    }
}