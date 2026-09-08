package com.claudecode.gateway;

import com.claudecode.api.StreamEvent;
import com.claudecode.api.StreamEventSseCodec;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /v1/messages}: the Anthropic Messages protocol face for
 * submitting one turn to the active session and streaming that turn back.
 *
 * <p>The turn lifecycle (idempotency, in-flight rejection, turn-scoped
 * subscription, disconnect-never-cancels) lives in {@link ProtocolTurnRunner};
 * this class is only the Anthropic wire projection on top of the shared
 * {@link SdkMessageToStreamEvent} block-level translation.
 */
public final class MessagesHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    public MessagesHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

    /** Handles one request exchange; owns the SSE lane it opens. */
    public void handle(HttpExchange exchange) throws IOException {
        runner.handle(exchange.getRequestBody(), new HttpProtocolResponder(exchange), this);
    }

    @Override
    public String extractPrompt(JsonNode request) {
        return extractLastUserText(request);
    }

    @Override
    public String extractIdempotencyKey(JsonNode request) {
        JsonNode userId = request.path("metadata").path("user_id");
        if (userId.isTextual() && !StringUtils.isBlank(userId.asText())) return userId.asText();
        return null;
    }

    @Override
    public ProtocolTurnRunner.PerTurnProjection newTurn(JsonNode request) {
        String model = request.path("model").asText("");
        return new AnthropicProjection(model);
    }

    @Override
    public String errorBody(int status, String reason) {
        return AnthropicErrors.body(status, reason);
    }

    /** The Anthropic per-turn projection over the shared block translation. */
    private static final class AnthropicProjection
            implements ProtocolTurnRunner.PerTurnProjection {

        private final SdkMessageToStreamEvent translator;
        private final String model;

        private AnthropicProjection(String model) {
            this.model = model;
            this.translator = new SdkMessageToStreamEvent();
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> openingFrames() {
            return encoded(translator.messageStart(model, null));
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> messageFrames(SDKMessage message) {
            List<ProtocolTurnRunner.SseFrame> frames = new ArrayList<>();
            for (StreamEvent event : translator.translate(message)) {
                frames.addAll(encoded(event));
            }
            return frames;
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> closingFrames(boolean userCancel) {
            List<ProtocolTurnRunner.SseFrame> frames = new ArrayList<>();
            for (StreamEvent event : translator.turnComplete(
                    userCancel ? "cancelled" : "end_turn", null)) {
                frames.addAll(encoded(event));
            }
            return frames;
        }

        private static List<ProtocolTurnRunner.SseFrame> encoded(StreamEvent event) {
            StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(event);
            return encoded == null ? List.of()
                : List.of(new ProtocolTurnRunner.SseFrame(encoded.type(), encoded.data()));
        }
    }

    /** Extracts the last user message's concatenated text content. */
    static String extractLastUserText(JsonNode request) {
        JsonNode messages = request.path("messages");
        if (!messages.isArray()) return null;
        JsonNode lastUser = null;
        for (JsonNode message : messages) {
            if (Strings.CS.equals("user", message.path("role").asText())) lastUser = message;
        }
        if (lastUser == null) return null;
        JsonNode content = lastUser.path("content");
        StringBuilder text = new StringBuilder();
        if (content.isTextual()) {
            text.append(content.asText());
        } else if (content.isArray()) {
            for (JsonNode block : (ArrayNode) content) {
                if (Strings.CS.equals("text", block.path("type").asText())
                        && block.path("text").isTextual()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** Anthropic-shaped JSON error body. */
    static final class AnthropicErrors {
        private AnthropicErrors() {}

        static String body(int status, String message) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            ObjectNode error = body.putObject("error");
            error.put("type", errorType(status));
            error.put("message", message);
            return body.toString();
        }

        private static String errorType(int status) {
            return switch (status) {
                case 400 -> "invalid_request_error";
                case 429 -> "overloaded_error";
                default -> "api_error";
            };
        }
    }

    /** Bridges the runner onto one HTTP exchange with Anthropic error bodies. */
    private record HttpProtocolResponder(HttpExchange exchange)
            implements ProtocolTurnRunner.ProtocolResponder {

        @Override
        public SseConnection beginSse() throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            // Length 0 asks for a chunked response — the stream flushes per frame.
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            // Closing the lane closes the stream; the turn is unaffected.
            return SseConnection.start(output, 4_096, Duration.ofSeconds(20), () -> {});
        }

        @Override
        public void respondJsonError(int status, String message) throws IOException {
            byte[] bytes = AnthropicErrors.body(status, message)
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
