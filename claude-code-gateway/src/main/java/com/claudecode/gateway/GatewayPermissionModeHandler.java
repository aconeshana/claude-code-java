package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostPermissionMode;
import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
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
        JsonNode request = readJson(exchange);
        if (request == null) return;
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
        writeState(node, state);
    }

    /**
     * Writes the wire projection of a permission-mode state. Shared with the
     * mirror broadcast so the SSE frame and this endpoint's body cannot drift.
     */
    static void writeState(ObjectNode node, SessionHostPermissionState state) {
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

}
