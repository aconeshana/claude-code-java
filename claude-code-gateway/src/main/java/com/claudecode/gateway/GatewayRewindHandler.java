package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostRewindController;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;

/**
 * Handles {@code GET /api/sessions/{id}/rewind/preview} and
 * {@code POST /api/sessions/{id}/rewind} uniformly across both live TUI workers
 * (via SessionLink RPC) and Web headless sessions.
 */
final class GatewayRewindHandler {

    private static final long REMOTE_TIMEOUT_SECONDS = 15L;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final GatewayHeadlessSessions headless;
    private final RemoteWorkerPool remoteWorkers;
    private final Predicate<String> isTurnInFlight;

    GatewayRewindHandler(
            GatewayHeadlessSessions headless,
            RemoteWorkerPool remoteWorkers,
            Predicate<String> isTurnInFlight) {
        this.headless = headless;
        this.remoteWorkers = remoteWorkers;
        this.isTurnInFlight = isTurnInFlight != null ? isTurnInFlight : _ -> false;
    }

    void handlePreview(HttpExchange exchange, String sessionId, String messageId) throws IOException {
        try (exchange) {
            if (StringUtils.isBlank(messageId)) {
                respondJson(exchange, 400, errorBody("invalid_request", "messageId query parameter is required"));
                return;
            }
            Optional<CompletableFuture<SessionLinkFrame>> remote = remoteWorkers != null
                ? remoteWorkers.previewRewind(sessionId, messageId.trim())
                : Optional.empty();
            if (remote.isPresent()) {
                respondRemoteFrame(exchange, remote.get());
                return;
            }
            SessionHostSession session = resolveHeadlessSession(sessionId);
            if (session == null) {
                respondJson(exchange, 404, errorBody("not_found", "session not found: " + sessionId));
                return;
            }
            try {
                SessionHostRewindController.Preview preview = session.rewinds().preview(messageId.trim());
                respondJson(exchange, 200, previewJson(preview));
            } catch (IllegalArgumentException failure) {
                respondJson(exchange, 400, errorBody("invalid_request",
                    StringUtils.defaultIfBlank(failure.getMessage(), "invalid rewind preview request")));
            } catch (UnsupportedOperationException failure) {
                respondJson(exchange, 400, errorBody("unsupported",
                    StringUtils.defaultIfBlank(failure.getMessage(), "rewind is not supported")));
            } catch (RuntimeException failure) {
                respondJson(exchange, 500, errorBody("rewind_failed",
                    StringUtils.defaultIfBlank(failure.getMessage(), "failed to preview rewind")));
            }
        }
    }

    void handleExecute(HttpExchange exchange, String sessionId) throws IOException {
        try (exchange) {
            if (isTurnInFlight.test(sessionId)) {
                respondJson(exchange, 409, errorBody("turn_in_flight",
                    "turn is currently running; stop it before rewinding"));
                return;
            }
            JsonNode body = readBoundedRequestJson(exchange);
            if (body == null) return;
            String messageId = text(body, "message_id");
            String modeRaw = text(body, "mode");
            if (StringUtils.isBlank(messageId)) {
                respondJson(exchange, 400, errorBody("invalid_request", "message_id is required"));
                return;
            }
            SessionHostRewindController.RewindMode mode;
            try {
                mode = SessionHostRewindController.RewindMode.parse(modeRaw);
            } catch (IllegalArgumentException failure) {
                respondJson(exchange, 400, errorBody("invalid_request", failure.getMessage()));
                return;
            }

            Optional<CompletableFuture<SessionLinkFrame>> remote = remoteWorkers != null
                ? remoteWorkers.executeRewind(sessionId, messageId.trim(), mode.wire())
                : Optional.empty();
            if (remote.isPresent()) {
                respondRemoteFrame(exchange, remote.get());
                return;
            }
            SessionHostSession session = resolveHeadlessSession(sessionId);
            if (session == null) {
                respondJson(exchange, 404, errorBody("not_found", "session not found: " + sessionId));
                return;
            }
            try {
                SessionHostRewindController.Result result = session.rewinds().execute(messageId.trim(), mode);
                respondJson(exchange, 200, resultJson(result));
            } catch (IllegalArgumentException | IllegalStateException failure) {
                respondJson(exchange, 400, errorBody("invalid_request",
                    StringUtils.defaultIfBlank(failure.getMessage(), "invalid rewind request")));
            } catch (UnsupportedOperationException failure) {
                respondJson(exchange, 400, errorBody("unsupported",
                    StringUtils.defaultIfBlank(failure.getMessage(), "rewind is not supported")));
            } catch (RuntimeException failure) {
                respondJson(exchange, 500, errorBody("rewind_failed",
                    StringUtils.defaultIfBlank(failure.getMessage(), "rewind failed")));
            }
        }
    }

