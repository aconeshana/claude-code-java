package com.claudecode.gateway;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.ProtocolWireCodec;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * {@code POST /v1/chat/completions}: the OpenAI Chat Completions protocol
 * face for submitting one turn to the active session and streaming or returning
 * that turn in strict OpenAI wire format.
 */
final class ChatHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    ChatHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

    public void handle(HttpExchange exchange) throws IOException {
        runner.handle(exchange.getRequestBody(), new HttpProtocolResponder(exchange), this);
    }

    @Override
    public String extractPrompt(JsonNode request) {
        return MessagesHandler.extractLastUserText(request);
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
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (Strings.CS.equals("image_url", type)) {
                String url = part.path("image_url").isTextual()
                    ? part.path("image_url").asText()
                    : part.path("image_url").path("url").asText("");
                images.add(MessagesHandler.parseDataUrlAttachment(url, null));
            } else if (Strings.CS.equals("file", type)) {
                JsonNode file = part.path("file");
                String fileName = file.path("filename").isTextual()
                    ? file.path("filename").asText() : null;
                String fileData = file.path("file_data").asText("");
                if (Strings.CS.startsWith(fileData, "data:")) {
                    files.add(MessagesHandler.parseDataUrlAttachment(fileData, fileName));
                } else if (StringUtils.isNotEmpty(fileData)) {
                    files.add(new SessionHostSubmission.Attachment(
                        "application/pdf", fileName, MessagesHandler.decodeBase64Strict(fileData)));
                }
            }
        }
        if (images.isEmpty() && files.isEmpty()) return SubmissionAttachments.NONE;
        return new SubmissionAttachments(images, files);
    }

    @Override
    public ProtocolTurnRunner.PerTurnProjection newTurn(JsonNode request) {
        String model = request.path("model").asText("");
        return new ChatProjection(model);
    }

    @Override
    public String errorBody(int status, String reason) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", reason);
        error.put("type", status == 400 ? "invalid_request_error" : "server_error");
        return body.toString();
    }

    private static final class ChatProjection implements ProtocolTurnRunner.PerTurnProjection {

        private final String model;
        private final String responseId;
        private final long created;
        private final List<ContentBlock> accumulatedBlocks = new ArrayList<>();
        private boolean anyToolCall;
        private int anyToolCallIndex;

        private ChatProjection(String model) {
            this.model = model;
            this.responseId = "chatcmpl-gw-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);
            this.created = System.currentTimeMillis() / 1_000;
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> openingFrames() {
            ObjectNode delta = JsonUtils.getMapper().createObjectNode();
            delta.put("role", "assistant");
            delta.put("content", "");
            return List.of(chunk(delta, null));
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> messageFrames(SDKMessage message) {
            List<ProtocolTurnRunner.SseFrame> frames = new ArrayList<>();
            switch (message) {
                case SDKMessage.Assistant assistant -> assistantFrames(assistant, frames);
                case SDKMessage.User user -> toolResultFrames(user, frames);
                default -> { }
            }
            return frames;
        }

        private void assistantFrames(
                SDKMessage.Assistant assistant, List<ProtocolTurnRunner.SseFrame> frames) {
            AssistantMessage body = assistant.message();
            if (body == null || body.message() == null) return;
            List<ContentBlock> blocks = body.message().content();
            if (blocks == null) return;
            for (ContentBlock block : blocks) {
                accumulatedBlocks.add(block);
                switch (block) {
                    case ThinkingBlock thinking -> {
                        ObjectNode delta = deltaObject();
                        delta.put("reasoning_content", thinking.thinking());
                        frames.add(chunk(delta, null));
                    }
                    case TextBlock text -> {
                        ObjectNode delta = deltaObject();
                        delta.put("content", text.text());
                        frames.add(chunk(delta, null));
                    }
                    case ToolUseBlock tool -> {
                        anyToolCall = true;
                        ObjectNode delta = deltaObject();
                        ArrayNode toolCalls = delta.putArray("tool_calls");
                        ObjectNode call = toolCalls.addObject();
                        call.put("index", anyToolCallIndex++);
                        call.put("id", tool.id());
                        call.put("type", "function");
                        ObjectNode function = call.putObject("function");
                        function.put("name", tool.name());
                        function.put("arguments",
                            tool.input() == null ? "{}" : tool.input().toString());
                        frames.add(chunk(delta, null));
                    }
                    default -> { }
                }
            }
        }

        private void toolResultFrames(
                SDKMessage.User user, List<ProtocolTurnRunner.SseFrame> frames) {
            UserMessage body = user.message();
            if (body == null || body.message() == null) return;
            List<ContentBlock> blocks = body.message().blocks();
            if (blocks == null) return;
            for (ContentBlock block : blocks) {
                if (!(block instanceof ToolResultBlock result)) continue;
                if (!result.isError()) continue;
                String errText = "Tool " + result.toolUseId() + " failed";
                accumulatedBlocks.add(new TextBlock(errText));
                ObjectNode delta = deltaObject();
                delta.put("content", errText);
                frames.add(chunk(delta, null));
            }
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> closingFrames(boolean userCancel) {
            ObjectNode delta = deltaObject();
            delta.putNull("content");
            String finishReason = userCancel ? "cancelled"
                : anyToolCall ? "tool_calls" : "stop";
            return List.of(
                chunk(delta, finishReason),
                new ProtocolTurnRunner.SseFrame(null, "[DONE]"));
        }

        @Override
        public String unaryResponse(boolean userCancel) {
            String stop = userCancel ? "cancelled" : (anyToolCall ? "tool_use" : "end_turn");
            ApiMessage msg = new ApiMessage(
                responseId, "message", "assistant", List.copyOf(accumulatedBlocks),
                model, stop, null, Usage.EMPTY);
            return ProtocolWireCodec.encodeResponse(ModelApiProtocol.OPENAI_CHAT, msg);
        }

        private ObjectNode deltaObject() {
            return JsonUtils.getMapper().createObjectNode();
        }

        private ProtocolTurnRunner.SseFrame chunk(ObjectNode delta, String finishReason) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("id", responseId);
            body.put("object", "chat.completion.chunk");
            body.put("created", created);
            if (!model.isEmpty()) body.put("model", model);
            ArrayNode choices = body.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason != null) choice.put("finish_reason", finishReason);
            else choice.putNull("finish_reason");
            return new ProtocolTurnRunner.SseFrame("chat.completion.chunk", body.toString());
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
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            ObjectNode error = body.putObject("error");
            error.put("message", message);
            error.put("type", status == 400 ? "invalid_request_error" : "server_error");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
