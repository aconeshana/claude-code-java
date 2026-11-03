package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET /api/spinner-verbs}: serves the TUI spinner's verb vocabulary
 * so the webui running status words its live row the way the TUI spinner
 * does ({@code "Brewing… (5s)"}).
 *
 * <p>Backed by {@link GatewaySpinnerVerbsPort}, which the CLI composition
 * root implements against {@code SpinnerVerbs}.
 */
final class GatewaySpinnerVerbsHandler {

    private final GatewaySpinnerVerbsPort verbs;

    GatewaySpinnerVerbsHandler(GatewaySpinnerVerbsPort verbs) {
        this.verbs = verbs;
    }

    /** Handles one {@code GET} exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode active = response.putArray("active");
        verbs.activeVerbs().forEach(active::add);
        ObjectNode tools = response.putObject("tools");
        // Sorted so the body is byte-stable across requests.
        for (Map.Entry<String, String> entry : new TreeMap<>(verbs.toolVerbs()).entrySet()) {
            tools.put(entry.getKey(), entry.getValue());
        }
        respondJson(exchange, 200, response);
    }
}
