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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared turn orchestration for every Messages-protocol-shaped endpoint
 * (Anthropic Messages, OpenAI Chat Completions, OpenAI Responses).
 */
final class ProtocolTurnRunner {

    interface TurnProjection {

        PerTurnProjection newTurn(JsonNode request);

        String extractPrompt(JsonNode request);

        default SubmissionAttachments extractAttachments(JsonNode request) {
            return SubmissionAttachments.NONE;
        }

        default String extractIdempotencyKey(JsonNode request) { return null; }

        default String extractSessionId(JsonNode request) {
            JsonNode sessionId = request.path("metadata").path("session_id");
            if (sessionId.isTextual() && !StringUtils.isBlank(sessionId.asText())) {
                return sessionId.asText();
            }
            return null;
        }

        String errorBody(int status, String reason);
    }

    /** Per-turn wire projection; owns translation state for one turn. */
    interface PerTurnProjection {
        List<SseFrame> openingFrames();

        List<SseFrame> messageFrames(SDKMessage message);

        List<SseFrame> closingFrames(boolean userCancel);

        /** Renders the accumulated turn as a single unary {@code application/json} response body. */
        String unaryResponse(boolean userCancel);
    }

    record SseFrame(String event, String data) {}

    interface SessionResolver {
        Optional<SessionHostSession> resolve(String sessionId);

        default Optional<CompletableFuture<Void>> submitRemote(
                String sessionId, SessionHostSubmission submission) {
            return Optional.empty();
        }
    }

    private static final int TOO_MANY_REQUESTS = 429;
    private static final Duration REMOTE_ACCEPT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration UNARY_TURN_TIMEOUT = Duration.ofMinutes(5);

    private final SessionResolver sessions;
    private final SessionHostSubmissionLedger ledger = new SessionHostSubmissionLedger();
    private static final Logger log = LoggerFactory.getLogger(ProtocolTurnRunner.class);

    private final InFlightGuard inFlight;

    ProtocolTurnRunner(SessionResolver sessions, InFlightGuard inFlight) {
        this.sessions = sessions;
        this.inFlight = inFlight;
    }

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
        if (request == null || !request.isObject()) {
            responder.respondJsonError(400, "request body must be a JSON object");
            return;
        }
        SubmissionAttachments attachments;
        try {
            attachments = projection.extractAttachments(request);
        } catch (IllegalArgumentException failure) {
            responder.respondJsonError(400, failure.getMessage());
            return;
        }
        String prompt = projection.extractPrompt(request);
        if (prompt == null && !attachments.isEmpty()) prompt = "";
        if (prompt == null) {
            responder.respondJsonError(400,
                "request must carry a user message with text content");
            return;
        }
        String idempotencyKey = projection.extractIdempotencyKey(request);
        String messageId = idempotencyKey == null
            ? "gw-" + UUID.randomUUID() : idempotencyKey;

        String sessionId = projection.extractSessionId(request);
        SessionHostSession session = sessions.resolve(sessionId).orElse(null);
        log.debug("[gw-turn] resolve session={} found={} requestModel={} messageId={}",
            sessionId, session != null, request.path("model").asText(""), messageId);
        if (session == null && sessionId != null) {
            Optional<CompletableFuture<Void>> remote = sessions.submitRemote(sessionId,
                new SessionHostSubmission(prompt, messageId,
                    attachments.images(), attachments.files()));
            if (remote.isPresent()) {
                respondRemoteAccepted(remote.get(), sessionId, messageId, responder);
                return;
            }
        }
        if (session == null) {
            responder.respondJsonError(sessionId == null ? 409 : 404,
                sessionId == null
                    ? "no active session to submit to"
                    : "unknown session: " + sessionId);
            return;
        }
        String targetId = session.info().id();
        if (!inFlight.tryAcquire(targetId)) {
            log.debug("[gw-turn] rejected: in flight session={}", targetId);
            responder.respondJsonError(TOO_MANY_REQUESTS,
                "a turn is already in flight on this session; retry after it completes");
            return;
        }
        log.debug("[gw-turn] acquired session={} messageId={}", targetId, messageId);

        boolean streaming = !request.has("stream")
            || !request.get("stream").isBoolean()
            || request.get("stream").asBoolean();

        PerTurnProjection perTurn = projection.newTurn(request);
        if (!streaming) {
            handleUnary(session, targetId, prompt, attachments, messageId, perTurn, responder);
            return;
        }

