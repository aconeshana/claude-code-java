package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.runtime.sessionlink.SessionLinkClient;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code POST /api/sudo/respond}: answers one pending sudo password ask.
 *
 * <p>Counterpart of {@link GatewayPermissionsHandler} for a different kind of
 * prompt. The web client sees {@code sudo.asked} on the mirror stream and
 * posts back either {@code {"behavior":"submit","password":"…"}} or
 * {@code {"behavior":"cancel"}}; the ask is completed the same way the TUI
 * dialog completes it, and {@code FIRST_RESPONDER} makes whichever side
 * answers first the one that wins.
 *
 * <h2>How the password is handled</h2>
 *
 * <p>The password is a secret in transit for exactly the width of this call:
 * it is read from the request body, moved into a {@code char[]}, handed to a
 * one-shot {@code Provided}, and wiped by the sudo run when the answer is
 * accepted — or by this handler when it is refused, since nobody else holds
 * it then. It is never logged (the gateway's request log
 * records paths, not bodies), never echoed in the response, and never placed
 * on the mirror stream — that stream carries only the resolved outcome.
 *
 * <p>Delivery is to this process's own coordinator when it happens to own the
 * ask, and otherwise over the owning TUI's Session Link, exactly as a
 * permission answer travels. A browser answering a session the gateway does
 * not own cannot happen: the ask only reached it through that link.
 */
@Explanation("Web-side responder for the sudo password interaction")
final class GatewaySudoRespondHandler {

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_PASSWORD_CHARS = 1024;

    private final InteractionCoordinator interactions;
    private final Optional<RemoteWorkerPool> remoteWorkers;

    GatewaySudoRespondHandler(
            InteractionCoordinator interactions, Optional<RemoteWorkerPool> remoteWorkers) {
        this.interactions = interactions;
        this.remoteWorkers = remoteWorkers;
    }

    /** Handles one respond exchange. */
    void handle(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
        String requestId = text(request, "request_id");
        String sessionId = text(request, "session_id");
        String behavior = text(request, "behavior");
        if (StringUtils.isBlank(requestId) || StringUtils.isBlank(sessionId)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "request_id and session_id are required"));
            return;
        }
        if (!Strings.CS.equals("submit", behavior) && !Strings.CS.equals("cancel", behavior)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "behavior must be submit or cancel"));
            return;
        }
        char[] password = null;
        try {
            if (Strings.CS.equals("submit", behavior)) {
                JsonNode node = request.path("password");
                if (!node.isTextual()) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "password is required to submit"));
                    return;
                }
                String raw = node.asText();
                if (raw.isEmpty() || raw.length() > MAX_PASSWORD_CHARS) {
                    // An empty password is never a valid answer — the TUI
                    // dialog ignores Enter in the same case — and the cap
                    // matches the local prompt's buffer.
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "password must be between 1 and " + MAX_PASSWORD_CHARS + " characters"));
                    return;
                }
                password = raw.toCharArray();
            }
            respond(exchange, requestId, sessionId, behavior, password);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private void respond(HttpExchange exchange, String requestId, String sessionId,
            String behavior, char[] password) throws IOException {
        boolean cancelled = Strings.CS.equals("cancel", behavior);
        Optional<CompletableFuture<SessionLinkFrame>> forwarded = forward(
            requestId, sessionId, behavior, password);
        if (forwarded.isPresent()) {
            respondForwarded(exchange, forwarded.get());
            return;
        }
        SudoPasswordInteraction.Result result = cancelled
            ? SudoPasswordInteraction.Result.cancelled()
            : SudoPasswordInteraction.Result.provided(password);
        boolean accepted = interactions.respond(InteractionFeatures.SUDO_PASSWORD,
            requestId, sessionId, result, InteractionEndpoint.REMOTE);
        if (!accepted && result instanceof SudoPasswordInteraction.Result.Provided provided) {
            // A refused result (already answered elsewhere) is ours to wipe;
            // an accepted one is written by the sudo run, which wipes it.
            provided.close();
        }
        if (!accepted) {
            respondAlreadyAnswered(exchange);
            return;
        }
        respondResponded(exchange);
    }

    /**
     * Hands the answer to the process that owns the session, or empty when
     * this gateway owns it and the local coordinator should take it.
     *
     * <p>The password goes on the wire only here, over the worker's Unix
     * socket — never over TCP.
     */
    private Optional<CompletableFuture<SessionLinkFrame>> forward(
            String requestId, String sessionId, String behavior, char[] password) {
        if (remoteWorkers.isEmpty()) return Optional.empty();
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("request_id", requestId);
        payload.put("interaction_kind", "sudo_password");
        payload.put("behavior", behavior);
        if (password != null) payload.put("password", new String(password));
        return remoteWorkers.get().respondInteraction(sessionId, payload);
    }

    private static void respondForwarded(
            HttpExchange exchange, CompletableFuture<SessionLinkFrame> reply) throws IOException {
        try {
            reply.get(REMOTE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            respondResponded(exchange);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SessionLinkClient.RemoteException remote
                    && Strings.CS.equals(remote.code(), "stale_interaction")) {
                respondAlreadyAnswered(exchange);
                return;
            }
            respondJson(exchange, 502, errorBody("remote_rejected",
                StringUtils.defaultIfBlank(failure.getCause() == null
                    ? null : failure.getCause().getMessage(),
                    "remote session rejected the answer")));
        } catch (TimeoutException _) {
            respondJson(exchange, 502, errorBody("remote_timeout",
                "remote session did not answer in time"));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 502, errorBody("remote_timeout",
                "interrupted while waiting for the remote session"));
        }
    }

    private static void respondResponded(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("responded", true);
        respondJson(exchange, 200, response);
    }

    /**
     * The ask is gone: answered by the terminal, or expired. Same outcome for
     * the client either way — its card is stale and the resolved frame has
     * already cleared it.
     */
    private static void respondAlreadyAnswered(HttpExchange exchange) throws IOException {
        respondJson(exchange, 409, errorBody("already_responded",
            "the ask is no longer pending; it was answered by another observer"));
    }
}
