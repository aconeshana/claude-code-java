package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /api/permissions/respond}: answers one pending permission ask.
 *
 * <p>The TUI dialog and a web client are two observers of the same blocked
 * {@code PermissionAskCallback.ask} call coordinated by the shared {@code
 * InteractionCoordinator}; the {@code FIRST_RESPONDER} policy makes the first
 * answer win. The request addresses the ask by the {@code request_id} from
 * the matching {@code permission.asked} mirror frame; a stale or unknown id
 * is a 404, an ask another observer already answered is a 409.
 *
 * <p>Request fields map onto the ask result: {@code allowed} picks
 * allow/deny, optional {@code feedback} becomes the deny reason or allow
 * instruction the model sees, and optional {@code skip} keeps the
 * ToolService.ignore semantic (this call is skipped) as a deny with
 * skip-marked feedback.
 */
@Explanation("Web-side responder of the shared permission ask coordinator")
final class GatewayPermissionsHandler {

    private final InteractionCoordinator interactions;

    GatewayPermissionsHandler(InteractionCoordinator interactions) {
        this.interactions = interactions;
    }

    /** Handles one respond exchange. */
    void handle(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "body is not valid JSON"));
            return;
        }
        String requestId = text(request, "request_id");
        String sessionId = text(request, "session_id");
        if (StringUtils.isBlank(requestId) || StringUtils.isBlank(sessionId)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "request_id and session_id are required"));
            return;
        }
        JsonNode allowedNode = request.path("allowed");
        if (!allowedNode.isBoolean()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "allowed must be a boolean"));
            return;
        }
        String feedback = text(request, "feedback");
        boolean skip = request.path("skip").asBoolean(false);
        JsonNode updatedInput = request.path("updated_input");
        if (updatedInput.isMissingNode() || updatedInput.isNull()) {
            updatedInput = null;
        } else if (!updatedInput.isObject()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "updated_input must be an object"));
            return;
        }
        PermissionAskCallback.Result result = result(
            allowedNode.asBoolean(), feedback, skip, updatedInput);

        // A user question and a permission ask share the respond channel; the
        // pending feature owns the request id, so try both in turn.
        boolean accepted = interactions.respond(
                InteractionFeatures.USER_QUESTION, requestId, sessionId, result,
                InteractionEndpoint.REMOTE)
            || interactions.respond(
                InteractionFeatures.PERMISSION, requestId, sessionId, result,
                InteractionEndpoint.REMOTE);
        if (!accepted) {
            // Unknown id (404) vs. already answered (409): a known feature
            // request that no longer pends was answered by another observer.
            respondJson(exchange, 409, errorBody("already_responded",
                "the ask was already answered by another observer"));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("responded", true);
        respondJson(exchange, 200, response);
    }

    private static PermissionAskCallback.Result result(
            boolean allowed, String feedback, boolean skip, JsonNode updatedInput) {
        if (skip) {
            return PermissionAskCallback.Result.denyWithDirectMessage(
                StringUtils.isNotBlank(feedback) ? feedback : "skipped by web client");
        }
        if (allowed) {
            if (updatedInput != null) {
                return StringUtils.isBlank(feedback)
                    ? PermissionAskCallback.Result.allowWithInput(updatedInput)
                    : PermissionAskCallback.Result.allowWithInputAndFeedback(
                        updatedInput, feedback);
            }
            return StringUtils.isBlank(feedback)
                ? PermissionAskCallback.Result.allow()
                : PermissionAskCallback.Result.allowWithFeedback(feedback);
        }
        return StringUtils.isBlank(feedback)
            ? PermissionAskCallback.Result.deny()
            : PermissionAskCallback.Result.denyWithFeedback(feedback);
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