        SseConnection connection = responder.beginSse();
        TurnScopeSink turnScope = new TurnScopeSink(perTurn, connection, targetId);
        turnScope.attach(session.events());
        try {
            for (SseFrame frame : perTurn.openingFrames()) {
                connection.offer(SseFrameWriter.event(frame.event(), null, frame.data()));
            }
            submitPrompt(session, prompt, attachments, messageId, turnScope::fail);
        } catch (RuntimeException failure) {
            inFlight.release(targetId);
            turnScope.fail(StringUtils.defaultIfBlank(failure.getMessage(),
                "turn submission failed"));
        }
    }

    private void handleUnary(
            SessionHostSession session,
            String targetId,
            String prompt,
            SubmissionAttachments attachments,
            String messageId,
            PerTurnProjection perTurn,
            ProtocolResponder responder) throws IOException {
        UnaryTurnScope unaryScope = new UnaryTurnScope(perTurn, targetId);
        unaryScope.attach(session.events());
        perTurn.openingFrames();
        try {
            submitPrompt(session, prompt, attachments, messageId, unaryScope::fail);
        } catch (RuntimeException failure) {
            inFlight.release(targetId);
            unaryScope.fail(StringUtils.defaultIfBlank(failure.getMessage(), "turn submission failed"));
        }
        try {
            String json = unaryScope.resultFuture().get(UNARY_TURN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            responder.respondUnaryJson(json);
        } catch (ExecutionException failure) {
            responder.respondJsonError(502, StringUtils.defaultIfBlank(
                failure.getCause() == null ? null : failure.getCause().getMessage(),
                "turn failed"));
        } catch (TimeoutException _) {
            unaryScope.fail("turn timed out");
            responder.respondJsonError(504, "turn timed out");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            unaryScope.fail("interrupted");
            responder.respondJsonError(502, "interrupted while waiting for turn");
        }
    }

    private static void respondRemoteAccepted(CompletableFuture<Void> accepted, String sessionId,
            String messageId, ProtocolResponder responder) throws IOException {
        try {
            accepted.get(REMOTE_ACCEPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failure) {
            responder.respondJsonError(502, StringUtils.defaultIfBlank(
                failure.getCause() == null ? null : failure.getCause().getMessage(),
                "remote session rejected the turn"));
            return;
        } catch (TimeoutException _) {
            responder.respondJsonError(502, "remote session did not accept the turn in time");
            return;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            responder.respondJsonError(502, "interrupted while waiting for the remote session");
            return;
        }
        responder.respondAccepted(sessionId, messageId);
    }

    private void submitPrompt(
            SessionHostSession session,
            String prompt,
            SubmissionAttachments attachments,
            String messageId,
            java.util.function.Consumer<String> onSubmitFailure) {
        String sessionId = session.info().id();
        SessionHostSubmission submission = new SessionHostSubmission(
            prompt, messageId, attachments.images(), attachments.files());
        log.debug("[gw-turn] submitting session={} messageId={}", sessionId, messageId);
        ledger.submit(sessionId, messageId,
            () -> session.submit(submission))
            .whenComplete((_, failure) -> {
                log.debug("[gw-turn] submit settled session={} failure={}", sessionId,
                    failure == null ? "none" : failure.toString());
                if (failure != null) {
                    inFlight.release(sessionId);
                    onSubmitFailure.accept(StringUtils.defaultIfBlank(failure.getMessage(),
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

    interface ProtocolResponder {
        SseConnection beginSse() throws IOException;

        void respondUnaryJson(String jsonBody) throws IOException;

        void respondJsonError(int status, String message) throws IOException;

        void respondAccepted(String sessionId, String messageId) throws IOException;

        static String acceptedBody(String sessionId, String messageId) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("accepted", true);
            body.put("session_id", sessionId);
            body.put("message_id", messageId);
            return body.toString();
        }
    }

    private final class UnaryTurnScope {
        private final PerTurnProjection projection;
        private final String sessionId;
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private volatile AutoCloseable subscription;
        private volatile boolean turnObserved;
        private final AtomicBoolean ended = new AtomicBoolean();

        UnaryTurnScope(PerTurnProjection projection, String sessionId) {
            this.projection = projection;
            this.sessionId = sessionId;
        }

        CompletableFuture<String> resultFuture() {
            return future;
        }

        void attach(SessionEventHub hub) {
            subscription = hub.subscribe(new SessionSink() {
                @Override public void onTurnStart(UserInput input) {
                    turnObserved = true;
                }
                @Override public void onMessage(SDKMessage msg) {
                    if (turnObserved) {
                        projection.messageFrames(msg);
                    }
                }
                @Override public void onError(Throwable error, boolean userCancel) {
                    if (!turnObserved) return;
                    complete(userCancel);
                }
                @Override public void onTurnComplete(TurnOutcome outcome) {
                    if (!turnObserved) return;
                    complete(outcome.userCancel());
                }
                @Override public void onIdle() { }
            }, false);
        }

        private void complete(boolean userCancel) {
            if (!ended.compareAndSet(false, true)) return;
            inFlight.release(sessionId);
            close();
            try {
                future.complete(projection.unaryResponse(userCancel));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }

        void fail(String message) {
            if (!ended.compareAndSet(false, true)) return;
            close();
            future.completeExceptionally(new IllegalStateException(message));
        }

        private void close() {
            AutoCloseable current = subscription;
            subscription = null;
            if (current != null) {
                try { current.close(); } catch (Exception _) { }
            }
        }
    }

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

        void attach(SessionEventHub hub) {
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
                @Override public void onIdle() { }
            }, false);
        }

        private void finishTurn() {
            if (!ended.compareAndSet(false, true)) return;
            inFlight.release(sessionId);
            close();
            connection.finish();
        }

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
                try { current.close(); } catch (Exception _) { }
            }
        }
    }
}
