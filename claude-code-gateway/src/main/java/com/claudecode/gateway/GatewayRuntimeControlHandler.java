package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * {@code GET/POST /api/runtime/keepalive} and {@code POST /api/runtime/restart}:
 * controls host idle-sleep prevention and in-place runtime reload without
 * dropping the WebUI HTTP listener or token.
 */
final class GatewayRuntimeControlHandler {

    private final GatewayRuntimeControlPort control;

    GatewayRuntimeControlHandler(GatewayRuntimeControlPort control) {
        this.control = control;
    }

    void handleGetKeepalive(HttpExchange exchange) throws IOException {
        respondKeepalive(exchange, control.keepalive());
    }

    void handleSetKeepalive(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
        if (!request.has("enabled") || !request.get("enabled").isBoolean()) {
            respondJson(exchange, 400, errorBody("invalid_request", "enabled boolean is required"));
            return;
        }
        respondKeepalive(exchange, control.setKeepalive(request.get("enabled").asBoolean()));
    }

    void handleRestart(HttpExchange exchange) throws IOException {
        GatewayRuntimeControlPort.RestartResult result = control.restartRuntime();
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("restarted", result.restarted());
        body.put("reloaded_tasks", result.reloadedTasks());
        body.put("reloaded_models", result.reloadedModels());
        respondJson(exchange, 200, body);
    }

    private static void respondKeepalive(
            HttpExchange exchange, GatewayRuntimeControlPort.KeepaliveState state) throws IOException {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("enabled", state.enabled());
        body.put("supported", state.supported());
        body.put("active", state.active());
        respondJson(exchange, 200, body);
    }
}
