package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostPermissionMode;
import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET/POST /api/session/permission-mode}: one session's live
 * permission-mode selection, feeding the webui composer's permission-mode
 * chip — the remote counterpart of the TUI Shift+Tab cycle.
 *
 * <p>The addressed session arrives as {@code ?session_id=} (GET) or a
 * {@code session_id} body field (POST); a blank id keeps the legacy
 * target — the active TUI session. Both halves ride
 * {@link GatewayPermissionModePort}, which the CLI composition root
 * implements against the same {@code PermissionGate} the TUI Shift+Tab
 * cycle and the SDK control-request path enforce.
 */
final class GatewayPermissionModeHandler {

    private final GatewayPermissionModePort permissionMode;

    GatewayPermissionModeHandler(GatewayPermissionModePort permissionMode) {
        this.permissionMode = permissionMode;
    }

    /** Handles one {@code GET} exchange: the addressed session's current state. */
    void handleGet(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, body(sessionIdOf(exchange)));
    }

    /** Handles one {@code POST} exchange: select a new permission mode. */
    void handlePost(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "body is not valid JSON"));
            return;
        }
        String sessionId = StringUtils.trimToNull(text(request, "session_id"));
        String mode = text(request, "mode");
        if (StringUtils.isBlank(mode)) {
            respondJson(exchange, 400, errorBody("invalid_request", "mode is required"));
            return;
        }
        GatewayPermissionModePort.SelectionResult result =
            permissionMode.selectMode(sessionId, mode.strip());
        if (!result.accepted()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(result.error(), "permission mode was rejected")));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        stateBody(response.putObject("permission_mode"), result.state());
        respondJson(exchange, 200, response);
    }

    /** The addressed session id: {@code ?session_id=}, or null for the active TUI session. */
    private static String sessionIdOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) return null;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            if (!Strings.CS.equals("session_id", pair.substring(0, split))) continue;
            return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private ObjectNode body(String sessionId) {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        Optional<SessionHostPermissionState> state = permissionMode.state(sessionId);
        if (state.isPresent()) {
            stateBody(response.putObject("permission_mode"), state.get());
        } else {
            response.putNull("permission_mode");
        }
        return response;
    }

    private static void stateBody(ObjectNode node, SessionHostPermissionState state) {
        node.put("current", state.current());
        ArrayNode modes = node.putArray("modes");
        for (SessionHostPermissionMode mode : state.modes()) {
            ObjectNode row = modes.addObject();
            row.put("value", mode.value());
            row.put("title", mode.title());
            row.put("short_title", mode.shortTitle());
            row.put("symbol", mode.symbol());
            row.put("color_key", mode.colorKey());
            row.put("available", mode.available());
        }
        node.put("bypass_permissions_available", state.bypassPermissionsAvailable());
        node.put("bypass_permissions_disabled_by_policy", state.bypassPermissionsDisabledByPolicy());
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
