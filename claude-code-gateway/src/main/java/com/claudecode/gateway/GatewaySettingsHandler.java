package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET/POST /api/settings}: reads the effective (merged, tier-attributed)
 * settings snapshot and applies one mutation op per request.
 *
 * <p>{@code POST} bodies are dispatched by their {@code op} field:
 * {@code userValue} ({@code key}, {@code value}), {@code permissionMode}
 * ({@code mode}, {@code tier}), {@code permissionRules} ({@code behavior},
 * {@code rules[]}, {@code tier}), {@code addDirectories}/
 * {@code removeDirectories} ({@code directories[]}, {@code tier}). Every
 * successful mutation responds with the refreshed effective snapshot so the
 * web client never has to re-fetch.
 */
final class GatewaySettingsHandler {

    private final GatewaySettingsPort settings;

    GatewaySettingsHandler(GatewaySettingsPort settings) {
        this.settings = settings;
    }

    /** Handles one {@code GET} exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, settings.effectiveSettings());
    }

    /** Handles one {@code POST} exchange. */
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
        String op = text(request, "op");
        try {
            switch (StringUtils.defaultString(op)) {
                case "userValue" -> applyUserValue(request);
                case "permissionMode" -> applyPermissionMode(request);
                case "permissionRules" -> applyPermissionRules(request);
                case "addDirectories" -> settings.addAdditionalDirectories(
                    stringList(request, "directories"), requireTier(request));
                case "removeDirectories" -> settings.removeAdditionalDirectories(
                    stringList(request, "directories"), requireTier(request));
                default -> {
                    respondJson(exchange, 400, errorBody("invalid_request", "unknown op: " + op));
                    return;
                }
            }
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid settings request")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to save settings: " + failure.getMessage()));
            return;
        }
        respondJson(exchange, 200, settings.effectiveSettings());
    }

    private void applyUserValue(JsonNode request) {
        String key = text(request, "key");
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("key is required");
        }
        JsonNode value = request.path("value");
        settings.writeUserValue(key, value.isMissingNode() ? null : value);
    }

    private void applyPermissionMode(JsonNode request) {
        settings.writeDefaultPermissionMode(text(request, "mode"), requireTier(request));
    }

    private void applyPermissionRules(JsonNode request) {
        String behavior = text(request, "behavior");
        if (StringUtils.isBlank(behavior)) {
            throw new IllegalArgumentException("behavior is required");
        }
        switch (behavior) {
            case "allow", "deny", "ask" -> { }
            default -> throw new IllegalArgumentException(
                "unknown permission behavior: " + behavior);
        }
        settings.replacePermissionRules(behavior, stringList(request, "rules"), requireTier(request));
    }

    private static String requireTier(JsonNode request) {
        String tier = text(request, "tier");
        if (StringUtils.isBlank(tier)) {
            throw new IllegalArgumentException("tier is required");
        }
        // The wire contract is owned here: unknown tiers never reach the
        // CLI adapter's enum translation.
        switch (tier) {
            case "user", "project", "local" -> { }
            default -> throw new IllegalArgumentException("unknown settings tier: " + tier);
        }
        return tier;
    }

    private static List<String> stringList(JsonNode request, String field) {
        JsonNode value = request.path(field);
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode entry : (ArrayNode) value) {
            if (entry.isTextual()) result.add(entry.asText());
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
