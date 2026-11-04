package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * The changed-files surfaces: {@code GET /api/changes.diff} reads one file's
 * diff, and {@code POST /api/changes.open} asks the host to open a file.
 *
 * <p>The viewer that consumes these is a browser page. It can read a diff
 * through this gateway, but it has no local filesystem and no desktop host to
 * hand a file to, so {@code changes.open} answers an explicit
 * {@code 422 unavailable} instead of silently succeeding. The webui renders its
 * open-file control disabled with that reason — upstream omits the control
 * where no desktop host exists; rendering it is this project's deliberate
 * difference, recorded in {@code webui/UPSTREAM.md}.
 */
final class GatewayChangesHandler {

    private final GatewayFileDiffPort fileDiff;

    GatewayChangesHandler(GatewayFileDiffPort fileDiff) {
        this.fileDiff = fileDiff;
    }

    /** Handles one {@code GET /api/changes.diff?session_id=&path=}. */
    void handleDiff(HttpExchange exchange) throws IOException {
        String path = queryParam(exchange, "path");
        if (StringUtils.isBlank(path)) {
            respondJson(exchange, 400, errorBody("invalid_request", "path is required"));
            return;
        }
        String sessionId = sessionIdOf(exchange);
        Optional<GatewayFileDiffPort.FileDiff> diff = fileDiff.diff(sessionId, path);
        if (diff.isEmpty()) {
            // The session or the file is unknown. This is a genuine miss — the
            // chip the user clicked names a file this session's turns never
            // touched — so it is a 404 rather than an empty diff, which would
            // render as "no changes" and misreport why nothing is shown.
            respondJson(exchange, 404, errorBody("not_found", "no diff for " + path));
            return;
        }
        respondJson(exchange, 200, writeDiff(diff.get()));
    }

    /** Handles one {@code POST /api/changes.open}. */
    void handleOpen(HttpExchange exchange) throws IOException {
        // The body is read so the connection drains, but no field of it can
        // change the answer: this host has no desktop to open a file with.
        exchange.getRequestBody().readAllBytes();
        respondJson(exchange, 422, errorBody("unavailable",
            "this client runs in a browser and cannot open local files"));
    }

    /**
     * Writes the wire projection of one file diff, in the shape the webui's
     * {@code WorkspaceFileDiff} union is typed against: a discriminated
     * {@code kind}, and the two sides only when there is text to show.
     *
     * @param diff the resolved diff
     * @return the JSON object to send
     */
    static ObjectNode writeDiff(GatewayFileDiffPort.FileDiff diff) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("path", diff.path());
        // The display path is what the viewer heads the diff with. It is the
        // reported path verbatim: this gateway has no workspace-relative
        // projection to shorten it against, and a shortened path would be a
        // guess about which root the file sits under.
        node.put("display", diff.path());
        switch (diff.kind()) {
            case BINARY -> node.put("kind", "binary");
            case OVERSIZED -> node.put("kind", "oversized");
            case TEXT -> {
                node.put("kind", "text");
                // A null old side is a created file: the viewer draws no
                // removed lines, which is exactly upstream's `oldText: null`.
                if (diff.oldText() == null) node.putNull("old_text");
                else node.put("old_text", diff.oldText());
                node.put("new_text", diff.newText());
                node.put("truncated", diff.truncated());
            }
        }
        return node;
    }
}