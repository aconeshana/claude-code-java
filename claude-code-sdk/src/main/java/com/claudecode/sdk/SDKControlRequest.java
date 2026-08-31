package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * Lossless SDK control request envelope.
 * <ul>
 *   <li>{@code SDKControlRequest} union.</li>
 *   <li>{@code SDKControlRequestSchema} wire shape.</li>
 * </ul>
 */
public record SDKControlRequest(String requestId, JsonNode request) {
    public SDKControlRequest {
        requestId = Objects.requireNonNull(requestId, "requestId");
        request = request == null
            ? JsonUtils.getMapper().createObjectNode()
            : request.deepCopy();
    }

    public static SDKControlRequest fromJson(JsonNode envelope) {
        requireEnvelope(envelope, "control_request");
        return new SDKControlRequest(
            requiredText(envelope, "request_id"), envelope.path("request"));
    }

    public ObjectNode toJson() {
        ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
        envelope.put("type", "control_request");
        envelope.put("request_id", requestId);
        envelope.set("request", request);
        return envelope;
    }

    @Override public JsonNode request() {
        return request.deepCopy();
    }

    static void requireEnvelope(JsonNode envelope, String type) {
        if (envelope == null || !envelope.isObject()
                || !type.equals(envelope.path("type").asText())) {
            throw new IllegalArgumentException("Expected " + type + " envelope");
        }
    }

    static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.asText();
    }
}