    private SessionHostSession resolveHeadlessSession(String sessionId) {
        if (headless == null || StringUtils.isBlank(sessionId)) return null;
        Optional<SessionHostSession> existing = headless.find(sessionId);
        if (existing.isPresent()) return existing.get();
        try {
            return headless.open(new GatewayHeadlessSessions.OpenRequest(sessionId, null)).session();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static void respondRemoteFrame(
            HttpExchange exchange, CompletableFuture<SessionLinkFrame> future) throws IOException {
        try {
            SessionLinkFrame frame = future.get(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (frame == null) {
                respondJson(exchange, 502, errorBody("remote_error", "empty response from TUI worker"));
                return;
            }
            if (frame.kind() == SessionLinkFrame.Kind.ERROR) {
                String msg = frame.payload() != null && frame.payload().has("message")
                    ? frame.payload().get("message").asText()
                    : "remote rewind failed";
                respondJson(exchange, 400, errorBody("remote_rewind_error", msg));
                return;
            }
            JsonNode payload = frame.payload() != null
                ? frame.payload() : JsonUtils.getMapper().createObjectNode();
            respondJson(exchange, 200, payload);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            respondJson(exchange, 504, errorBody("remote_timeout", "interrupted waiting for TUI worker"));
        } catch (TimeoutException _) {
            respondJson(exchange, 504, errorBody("remote_timeout", "timed out waiting for TUI worker"));
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            respondJson(exchange, 502, errorBody("remote_error",
                StringUtils.defaultIfBlank(cause.getMessage(), "remote TUI worker failed")));
        }
    }

    private static ObjectNode previewJson(SessionHostRewindController.Preview preview) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("can_rewind_conversation", preview.canRewindConversation());
        out.put("can_rewind_code", preview.canRewindCode());
        if (preview.codeUnavailableReason() != null) {
            out.put("code_unavailable_reason", preview.codeUnavailableReason());
        } else {
            out.putNull("code_unavailable_reason");
        }
        out.put("turns_to_drop", preview.turnsToDrop());
        out.put("messages_to_drop", preview.messagesToDrop());
        out.put("insertions", preview.insertions());
        out.put("deletions", preview.deletions());
        ArrayNode files = out.putArray("files");
        for (SessionHostRewindController.FileDiff file : preview.files()) {
            ObjectNode item = files.addObject();
            item.put("path", file.path());
            item.put("insertions", file.insertions());
            item.put("deletions", file.deletions());
            item.put("will_delete", file.willDelete());
            item.put("unified_diff", file.unifiedDiff());
        }
        return out;
    }

    private static ObjectNode resultJson(SessionHostRewindController.Result result) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("rewound_conversation", result.rewoundConversation());
        out.put("rewound_code", result.rewoundCode());
        out.put("retained_message_count", result.retainedMessageCount());
        ArrayNode restored = out.putArray("restored_files");
        for (String path : result.restoredFiles()) {
            restored.add(path);
        }
        return out;
    }

    private static JsonNode readBoundedRequestJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] raw = in.readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) {
                respondJson(exchange, 413, errorBody("payload_too_large", "request body too large"));
                return null;
            }
            if (raw.length == 0) return JsonUtils.getMapper().createObjectNode();
            return JsonUtils.getMapper().readTree(raw);
        } catch (IOException _) {
            respondJson(exchange, 400, errorBody("invalid_json", "request body is not valid JSON"));
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private static ObjectNode errorBody(String code, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode err = body.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return body;
    }

    private static void respondJson(HttpExchange exchange, int status, JsonNode payload) throws IOException {
        byte[] bytes = JsonUtils.getMapper().writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
