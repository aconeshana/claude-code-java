package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    void handleList(HttpExchange exchange) throws IOException {
        String dir = StringUtils.trimToEmpty(queryParam(exchange, "path"));
        Optional<GatewayWorkspaceFilePort.DirectoryListing> listing =
            files.list(sessionIdOf(exchange), dir);
        if (listing.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found", "no readable directory at " + dir));
            return;
        }
        GatewayWorkspaceFilePort.DirectoryListing result = listing.get();
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("path", result.path());
        ArrayNode entries = body.putArray("entries");
        for (GatewayWorkspaceFilePort.DirectoryEntry entry : result.entries()) {
            ObjectNode row = entries.addObject();
            row.put("name", entry.name());
            row.put("path", entry.path());
            row.put("directory", entry.directory());
            row.put("size", entry.size());
        }
        respondJson(exchange, 200, body);
    }

    void handleOpen(HttpExchange exchange) throws IOException {
        JsonNode req = readJson(exchange);
        if (req == null) return;
        String path = StringUtils.trimToEmpty(req.path("path").asText(""));
        if (path.isEmpty()) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        String sessionId = StringUtils.trimToNull(req.path("session_id").asText(""));
        if (sessionId == null) {
            sessionId = sessionIdOf(exchange);
        }
        boolean reveal = req.path("reveal").asBoolean(false);
        if (!files.openInHost(sessionId, path, reveal)) {
            respondJson(exchange, 404, errorBody("not_found", "cannot open file at " + path));
            return;
        }
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("opened", true);
        body.put("path", path);
        body.put("reveal", reveal);
        respondJson(exchange, 200, body);
    }
}
