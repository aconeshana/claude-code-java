package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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

    private static final Set<String> VALID_PROTOCOLS =
        Set.of("anthropic", "chat", "responses", "gemini");

    private static final Duration DISCOVER_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DISCOVER_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient DISCOVER_HTTP = HttpClient.newBuilder()
        .connectTimeout(DISCOVER_CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private final GatewayModelsPort models;

    /** One model entry returned by a provider's {@code /models} endpoint. */
    record DiscoveredModel(
        String id,
        String displayName,
        String ownedBy,
        Long contextWindow) {}

    GatewayModelsHandler(GatewayModelsPort models) {
        this.models = models;
    }

    /** Handles one {@code GET} (list) exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, listBody());
    }

    /** Handles one {@code POST} (save) exchange. */
    void handlePost(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
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

    /**
     * Handles {@code POST /api/models/discover}: queries a provider's standard
     * {@code /models} endpoint (OpenAI, Anthropic, or Gemini shape) server-side
     * so the webui can import multiple models from one provider without CORS
     * restrictions, while keeping flat {@code model.json} persistence unchanged.
     */
    void handleDiscover(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
        String baseUrl = StringUtils.trimToNull(text(request, "base_url"));
        String protocol = StringUtils.defaultIfBlank(text(request, "protocol"), "chat").trim();
        if (baseUrl == null) {
            respondJson(exchange, 400, errorBody("invalid_request", "base_url is required"));
            return;
        }
        if (!VALID_PROTOCOLS.contains(protocol)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "unknown model protocol: " + protocol));
            return;
        }
        List<String> candidates;
        try {
            candidates = resolveDiscoverUrls(baseUrl, protocol);
        } catch (IllegalArgumentException invalid) {
            respondJson(exchange, 400, errorBody("invalid_request", invalid.getMessage()));
            return;
        }
        String apiKey = StringUtils.trimToNull(text(request, "api_key"));
        Map<String, String> customHeaders = stringMap(request, "headers");

        int lastStatus = 0;
        String lastError = null;
        for (String endpoint : candidates) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(DISCOVER_REQUEST_TIMEOUT)
                    .GET()
                    .header("Accept", "application/json");
                Map<String, String> mergedHeaders = new LinkedHashMap<>();
                if (apiKey != null) {
                    if (Strings.CS.equals("anthropic", protocol)) {
                        mergedHeaders.put("x-api-key", apiKey);
                        mergedHeaders.put("anthropic-version", "2023-06-01");
                        mergedHeaders.put("Authorization", "Bearer " + apiKey);
                    } else if (Strings.CS.equals("gemini", protocol)) {
                        mergedHeaders.put("x-goog-api-key", apiKey);
                        mergedHeaders.put("Authorization", "Bearer " + apiKey);
                    } else {
                        mergedHeaders.put("Authorization", "Bearer " + apiKey);
                    }
                }
                mergedHeaders.putAll(customHeaders);
                mergedHeaders.forEach(req::header);

                HttpResponse<String> response = DISCOVER_HTTP.send(
                    req.build(), HttpResponse.BodyHandlers.ofString());
                lastStatus = response.statusCode();
                if (lastStatus == 404 && candidates.size() > 1) {
                    lastError = "HTTP 404 from " + endpoint;
                    continue;
                }
                if (lastStatus < 200 || lastStatus >= 300) {
                    String snippet = StringUtils.abbreviate(
                        StringUtils.defaultString(response.body()).replaceAll("\\s+", " ").trim(),
                        240);
                    respondJson(exchange, 502, errorBody("upstream_error",
                        "Provider returned HTTP " + lastStatus + " from " + endpoint
                            + (snippet.isEmpty() ? "" : ": " + snippet)));
                    return;
                }
                JsonNode parsed = JsonUtils.getMapper().readTree(response.body());
                List<DiscoveredModel> discovered = parseDiscoveredModels(parsed);
                ObjectNode body = JsonUtils.getMapper().createObjectNode();
                body.put("endpoint", endpoint);
                ArrayNode list = body.putArray("models");
                for (DiscoveredModel m : discovered) {
                    ObjectNode row = list.addObject();
                    row.put("id", m.id());
                    if (m.displayName() != null) row.put("display_name", m.displayName());
                    if (m.ownedBy() != null) row.put("owned_by", m.ownedBy());
                    if (m.contextWindow() != null) row.put("context_window", m.contextWindow());
                }
                respondJson(exchange, 200, body);
                return;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                respondJson(exchange, 504, errorBody("timeout", "model discovery interrupted"));
                return;
            } catch (IOException | RuntimeException failure) {
                lastError = failure.getMessage();
            }
        }
        respondJson(exchange, 502, errorBody("upstream_error",
            StringUtils.defaultIfBlank(lastError,
                "Failed to fetch models (HTTP " + lastStatus + ")")));
    }

    /**
     * Handles {@code POST /api/models/batch}: saves multiple discovered models
     * sharing one provider's {@code protocol}, {@code base_url}, {@code api_key},
     * and {@code headers} as standard individual entries in {@code model.json}.
     */
    void handleBatchSave(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
        String protocol = text(request, "protocol");
        String baseUrl = text(request, "base_url");
        if (StringUtils.isBlank(protocol) || StringUtils.isBlank(baseUrl)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "protocol and base_url are required"));
            return;
        }
        if (!VALID_PROTOCOLS.contains(protocol)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "unknown model protocol: " + protocol));
            return;
        }
        JsonNode entries = request.path("models");
        if (!entries.isArray() || entries.isEmpty()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "models must be a non-empty array"));
            return;
        }
        JsonNode apiKey = request.path("api_key");
        Map<String, String> headers = stringMap(request, "headers");
        Long defaultContextWindow = request.hasNonNull("context_window")
            ? request.path("context_window").asLong() : null;
        Boolean defaultMultimodal = request.has("multimodal") && request.path("multimodal").isBoolean()
            ? request.path("multimodal").asBoolean() : null;

        int saved = 0;
        try {
            for (JsonNode item : entries) {
                String modelName = item.isTextual()
                    ? item.asText()
                    : text(item, "model_name");
                if (StringUtils.isBlank(modelName)) continue;
                Long cw = item.isObject() && item.hasNonNull("context_window")
                    ? item.path("context_window").asLong()
                    : defaultContextWindow;
                Boolean mm = item.isObject() && item.has("multimodal") && item.path("multimodal").isBoolean()
                    ? item.path("multimodal").asBoolean()
                    : defaultMultimodal;
                models.save(modelName.trim(), protocol, baseUrl.trim(), apiKey, headers, cw, mm);
                saved++;
            }
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(failure.getMessage(), "invalid model configuration")));
            return;
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to save models: " + failure.getMessage()));
            return;
        }
        if (saved == 0) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "no valid model_name entries provided"));
            return;
        }
        ObjectNode body = listBody();
        body.put("saved", saved);
        respondJson(exchange, 200, body);
    }

    /** Resolves candidate {@code /models} URLs for a provider base URL and protocol. */
    static List<String> resolveDiscoverUrls(String rawBaseUrl, String protocol) {
        String trimmed = StringUtils.stripEnd(rawBaseUrl.trim(), "/");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("invalid base_url: " + rawBaseUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Strings.CS.equals("http", scheme) && !Strings.CS.equals("https", scheme)) {
            throw new IllegalArgumentException("base_url must use http or https");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (Strings.CS.endsWith(lower, "/models")) {
            return List.of(trimmed);
        }
        if (Strings.CS.equals("gemini", protocol)) {
            if (Strings.CS.endsWith(lower, "/v1beta") || Strings.CS.endsWith(lower, "/v1")) {
                return List.of(trimmed + "/models");
            }
            return List.of(trimmed + "/v1beta/models", trimmed + "/models");
        }
        if (Strings.CS.endsWith(lower, "/v1") || Strings.CS.endsWith(lower, "/v1beta")) {
            return List.of(trimmed + "/models");
        }
        if (Strings.CS.equals("anthropic", protocol)) {
            return List.of(trimmed + "/v1/models", trimmed + "/models");
        }
        return List.of(trimmed + "/models", trimmed + "/v1/models");
    }

    /** Parses OpenAI (`data[]`), Anthropic (`data[]`), Gemini (`models[]`), or bare array responses. */
    static List<DiscoveredModel> parseDiscoveredModels(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) return List.of();
        JsonNode array = root.isArray() ? root
            : root.path("data").isArray() ? root.path("data")
            : root.path("models").isArray() ? root.path("models")
            : null;
        if (array == null) return List.of();
        List<DiscoveredModel> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isObject()) continue;
            String id = StringUtils.trimToNull(text(item, "id"));
            if (id == null) {
                String name = StringUtils.trimToNull(text(item, "name"));
                if (name != null) {
                    id = Strings.CS.startsWith(name, "models/")
                        ? name.substring("models/".length()) : name;
                }
            }
            if (id == null || !seen.add(id)) continue;
            String displayName = StringUtils.trimToNull(text(item, "display_name"));
            if (displayName == null) {
                displayName = StringUtils.trimToNull(text(item, "displayName"));
            }
            String ownedBy = StringUtils.trimToNull(text(item, "owned_by"));
            Long contextWindow = positiveLong(item, "context_window");
            if (contextWindow == null) contextWindow = positiveLong(item, "context_length");
            if (contextWindow == null) contextWindow = positiveLong(item, "inputTokenLimit");
            out.add(new DiscoveredModel(id, displayName, ownedBy, contextWindow));
        }
        return out;
    }

    private static Long positiveLong(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isNumber()) return null;
        long parsed = v.asLong();
        return parsed > 0 ? parsed : null;
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

}
