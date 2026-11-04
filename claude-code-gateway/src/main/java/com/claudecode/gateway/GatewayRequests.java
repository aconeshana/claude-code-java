package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

/**
 * Request-side counterparts of {@link GatewayResponses}: the small reads every
 * JSON handler performs on its decoded body.
 *
 * <p>These used to be copied verbatim into each handler. They are deliberately
 * lenient — a missing field, a wrong type, and an explicit JSON {@code null}
 * all read as {@code null} rather than raising — because the gateway answers a
 * malformed request with a protocol error body, not a stack trace.
 */
final class GatewayRequests {

    private GatewayRequests() {}

    /**
     * The decoded request body, or {@code null} when the exchange has already
     * been answered with a 400. An empty body decodes to an empty object: such
     * a request has no fields rather than malformed ones, so the handler's own
     * required-field check reports it (with a more specific message) instead.
     */
    static JsonNode readJson(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (StringUtils.isBlank(body)) return JsonUtils.getMapper().createObjectNode();
        try {
            return JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "body is not valid JSON"));
            return null;
        }
    }

    /** The field as text, or {@code null} when absent or not a JSON string. */
    static String text(JsonNode request, String field) {
        JsonNode value = request.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** The addressed session id: {@code ?session_id=}, or null for the active TUI session. */
    static String sessionIdOf(HttpExchange exchange) {
        return queryParam(exchange, "session_id");
    }

    /**
     * One decoded query parameter, or null when absent.
     *
     * @param exchange the request
     * @param name     the parameter name
     * @return the decoded value, or null
     */
    static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) return null;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            if (!Strings.CS.equals(name, pair.substring(0, split))) continue;
            return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}