package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /api/sessions/open} and {@code POST /api/sessions/close}: the
 * headless-session lifecycle endpoints.
 *
 * <p>Open mints (or resumes) one parallel headless session bound to a project
 * directory; close shuts one down. The body carries {@code session_id}
 * (optional on open — empty mints a fresh id; required on close) and
 * {@code project_path} (optional on open — defaults to the process project).
 * The two-level catalog listing itself lives on {@code GET /api/sessions}
 * (ProjectCatalog shape with per-session open/active annotations).
 */
final class GatewaySessionsHandler {

    /** Mirror-side hooks for headless-session lifecycle transitions. */
    interface Lifecycle {
        default void onOpened(SessionHostSession session) {}
        default void onClosed(String sessionId) {}
    }

    private final GatewayHeadlessSessions headless;
    private final Lifecycle lifecycle;
    private final AtomicBoolean opening = new AtomicBoolean();

    GatewaySessionsHandler(GatewayHeadlessSessions headless) {
        this(headless, new Lifecycle() {});
    }

    GatewaySessionsHandler(GatewayHeadlessSessions headless, Lifecycle lifecycle) {
        this.headless = headless;
        this.lifecycle = lifecycle == null ? new Lifecycle() {} : lifecycle;
    }

    /** Handles one open or close exchange. */
    void handle(HttpExchange exchange, boolean open) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "body is not valid JSON"));
            return;
        }
        String sessionId = text(request, "session_id");
        String projectPath = text(request, "project_path");
        if (open) {
            handleOpen(exchange, sessionId, projectPath);
        } else {
            handleClose(exchange, sessionId);
        }
    }

    private void handleOpen(HttpExchange exchange, String sessionId, String projectPath)
            throws IOException {
        if (StringUtils.isNotBlank(sessionId) && sessionId.length() > 256) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "session_id must be at most 256 characters"));
            return;
        }
        if (!opening.compareAndSet(false, true)) {
            respondJson(exchange, 429, errorBody("api_error",
                "another session open is in progress; retry shortly"));
            return;
        }
        GatewayHeadlessSessions.Opened opened;
        try {
            opened = headless.open(new GatewayHeadlessSessions.OpenRequest(sessionId, projectPath));
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid open request")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to open session: " + failure.getMessage()));
            return;
        } finally {
            opening.set(false);
        }
        lifecycle.onOpened(opened.session());
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", opened.session().info().id());
        result.put("project_path", opened.projectPath());
        result.put("resumed", opened.resumed());
        result.put("headless", true);
        respondJson(exchange, 200, result);
    }

    private void handleClose(HttpExchange exchange, String sessionId) throws IOException {
        if (StringUtils.isBlank(sessionId)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "session_id is required"));
            return;
        }
        boolean closed;
        try {
            closed = headless.close(sessionId);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to close session: " + failure.getMessage()));
            return;
        }
        if (!closed) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        lifecycle.onClosed(sessionId);
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("closed", true);
        respondJson(exchange, 200, result);
    }

    /** Closes every open headless session (gateway shutdown path). */
    void closeAll() {
        try {
            headless.closeAll();
        } catch (RuntimeException _) {
            // Shutdown path: individual session cleanup failures must not
            // block the remaining teardown.
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
