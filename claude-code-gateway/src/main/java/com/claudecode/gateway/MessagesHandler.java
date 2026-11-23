package com.claudecode.gateway;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.ProtocolWireCodec;
import com.claudecode.api.StreamEvent;
import com.claudecode.api.StreamEventSseCodec;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code POST /v1/messages}: the Anthropic Messages protocol face for
 * submitting one turn to the active session and streaming or returning that turn.
 */
final class MessagesHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    MessagesHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

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
    public SubmissionAttachments extractAttachments(JsonNode request) {
        JsonNode messages = request.path("messages");
        if (!messages.isArray()) return SubmissionAttachments.NONE;
        JsonNode lastUser = null;
        for (JsonNode message : messages) {
            if (Strings.CS.equals("user", message.path("role").asText())) lastUser = message;
        }
        JsonNode content = lastUser == null ? null : lastUser.path("content");
        if (content == null || !content.isArray()) return SubmissionAttachments.NONE;
        List<SessionHostSubmission.Attachment> images = new ArrayList<>();
        List<SessionHostSubmission.Attachment> files = new ArrayList<>();
        for (JsonNode block : content) {
            String type = block.path("type").asText();
            JsonNode source = block.path("source");
            if (Strings.CS.equals("image", type)) {
                images.add(base64Attachment(source, null));
            } else if (Strings.CS.equals("document", type)) {
                String name = block.path("title").isTextual()
                    ? block.path("title").asText() : null;
                files.add(base64Attachment(source, name));
            }
        }
        if (images.isEmpty() && files.isEmpty()) return SubmissionAttachments.NONE;
        return new SubmissionAttachments(images, files);
    }

    private static SessionHostSubmission.Attachment base64Attachment(
            JsonNode source, String fileName) {
        if (!Strings.CS.equals("base64", source.path("type").asText())) {
            throw new IllegalArgumentException(
                "attachment source must be a base64 block, got type: "
                    + source.path("type").asText(""));
        }
        String data = source.path("data").asText(null);
        if (StringUtils.isEmpty(data)) {
            throw new IllegalArgumentException("attachment source has no data");
        }
        byte[] decoded = decodeBase64Strict(data);
        String mediaType = source.path("media_type").asText(null);
        return new SessionHostSubmission.Attachment(mediaType, fileName, decoded);
    }

    static SessionHostSubmission.Attachment parseDataUrlAttachment(String dataUrl, String fileName) {
        if (StringUtils.isBlank(dataUrl) || !Strings.CS.startsWith(dataUrl, "data:")) {
            throw new IllegalArgumentException(
                "attachment URL must be a base64 data: URL");
        }
        int semi = dataUrl.indexOf(";base64,");
        if (semi < 0) {
            throw new IllegalArgumentException("attachment data URL must be base64-encoded");
        }
        String mediaType = dataUrl.substring("data:".length(), semi);
        String data = dataUrl.substring(semi + ";base64,".length());
        if (StringUtils.isEmpty(data)) {
            throw new IllegalArgumentException("attachment data URL has no data");
        }
        return new SessionHostSubmission.Attachment(mediaType, fileName, decodeBase64Strict(data));
    }

    static byte[] decodeBase64Strict(String data) {
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("attachment data is not valid base64", failure);
        }
    }

    @Override
    public String errorBody(int status, String reason) {
        return AnthropicErrors.body(status, reason);
    }

    private static final class AnthropicProjection
            implements ProtocolTurnRunner.PerTurnProjection {

        private final SdkMessageToStreamEvent translator;
        private final String model;
        private final List<ContentBlock> accumulatedBlocks = new ArrayList<>();
        private boolean sawToolUse;

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
            if (message instanceof SDKMessage.Assistant assistant
                    && assistant.message() != null
                    && assistant.message().message() != null
                    && assistant.message().message().content() != null) {
                for (ContentBlock b : assistant.message().message().content()) {
                    accumulatedBlocks.add(b);
                    if (b instanceof ToolUseBlock) sawToolUse = true;
                }
            }
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

        @Override
        public String unaryResponse(boolean userCancel) {
            String stop = userCancel ? "cancelled" : (sawToolUse ? "tool_use" : "end_turn");
            ApiMessage msg = new ApiMessage(
                "msg-gw-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                "message",
                "assistant",
                List.copyOf(accumulatedBlocks),
                model,
                stop,
                null,
                Usage.EMPTY);
            return ProtocolWireCodec.encodeResponse(ModelApiProtocol.ANTHROPIC, msg);
        }

        private static List<ProtocolTurnRunner.SseFrame> encoded(StreamEvent event) {
            StreamEventSseCodec.EncodedEvent encoded = StreamEventSseCodec.encode(event);
            return encoded == null ? List.of()
                : List.of(new ProtocolTurnRunner.SseFrame(encoded.type(), encoded.data()));
        }
    }

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
            for (JsonNode block : content) {
                if (Strings.CS.equals("text", block.path("type").asText())
                        && block.path("text").isTextual()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(block.path("text").asText());
                }
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    static final class AnthropicErrors {
        private AnthropicErrors() {}

        static String body(int status, String message) {
            return GatewayResponses.errorBody(errorType(status), message).toString();
        }

        private static String errorType(int status) {
            return switch (status) {
                case 400 -> "invalid_request_error";
                case 429 -> "overloaded_error";
                default -> "api_error";
            };
        }
    }

    private record HttpProtocolResponder(HttpExchange exchange)
            implements ProtocolTurnRunner.ProtocolResponder {

        @Override
        public SseConnection beginSse() throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            return SseConnection.start(output, 4_096, Duration.ofSeconds(20), () -> {});
        }

        @Override
        public void respondUnaryJson(String jsonBody) throws IOException {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void respondAccepted(String sessionId, String messageId) throws IOException {
            byte[] bytes = ProtocolTurnRunner.ProtocolResponder.acceptedBody(sessionId, messageId)
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
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
