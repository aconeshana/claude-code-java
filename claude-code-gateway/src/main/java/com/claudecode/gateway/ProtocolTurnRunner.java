package com.claudecode.gateway;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionHostSubmissionLedger;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;

/**
 * Shared turn orchestration for every Messages-protocol-shaped endpoint
 * (Anthropic Messages, OpenAI Chat Completions, OpenAI Responses).
 *
 * <p>All three protocol faces differ only in their wire projection; the turn
 * lifecycle is identical and lives here:
 * <ul>
 *   <li>the client's message history is discarded — only the extracted prompt
 *       reaches the session; the host transcript is the single source of truth</li>
 *   <li>the idempotency key goes through the submission ledger, so a retried
 *       POST never re-submits the turn</li>
 *   <li>a request carrying {@code metadata.session_id} routes to that headless
 *       session (bypassing the registry's single activation); requests without
 *       one keep submitting to the active session</li>
 *   <li>a turn already in flight on the same session returns the protocol's
 *       error shape — queueing stays a TUI concern because session events
 *       carry no turn id to attribute queued turns to</li>
 *   <li>disconnecting from the SSE stream never cancels the turn: the turn
 *       belongs to the session, and the mirror stream keeps following it</li>
 * </ul>
 */
final class ProtocolTurnRunner {

    /**
     * One protocol face. The projection factory pattern keeps per-turn
     * translation state: tool_use→tool_result block correlation spans several
     * messages of one turn, so each turn gets its own projection instance.
     */
    interface TurnProjection {

        /** A fresh per-turn stateful projection for one request. */
        PerTurnProjection newTurn(JsonNode request);

        /** The prompt extracted from the protocol request, or null when invalid. */
        String extractPrompt(JsonNode request);

        /**
         * Attachments extracted from the protocol request, or an empty list
         * when the request carries none. Split into images (inline base64
         * picture blocks) and files (persisted to disk by the session owner).
         */
        default SubmissionAttachments extractAttachments(JsonNode request) {
            return SubmissionAttachments.NONE;
        }

        /** The idempotency key from the request, or null for a fresh one. */
        default String extractIdempotencyKey(JsonNode request) { return null; }

        /** The headless session id from the request, or null for the active session. */
        default String extractSessionId(JsonNode request) {
            JsonNode sessionId = request.path("metadata").path("session_id");
            if (sessionId.isTextual() && !StringUtils.isBlank(sessionId.asText())) {
                return sessionId.asText();
            }
            return null;
        }

        /** The protocol's JSON error body for one status class. */
        String errorBody(int status, String reason);
    }

    /** Per-turn wire projection; owns translation state for one turn. */
    interface PerTurnProjection {
        /** Frames to emit before the turn starts (the protocol's opening event). */
        List<SseFrame> openingFrames();

        /** Frames for one assistant/tool-result message inside this turn. */
        List<SseFrame> messageFrames(SDKMessage message);

        /** The protocol's terminal frames, including its usage/finish event. */
        List<SseFrame> closingFrames(boolean userCancel);
    }

    /** One pre-serialized SSE frame. */
    record SseFrame(String event, String data) {}

    /** Resolves the submit target for one turn: a headless id, or the activation. */
    interface SessionResolver {
        Optional<SessionHostSession> resolve(String sessionId);
    }

    private static final int TOO_MANY_REQUESTS = 429;

    private final SessionResolver sessions;
    private final SessionHostSubmissionLedger ledger = new SessionHostSubmissionLedger();
    private final InFlightGuard inFlight;

    ProtocolTurnRunner(SessionResolver sessions, InFlightGuard inFlight) {
        this.sessions = sessions;
        this.inFlight = inFlight;
    }

    /** Handles one protocol exchange end-to-end; owns the SSE lane it opens. */
    void handle(InputStream requestBody, ProtocolResponder responder,
            TurnProjection projection) throws IOException {
        String body;
        try (var input = requestBody) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            responder.respondJsonError(400, "body is not valid JSON");
            return;
        }
        String prompt = projection.extractPrompt(request);
        if (prompt == null) {
            responder.respondJsonError(400,
                "request must carry a user message with text content");
            return;
        }
        SubmissionAttachments attachments;
        try {
            attachments = projection.extractAttachments(request);
        } catch (IllegalArgumentException failure) {
            responder.respondJsonError(400, failure.getMessage());
            return;
        }
        String idempotencyKey = projection.extractIdempotencyKey(request);
        String messageId = idempotencyKey == null
            ? "gw-" + UUID.randomUUID() : idempotencyKey;

        String sessionId = projection.extractSessionId(request);
        SessionHostSession session = sessions.resolve(sessionId).orElse(null);
        if (session == null) {
            responder.respondJsonError(sessionId == null ? 409 : 404,
                sessionId == null
                    ? "no active session to submit to"
                    : "unknown session: " + sessionId);
            return;
        }
        String targetId = session.info().id();
        if (!inFlight.tryAcquire(targetId)) {
            responder.respondJsonError(TOO_MANY_REQUESTS,
                "a turn is already in flight on this session; retry after it completes");
            return;
        }

