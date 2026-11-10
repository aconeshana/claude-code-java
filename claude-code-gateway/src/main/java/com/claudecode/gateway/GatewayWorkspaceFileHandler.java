package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * {@code GET /api/workspace-file?session_id=&path=} — one workspace file's
 * content for the webui file-preview tab.
 */
final class GatewayWorkspaceFileHandler {

    private final GatewayWorkspaceFilePort files;

    GatewayWorkspaceFileHandler(GatewayWorkspaceFilePort files) {
        this.files = files;
    }

    void handleGet(HttpExchange exchange) throws IOException {
        String path = StringUtils.trimToEmpty(queryParam(exchange, "path"));
        if (path.isEmpty()) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        Optional<GatewayWorkspaceFilePort.TextFile> file = files.read(sessionIdOf(exchange), path);
        if (file.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found", "no readable file at " + path));
            return;
        }
        GatewayWorkspaceFilePort.TextFile content = file.get();
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("path", content.path());
        body.put("kind", content.kind().name().toLowerCase(Locale.ROOT));
        if (content.kind() == GatewayWorkspaceFilePort.TextFile.Kind.TEXT) {
            body.put("text", content.text());
        }
        respondJson(exchange, 200, body);
    }
}
