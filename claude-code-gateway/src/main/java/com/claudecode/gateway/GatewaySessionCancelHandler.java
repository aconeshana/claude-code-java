package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
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
 * {@code POST /api/session/cancel}: stops the active turn on one session —
 * the remote counterpart of the TUI's Ctrl+C, driving the webui composer's
 * stop button (the send control's stop form).
 *
 * <p>The addressed session arrives as a {@code session_id} body field; a
 * blank id keeps the legacy target — the active TUI session. The stop rides
 * {@link GatewaySessionCancelPort}, which the CLI composition root implements
 * against the same {@code QuerySession.interrupt()} the TUI Ctrl+C and the SDK
 * interrupt control request drive, so the transcript records the same
 * user-cancel semantics wherever the stop came from.
 */
final class GatewaySessionCancelHandler {

    private static final Duration REMOTE_TIMEOUT = Duration.ofSeconds(10);

    private final GatewaySessionCancelPort cancel;
    private final RemoteWorkerPool remoteWorkers;

    GatewaySessionCancelHandler(GatewaySessionCancelPort cancel, RemoteWorkerPool remoteWorkers) {
        this.cancel = cancel;
        this.remoteWorkers = remoteWorkers;
    }

    /** Handles one {@code POST} exchange: stop the addressed session's turn. */
    void handlePost(HttpExchange exchange) throws IOException {
        JsonNode request = readJson(exchange);
        if (request == null) return;
        String sessionId = StringUtils.trimToNull(text(request, "session_id"));
        boolean reached;
        try {
            reached = cancel.cancel(sessionId);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to cancel session: " + StringUtils.defaultIfBlank(
                    failure.getMessage(), failure.toString())));
            return;
        }
        if (!reached && sessionId != null && remoteWorkers != null) {
            Optional<CompletableFuture<SessionLinkFrame>> forwarded = remoteWorkers.cancelTurn(sessionId);
            if (forwarded.isPresent()) {
                respondForwarded(exchange, sessionId, forwarded.get());
                return;
            }
        }
        if (!reached) {
            respondJson(exchange, 404, errorBody("not_found",
                sessionId == null
                    ? "no active session to cancel"
                    : "unknown session: " + sessionId));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        if (sessionId != null) result.put("session_id", sessionId);
        // The request is accepted, not completed: cancellation unwinds the
        // turn asynchronously and the mirror stream reports the outcome.
        result.put("cancelling", true);
        respondJson(exchange, 200, result);
    }

    private static void respondForwarded(HttpExchange exchange, String sessionId,
            CompletableFuture<SessionLinkFrame> reply) throws IOException {
        try {
            reply.get(REMOTE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SessionLinkClient.RemoteException remote
                    && Strings.CS.equals(remote.code(), "invalid_request")) {
                respondJson(exchange, 404, errorBody("not_found", "unknown session: " + sessionId));
                return;
            }
            respondJson(exchange, 502, errorBody("remote_rejected",
                StringUtils.defaultIfBlank(failure.getCause() == null
                    ? null : failure.getCause().getMessage(), "remote session rejected the cancel")));
            return;
        } catch (TimeoutException _) {
            respondJson(exchange, 502, errorBody("remote_timeout",
                "remote session did not acknowledge the cancel in time"));
            return;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 502, errorBody("remote_timeout",
                "interrupted while waiting for the remote session"));
            return;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("session_id", sessionId);
        result.put("cancelling", true);
        respondJson(exchange, 200, result);
    }
}
