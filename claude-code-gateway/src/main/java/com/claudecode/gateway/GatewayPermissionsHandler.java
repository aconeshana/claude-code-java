package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readRequestJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.sessionlink.SessionLinkClient;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(10);

    private final InteractionCoordinator interactions;
    private final RemoteWorkerPool remoteWorkers;

    GatewayPermissionsHandler(InteractionCoordinator interactions, RemoteWorkerPool remoteWorkers) {
        this.interactions = interactions;
        this.remoteWorkers = remoteWorkers;
    }

    /** Handles one respond exchange. */
    void handle(HttpExchange exchange) throws IOException {
        JsonNode request = readRequestJson(exchange);
        if (request == null) return;
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
        boolean allowed = allowedNode.asBoolean();
        PermissionAskCallback.Result result = result(allowed, feedback, skip, updatedInput);

        // A user question and a permission ask share the respond channel; the
        // pending feature owns the request id, so try both in turn.
        boolean accepted = interactions.respond(
                InteractionFeatures.USER_QUESTION, requestId, sessionId, result,
                InteractionEndpoint.REMOTE)
            || interactions.respond(
                InteractionFeatures.PERMISSION, requestId, sessionId, result,
                InteractionEndpoint.REMOTE);
        if (accepted) {
            respondResponded(exchange);
            return;
        }
        ObjectNode remotePayload = remotePayload(requestId, allowed, skip, feedback, updatedInput);
        Optional<CompletableFuture<SessionLinkFrame>> forwarded = remoteWorkers != null
            ? remoteWorkers.respondInteraction(sessionId, remotePayload)
            : Optional.empty();
        if (forwarded.isEmpty()) {
            // Nothing is pending for this id here, so the answer has no ask to
            // land on. That covers a late answer to an ask another observer
            // already resolved and an id this gateway never issued; both reach
            // the client as the same conflict, because from the client's side
            // they are the same outcome — its dialog is gone either way.
            respondJson(exchange, 409, errorBody("already_responded",
                "the ask is no longer pending; it was answered by another observer"));
            return;
        }
        respondForwarded(exchange, forwarded.get());
    }

    private static void respondResponded(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("responded", true);
        respondJson(exchange, 200, response);
    }

    private static void respondForwarded(HttpExchange exchange, CompletableFuture<SessionLinkFrame> reply)
            throws IOException {
        try {
            reply.get(REMOTE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            respondResponded(exchange);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SessionLinkClient.RemoteException remote
                    && Strings.CS.equals(remote.code(), "stale_interaction")) {
                respondJson(exchange, 409, errorBody("already_responded",
                    "the ask was already answered by another observer"));
                return;
            }
            respondJson(exchange, 502, errorBody("remote_rejected",
                StringUtils.defaultIfBlank(failure.getCause() == null
                    ? null : failure.getCause().getMessage(), "remote session rejected the answer")));
        } catch (TimeoutException _) {
            respondJson(exchange, 502, errorBody("remote_timeout",
                "remote session did not answer in time"));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 502, errorBody("remote_timeout",
                "interrupted while waiting for the remote session"));
        }
    }

    private static ObjectNode remotePayload(
            String requestId, boolean allowed, boolean skip, String feedback, JsonNode updatedInput) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("request_id", requestId);
        boolean allow = allowed && !skip;
        payload.put("behavior", allow ? "allow" : "deny");
        if (updatedInput != null && allow) payload.set("updated_input", updatedInput);
        String message = skip
            ? (StringUtils.isNotBlank(feedback) ? feedback : "skipped by web client")
            : feedback;
        if (StringUtils.isNotBlank(message)) payload.put("message", message);
        return payload;
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

}
