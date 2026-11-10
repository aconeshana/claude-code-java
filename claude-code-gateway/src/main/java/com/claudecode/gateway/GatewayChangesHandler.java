package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.queryParam;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

/**
 * The changed-files surfaces: {@code GET /api/changes.summary} reads one
 * turn's changed files, and {@code GET /api/changes.diff} reads one file's
 * hunks.
 */
final class GatewayChangesHandler {

    private final GatewayFileDiffPort fileDiff;

    GatewayChangesHandler(GatewayFileDiffPort fileDiff) {
        this.fileDiff = fileDiff;
    }

    /** Handles one {@code GET /api/changes.summary?session_id=&turn=}. */
    void handleSummary(HttpExchange exchange) throws IOException {
        String rawTurn = queryParam(exchange, "turn");
        int turn;
        try {
            turn = Integer.parseInt(StringUtils.trimToEmpty(rawTurn));
        } catch (NumberFormatException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "turn must be a positive integer"));
            return;
        }
        if (turn < 1) {
            respondJson(exchange, 400, errorBody("invalid_request", "turn must be a positive integer"));
            return;
        }
        String sessionId = sessionIdOf(exchange);
        Optional<GatewayFileDiffPort.ChangesSummary> summary = fileDiff.summary(sessionId, turn);
        if (summary.isEmpty()) {
            // Either the session is unknown or the turn changed no files. The
            // card is only mounted for a turn that has a summary, so an
            // unresolved turn is a genuine miss rather than an empty card.
            respondJson(exchange, 404, errorBody("not_found", "no changes for turn " + turn));
            return;
        }
        respondJson(exchange, 200, writeSummary(summary.get()));
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

    /**
     * Writes the wire projection of one turn's changed files, in the shape the
     * webui's {@code ChangesSummary} is typed against.
     *
     * @param summary the resolved summary
     * @return the JSON object to send
     */
    static ObjectNode writeSummary(GatewayFileDiffPort.ChangesSummary summary) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("turn", summary.turn());
        node.put("total", summary.total());
        node.put("added", summary.added());
        node.put("deleted", summary.deleted());
        ArrayNode files = node.putArray("files");
        for (GatewayFileDiffPort.ChangedFile file : summary.files()) {
            ObjectNode entry = files.addObject();
            entry.put("path", file.path());
            entry.put("display", file.display());
            entry.put("added", file.added());
            entry.put("deleted", file.deleted());
            // Absent means "plain text": the viewer reads a missing flag as
            // false, and omitting the false case keeps the payload to what the
            // file actually is.
            if (file.binary()) entry.put("binary", true);
            if (file.oversized()) entry.put("oversized", true);
        }
        return node;
    }

    /**
     * Writes the wire projection of one file diff, in the shape the webui's
     * {@code WorkspaceFileDiff} union is typed against: a discriminated
     * {@code kind}, and the hunks only when there is text to show.
     *
     * @param diff the resolved diff
     * @return the JSON object to send
     */
    static ObjectNode writeDiff(GatewayFileDiffPort.FileDiff diff) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("path", diff.path());
        node.put("display", diff.display());
        switch (diff.kind()) {
            case BINARY -> node.put("kind", "binary");
            case OVERSIZED -> node.put("kind", "oversized");
            case TEXT -> {
                node.put("kind", "text");
                // A created file has no prior version and a deletion leaves
                // nothing behind; the viewer names both cases from these flags
                // rather than inferring them from an absent side.
                node.put("before", diff.before());
                node.put("after", diff.after());
                // Coarse marks hunks synthesized from a whole-file write rather
                // than recorded by a patch, so the viewer can say the comparison
                // is approximate. The hunks themselves are written verbatim:
                // StructuredPatchHunk carries the same field names the wire
                // format uses, so its own serialization is already the contract.
                node.put("coarse", diff.coarse());
                node.set("hunks", JsonUtils.getMapper().valueToTree(diff.hunks()));
            }
        }
        return node;
    }
}