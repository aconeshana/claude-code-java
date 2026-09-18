package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /api/sessions/{id}/rename}, {@code /fork}, {@code /archive},
 * and {@code DELETE /api/sessions/{id}}: the session row menu's mutating
 * actions, delegated to {@link GatewaySessionActionsPort}.
 */
final class GatewaySessionActionsHandler {

    private final GatewaySessionActionsPort actions;

    GatewaySessionActionsHandler(GatewaySessionActionsPort actions) {
        this.actions = actions;
    }

    void handleRename(HttpExchange exchange, String sessionId) throws IOException {
        JsonNode request = readBody(exchange);
        if (request == null) return;
        String title = text(request, "title");
        if (StringUtils.isBlank(title)) {
            respondJson(exchange, 400, errorBody("invalid_request", "title is required"));
            return;
        }
        try {
            actions.rename(sessionId, title);
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid rename request")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("custom_title", title);
        respondJson(exchange, 200, result);
    }

    void handleFork(HttpExchange exchange, String sessionId) throws IOException {
        JsonNode request = readBody(exchange);
        if (request == null) return;
        String title = text(request, "title");
        GatewaySessionActionsPort.ForkResult forked;
        try {
            forked = actions.fork(sessionId, title);
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid fork request")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", forked.sessionId());
        respondJson(exchange, 200, result);
    }

    void handleArchive(HttpExchange exchange, String sessionId) throws IOException {
        try {
            actions.archive(sessionId);
        } catch (RuntimeException failure) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("archived", true);
        respondJson(exchange, 200, result);
    }

    void handleDelete(HttpExchange exchange, String sessionId) throws IOException {
        boolean deleted;
        try {
            deleted = actions.delete(sessionId);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to delete session: " + failure.getMessage()));
            return;
        }
        if (!deleted) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("deleted", true);
        respondJson(exchange, 200, result);
    }

    private static JsonNode readBody(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (StringUtils.isBlank(body)) return JsonUtils.getMapper().createObjectNode();
        try {
            return JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "body is not valid JSON"));
            return null;
        }
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
    }

    private static void respondJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
