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
 *
 * <p>All four handlers share one failure vocabulary, because the client
 * renders the three outcomes differently: 400 for a request the user can fix,
 * 404 for a row that is already gone (the webui drops it and moves on), and
 * 500 carrying the underlying message for an operation that failed on a
 * session that exists. Collapsing the last two loses the only signal that
 * tells a user their transcript is intact but unwritable — see
 * {@link GatewaySessionActionsPort.UnknownSessionException}.
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
        } catch (GatewaySessionActionsPort.UnknownSessionException _) {
            respondUnknown(exchange, sessionId);
            return;
        } catch (RuntimeException failure) {
            respondFailed(exchange, "rename", sessionId, failure);
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
        } catch (GatewaySessionActionsPort.UnknownSessionException _) {
            respondUnknown(exchange, sessionId);
            return;
        } catch (RuntimeException failure) {
            respondFailed(exchange, "fork", sessionId, failure);
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", forked.sessionId());
        respondJson(exchange, 200, result);
    }

    void handleArchive(HttpExchange exchange, String sessionId) throws IOException {
        try {
            actions.archive(sessionId);
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid archive request")));
            return;
        } catch (GatewaySessionActionsPort.UnknownSessionException _) {
            respondUnknown(exchange, sessionId);
            return;
        } catch (RuntimeException failure) {
            respondFailed(exchange, "archive", sessionId, failure);
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
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid delete request")));
            return;
        } catch (GatewaySessionActionsPort.UnknownSessionException _) {
            respondUnknown(exchange, sessionId);
            return;
        } catch (RuntimeException failure) {
            respondFailed(exchange, "delete", sessionId, failure);
            return;
        }
        if (!deleted) {
            respondUnknown(exchange, sessionId);
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("deleted", true);
        respondJson(exchange, 200, result);
    }

    private static void respondUnknown(HttpExchange exchange, String sessionId) throws IOException {
        respondJson(exchange, 404, errorBody("not_found", "unknown session: " + sessionId));
    }

    /**
     * The session resolved and the operation failed on it — a full disk or a
     * read-only transcript, not a missing row. The underlying message is
     * carried through: answering "unknown session" here would tell the user
     * their conversation is gone when it is intact and merely unwritable.
     */
    private static void respondFailed(HttpExchange exchange, String action, String sessionId,
            RuntimeException failure) throws IOException {
        respondJson(exchange, 500, errorBody("api_error", "failed to " + action + " session "
            + sessionId + ": " + StringUtils.defaultIfBlank(failure.getMessage(),
                failure.toString())));
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