        SseConnection connection = responder.beginSse();
        PerTurnProjection perTurn = projection.newTurn(request);
        TurnScopeSink turnScope = new TurnScopeSink(perTurn, connection, targetId);
        // Subscribe before submitting: onTurnStart fires synchronously on the
        // submitting thread, so the subscription must already be in place.
        turnScope.attach(session.events(), connection);
        try {
            for (SseFrame frame : perTurn.openingFrames()) {
                connection.offer(SseFrameWriter.event(frame.event(), null, frame.data()));
            }
            submitPrompt(session, prompt, attachments, messageId, turnScope);
        } catch (RuntimeException failure) {
            inFlight.release(targetId);
            turnScope.fail(StringUtils.defaultIfBlank(failure.getMessage(),
                "turn submission failed"));
        }
    }

    private void submitPrompt(SessionHostSession session, String prompt,
            SubmissionAttachments attachments, String messageId, TurnScopeSink turnScope) {
        String sessionId = session.info().id();
        SessionHostSubmission submission = new SessionHostSubmission(
            prompt, messageId, attachments.images(), attachments.files());
        ledger.submit(sessionId, messageId,
            () -> session.submit(submission))
            .whenComplete((_, failure) -> {
                if (failure != null) {
                    // The submission never reached the engine; release the
                    // in-flight slot and end the stream. Successful turns do
                    // both at turn end.
                    inFlight.release(sessionId);
                    turnScope.fail(StringUtils.defaultIfBlank(failure.getMessage(),
                        "turn submission failed"));
                }
            });
    }

    private static ObjectNode errorJson(String message) {
        var body = JsonUtils.getMapper().createObjectNode();
        var error = body.putObject("error");
        error.put("message", message);
        return body;
    }

    /** Opens the SSE response and returns the connection lane owning it. */
    interface ProtocolResponder {
        SseConnection beginSse() throws IOException;

        void respondJsonError(int status, String message) throws IOException;
    }

    /**
     * Turn-scoped projection feeding the request's SSE lane.
     *
     * <p>Subscribes to the session hub without replay (the recorded prefix
     * belongs to earlier turns), projects this turn's events through the
     * protocol projection, and on turn completion releases the session's
     * in-flight slot and finishes the lane: the closing frames are flushed and
     * the response body ends, so the client's HTTP connection is freed rather
     * than held open by heartbeats (browsers allow six per host). Events
     * before the first
     * {@code onTurnStart} are ignored so a turn that was already draining when
     * the request arrived cannot leak into this stream.
     */
    private final class TurnScopeSink {

        private final PerTurnProjection projection;
        private final SseConnection connection;
        private final String sessionId;
        private volatile AutoCloseable subscription;
        private volatile boolean turnObserved;
        private final AtomicBoolean ended = new AtomicBoolean();

        TurnScopeSink(PerTurnProjection projection, SseConnection connection, String sessionId) {
            this.projection = projection;
            this.connection = connection;
            this.sessionId = sessionId;
        }

        void attach(SessionEventHub hub, SseConnection lane) {
            // replay=false: the hub's recorded prefix belongs to previous
            // turns and must not seed this request's stream.
            subscription = hub.subscribe(new SessionSink() {
                @Override public void onTurnStart(UserInput input) {
                    turnObserved = true;
                }
                @Override public void onMessage(SDKMessage msg) {
                    if (turnObserved) {
                        for (SseFrame frame : projection.messageFrames(msg)) {
                            connection.offer(
                                SseFrameWriter.event(frame.event(), null, frame.data()));
                        }
                    }
                }
                @Override public void onError(Throwable error, boolean userCancel) {
                    if (!turnObserved) return;
                    for (SseFrame frame : projection.closingFrames(userCancel)) {
                        connection.offer(
                            SseFrameWriter.event(frame.event(), null, frame.data()));
                    }
                    finishTurn();
                }
                @Override public void onTurnComplete(TurnOutcome outcome) {
                    if (!turnObserved) return;
                    for (SseFrame frame : projection.closingFrames(outcome.userCancel())) {
                        connection.offer(
                            SseFrameWriter.event(frame.event(), null, frame.data()));
                    }
                    finishTurn();
                }
                @Override public void onIdle() { /* not a turn boundary */ }
            }, false);
        }

        private void finishTurn() {
            if (!ended.compareAndSet(false, true)) return;
            inFlight.release(sessionId);
            close();
            connection.finish();
        }

        /**
         * Ends the stream with the protocol-neutral error frame when the turn
         * never started. The caller owns the in-flight slot release; a turn
         * that already ended through the hub keeps its own closing frames.
         */
        void fail(String message) {
            if (!ended.compareAndSet(false, true)) return;
            close();
            connection.offer(SseFrameWriter.event("error", null,
                errorJson(message).toString()));
            connection.finish();
        }

        private void close() {
            AutoCloseable current = subscription;
            subscription = null;
            if (current != null) {
                try { current.close(); } catch (Exception _) {}
            }
        }
    }
}
