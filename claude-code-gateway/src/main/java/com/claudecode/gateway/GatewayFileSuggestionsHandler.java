package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET /api/file-suggestions}: workspace path candidates for the webui
 * composer's {@code @} completion menu.
 *
 * <p>Backed by {@link GatewayFileSuggestionsPort}, which the CLI composition
 * root implements against the same workspace enumeration the TUI's
 * {@code @}-completion uses. Rows carry paths only — selecting a candidate
 * never reads or attaches file contents (the submission-time
 * {@code AtMentionedFilesProvider} parses the {@code @path} token the pick
 * inserted and reads the file then), matching dsh's host file-reference
 * contract.
 */
final class GatewayFileSuggestionsHandler {

    private final GatewayFileSuggestionsPort files;

    GatewayFileSuggestionsHandler(GatewayFileSuggestionsPort files) {
        this.files = files;
    }

    /** Handles one {@code GET} exchange; {@code ?q=} is the live query. */
    void handleGet(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode entries = response.putArray("files");
        for (GatewayFileSuggestionsPort.FileCandidate candidate
                : files.list(queryOf(exchange))) {
            ObjectNode node = entries.addObject();
            node.put("path", candidate.path());
            node.put("directory", candidate.directory());
        }
        respondJson(exchange, 200, response);
    }

    /** The {@code ?q=} query, or an empty string when absent. */
    private static String queryOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) return "";
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            if (!Strings.CS.equals("q", pair.substring(0, split))) continue;
            return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
        }
        return "";
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