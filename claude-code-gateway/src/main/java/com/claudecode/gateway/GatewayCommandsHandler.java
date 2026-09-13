package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET /api/commands}: lists the slash commands and skill commands the
 * webui composer's "+" menu offers, mirroring dsh's host command catalog
 * listing for its composer menu.
 *
 * <p>Backed by {@link GatewayCommandsPort}, which the CLI composition root
 * implements against the interactive {@code CommandRegistry} (commands plus
 * user-invocable skill projections share one registry there).
 */
final class GatewayCommandsHandler {

    private final GatewayCommandsPort commands;

    GatewayCommandsHandler(GatewayCommandsPort commands) {
        this.commands = commands;
    }

    /** Handles one {@code GET} (list) exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode entries = response.putArray("commands");
        for (GatewayCommandsPort.CommandEntry entry : commands.list()) {
            ObjectNode node = entries.addObject();
            node.put("name", entry.name());
            if (entry.description() != null) node.put("description", entry.description());
            if (entry.argumentHint() != null) node.put("argument_hint", entry.argumentHint());
            node.put("kind", entry.kind());
        }
        respondJson(exchange, 200, response);
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
