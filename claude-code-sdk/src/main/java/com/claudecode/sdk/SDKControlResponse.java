package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lossless success/error SDK control response envelope.
 * <ul>
 *   <li>{@code SDKControlResponse} union.</li>
 *   <li>success/error response wire shapes.</li>
 * </ul>
 */
public record SDKControlResponse(String requestId, boolean success, JsonNode response, String error,
                                 List<SDKControlRequest> pendingPermissionRequests) {
    public SDKControlResponse {
        requestId = Objects.requireNonNull(requestId, "requestId");
        response = response == null ? null : response.deepCopy();
        pendingPermissionRequests = pendingPermissionRequests == null
            ? List.of() : List.copyOf(pendingPermissionRequests);
        if (success) {
            error = null;
            pendingPermissionRequests = List.of();
        } else {
            response = null;
            error = Objects.requireNonNull(error, "error");
        }
    }

    public SDKControlResponse(String requestId, boolean success, JsonNode response, String error) {
        this(requestId, success, response, error, List.of());
    }

    public static SDKControlResponse success(String requestId, JsonNode response) {
        return new SDKControlResponse(requestId, true, response, null);
    }

    public static SDKControlResponse error(String requestId, String error) {
        return new SDKControlResponse(requestId, false, null, error);
    }

    public static SDKControlResponse fromJson(JsonNode envelope) {
        SDKControlRequest.requireEnvelope(envelope, "control_response");
        JsonNode body = envelope.path("response");
        String requestId = SDKControlRequest.requiredText(body, "request_id");
        return switch (body.path("subtype").asText()) {
            case "success" -> success(requestId,
                body.has("response") ? body.get("response") : null);
            case "error" -> new SDKControlResponse(requestId, false, null,
                SDKControlRequest.requiredText(body, "error"), pendingRequests(body));
            default -> throw new IllegalArgumentException("Unknown control response subtype");
        };
    }

    public ObjectNode toJson() {
        ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
        envelope.put("type", "control_response");
        ObjectNode body = envelope.putObject("response");
        body.put("subtype", success ? "success" : "error");
        body.put("request_id", requestId);
        if (success) {
            if (response != null) body.set("response", response);
        } else {
            body.put("error", error);
            if (!pendingPermissionRequests.isEmpty()) {
                ArrayNode pending = body.putArray("pending_permission_requests");
                pendingPermissionRequests.forEach(request -> pending.add(request.toJson()));
            }
        }
        return envelope;
    }

    @Override public JsonNode response() {
        return response == null ? null : response.deepCopy();
    }

    private static List<SDKControlRequest> pendingRequests(JsonNode body) {
        JsonNode pending = body.path("pending_permission_requests");
        if (!pending.isArray()) return List.of();
        List<SDKControlRequest> requests = new ArrayList<>();
        pending.forEach(value -> requests.add(SDKControlRequest.fromJson(value)));
        return requests;
    }
}
