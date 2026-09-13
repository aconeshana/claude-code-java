package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET/POST /api/models} and {@code DELETE /api/models/{name}}: lists,
 * saves, and removes entries in the custom model catalogue shown in the
 * webui's Models settings section.
 *
 * <p>Backed by {@link GatewayModelsPort}, which the CLI composition root
 * implements against the same {@code CustomModelCatalog} instance used by
 * the {@code /model} command, so both surfaces read and write one file.
 * {@code api_key} is never echoed back to the client; {@code POST} accepts
 * it as absent (keep existing), {@code null} (clear), or a string (set).
 */
final class GatewayModelsHandler {

    private static final Set<String> VALID_PROTOCOLS = Set.of("anthropic", "chat", "responses");

    private final GatewayModelsPort models;

    GatewayModelsHandler(GatewayModelsPort models) {
        this.models = models;
    }

    /** Handles one {@code GET} (list) exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, listBody());
    }

    /** Handles one {@code POST} (save) exchange. */
    void handlePost(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "body is not valid JSON"));
            return;
        }
        String modelName = text(request, "model_name");
        String protocol = text(request, "protocol");
        String baseUrl = text(request, "base_url");
        if (StringUtils.isBlank(modelName) || StringUtils.isBlank(protocol)
                || StringUtils.isBlank(baseUrl)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "model_name, protocol, and base_url are required"));
            return;
        }
        if (!VALID_PROTOCOLS.contains(protocol)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "unknown model protocol: " + protocol));
            return;
        }
        JsonNode apiKey = request.path("api_key");
        Map<String, String> headers = stringMap(request, "headers");
        Long contextWindow = request.hasNonNull("context_window")
            ? request.path("context_window").asLong() : null;
        Boolean multimodal = request.has("multimodal")
            ? request.path("multimodal").isBoolean()
                ? request.path("multimodal").asBoolean() : null
            : null;
        try {
            models.save(modelName, protocol, baseUrl, apiKey, headers, contextWindow, multimodal);
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid model configuration")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to save model: " + failure.getMessage()));
            return;
        }
        respondJson(exchange, 200, listBody());
    }

    /** Handles one {@code DELETE} exchange for the model named {@code modelName}. */
    void handleDelete(HttpExchange exchange, String modelName) throws IOException {
        if (StringUtils.isBlank(modelName)) {
            respondJson(exchange, 400, errorBody("invalid_request", "model_name is required"));
            return;
        }
        boolean removed = models.remove(modelName);
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("removed", removed);
        respondJson(exchange, removed ? 200 : 404, response);
    }

    private ObjectNode listBody() {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode entries = response.putArray("models");
        for (GatewayModelsPort.ModelEntry entry : models.list()) {
            ObjectNode node = entries.addObject();
            node.put("model_name", entry.modelName());
            node.put("protocol", entry.protocol());
            node.put("base_url", entry.baseUrl());
            node.put("has_api_key", entry.hasApiKey());
            ObjectNode headers = node.putObject("headers");
            entry.headers().forEach(headers::put);
            if (entry.contextWindow() != null) node.put("context_window", entry.contextWindow());
            if (entry.multimodal() != null) node.put("multimodal", entry.multimodal());
        }
        return response;
    }

    private static Map<String, String> stringMap(JsonNode request, String fieldName) {
        JsonNode value = request.path(fieldName);
        if (!value.isObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isTextual()) result.put(field.getKey(), field.getValue().asText());
        }
        return result;
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
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
