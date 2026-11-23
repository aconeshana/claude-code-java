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
 * {@code POST /v1/responses}: the OpenAI Responses protocol face for
 * submitting one turn to the active session and streaming or returning that
 * turn as strict client-shaped {@code response.*} events.
 */
final class ResponsesHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    ResponsesHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

    public void handle(HttpExchange exchange) throws IOException {
        runner.handle(exchange.getRequestBody(), new HttpProtocolResponder(exchange), this);
    }

    @Override
    public String extractPrompt(JsonNode request) {
        return extractResponsesPrompt(request);
    }

    @Override
    public SubmissionAttachments extractAttachments(JsonNode request) {
        JsonNode input = request.path("input");
        if (!input.isArray()) return SubmissionAttachments.NONE;

        List<SessionHostSubmission.Attachment> images = new ArrayList<>();
        List<SessionHostSubmission.Attachment> files = new ArrayList<>();

        JsonNode lastUser = null;
        for (JsonNode item : input) {
            if (Strings.CS.equals("input_file", item.path("type").asText(""))) {
                extractResponsesFileAttachment(item, files);
            } else if (Strings.CS.equals("user", item.path("role").asText())) {
                lastUser = item;
            }
        }
        if (lastUser != null && lastUser.path("content").isArray()) {
            for (JsonNode block : lastUser.path("content")) {
                String type = block.path("type").asText("");
                if (Strings.CS.equals("input_image", type)) {
                    String url = block.path("image_url").isTextual()
                        ? block.path("image_url").asText()
                        : block.path("image_url").path("url").asText("");
                    images.add(MessagesHandler.parseDataUrlAttachment(url, null));
                } else if (Strings.CS.equals("input_file", type)) {
                    extractResponsesFileAttachment(block, files);
                }
            }
        }
        if (images.isEmpty() && files.isEmpty()) return SubmissionAttachments.NONE;
        return new SubmissionAttachments(images, files);
    }

    private static void extractResponsesFileAttachment(
            JsonNode node, List<SessionHostSubmission.Attachment> files) {
        String fileName = node.path("filename").isTextual() ? node.path("filename").asText() : null;
        String fileData = node.path("file_data").asText("");
        if (Strings.CS.startsWith(fileData, "data:")) {
            files.add(MessagesHandler.parseDataUrlAttachment(fileData, fileName));
        } else if (StringUtils.isNotEmpty(fileData)) {
            files.add(new SessionHostSubmission.Attachment(
                "application/pdf", fileName, MessagesHandler.decodeBase64Strict(fileData)));
        }
    }

    @Override
    public ProtocolTurnRunner.PerTurnProjection newTurn(JsonNode request) {
        String model = request.path("model").asText("");
        return new ResponsesProjection(model);
    }

    @Override
    public String errorBody(int status, String reason) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", reason);
        error.put("code", status == 400 ? "invalid_request" : "server_error");
        return body.toString();
    }

    static String extractResponsesPrompt(JsonNode request) {
        JsonNode input = request.path("input");
        if (input.isTextual() && !StringUtils.isBlank(input.asText())) return input.asText();
        if (!input.isArray()) return null;
        JsonNode lastUser = null;
        for (JsonNode item : input) {
            if (Strings.CS.equals("user", item.path("role").asText())) lastUser = item;
        }
        if (lastUser == null) return null;
        JsonNode content = lastUser.path("content");
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            if (Strings.CS.equals("input_text", block.path("type").asText())
                    && block.path("text").isTextual()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.path("text").asText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static final class ResponsesProjection
            implements ProtocolTurnRunner.PerTurnProjection {

        private final String model;
        private final String responseId;
        private final long createdAt;
        private final List<ContentBlock> accumulatedBlocks = new ArrayList<>();
        private final ArrayNode completedOutputItems = JsonUtils.getMapper().createArrayNode();
        private final StringBuilder openText = new StringBuilder();
        private final StringBuilder openReasoning = new StringBuilder();
        private int sequenceNumber;
        private int outputIndex;
        private Integer reasoningItemIndex;
        private Integer messageItemIndex;
        private boolean sawToolUse;

        private ResponsesProjection(String model) {
            this.model = model;
            this.responseId = "resp-gw-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);
            this.createdAt = System.currentTimeMillis() / 1_000;
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> openingFrames() {
            ObjectNode response = responseShell("in_progress", false);
            ObjectNode body = eventShell("response.created");
            body.set("response", response);
            return List.of(frame("response.created", body));
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
                        if (reasoningItemIndex == null) {
                            reasoningItemIndex = outputIndex++;
                            openReasoning.setLength(0);
                            frames.add(itemAdded(reasoningItemIndex, "reasoning"));
                        }
                        openReasoning.append(thinking.thinking());
                        frames.add(deltaEvent("response.reasoning_text.delta",
                            reasoningItemIndex, thinking.thinking()));
                    }
                    case TextBlock text -> {
                        closeReasoningIfOpen(frames);
                        if (messageItemIndex == null) {
                            messageItemIndex = outputIndex++;
                            openText.setLength(0);
                            frames.add(itemAdded(messageItemIndex, "message"));
                        }
                        openText.append(text.text());
                        frames.add(deltaEvent("response.output_text.delta",
                            messageItemIndex, text.text()));
                    }
                    case ToolUseBlock tool -> {
                        sawToolUse = true;
                        closeReasoningIfOpen(frames);
                        closeMessageIfOpen(frames);
                        int itemIndex = outputIndex++;
                        frames.add(functionCallAdded(itemIndex, tool));
                        ObjectNode doneItem = completedFunctionCallItem(itemIndex, tool);
                        completedOutputItems.add(doneItem.deepCopy());
                        frames.add(itemDoneFrame(itemIndex, doneItem));
                    }
                    default -> { }
                }
            }
        }

        private void closeReasoningIfOpen(List<ProtocolTurnRunner.SseFrame> frames) {
            if (reasoningItemIndex == null) return;
            ObjectNode doneItem = itemShell(reasoningItemIndex, "reasoning");
            ArrayNode summary = doneItem.putArray("summary");
            if (!openReasoning.isEmpty()) {
                summary.addObject().put("type", "summary_text").put("text", openReasoning.toString());
            }
            completedOutputItems.add(doneItem.deepCopy());
            frames.add(itemDoneFrame(reasoningItemIndex, doneItem));
            reasoningItemIndex = null;
            openReasoning.setLength(0);
        }

        private void closeMessageIfOpen(List<ProtocolTurnRunner.SseFrame> frames) {
            if (messageItemIndex == null) return;
            ObjectNode doneItem = itemShell(messageItemIndex, "message");
            doneItem.put("status", "completed");
            doneItem.put("role", "assistant");
            doneItem.putArray("content").addObject()
                .put("type", "output_text")
                .put("text", openText.toString())
                .putArray("annotations");
            completedOutputItems.add(doneItem.deepCopy());
            frames.add(itemDoneFrame(messageItemIndex, doneItem));
            messageItemIndex = null;
            openText.setLength(0);
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
                int itemIndex = outputIndex++;
                ObjectNode item = itemShell(itemIndex, "function_call_output");
                item.put("call_id", result.toolUseId());
                StringBuilder text = new StringBuilder("Tool failed");
                if (result.content() != null) {
                    for (ContentBlock inner : result.content()) {
                        if (inner instanceof TextBlock(String text1)) {
                            text.append(": ").append(text1);
                        }
                    }
                }
                item.put("output", text.toString());
                completedOutputItems.add(item.deepCopy());
                frames.add(itemDoneFrame(itemIndex, item));
            }
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> closingFrames(boolean userCancel) {
            List<ProtocolTurnRunner.SseFrame> frames = new ArrayList<>();
            closeReasoningIfOpen(frames);
            closeMessageIfOpen(frames);
            String status = userCancel ? "incomplete" : "completed";
            ObjectNode response = responseShell(status, true);
            ObjectNode body = eventShell("response." + status);
            body.set("response", response);
            frames.add(frame("response." + status, body));
            return frames;
        }

        @Override
        public String unaryResponse(boolean userCancel) {
            String stop = userCancel ? "max_tokens" : (sawToolUse ? "tool_use" : "end_turn");
            ApiMessage msg = new ApiMessage(
                responseId, "message", "assistant", List.copyOf(accumulatedBlocks),
                model, stop, null, Usage.EMPTY);
            return ProtocolWireCodec.encodeResponse(ModelApiProtocol.OPENAI_RESPONSES, msg);
        }

        private ObjectNode responseShell(String status, boolean includeOutput) {
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("id", responseId);
            response.put("object", "response");
            response.put("created_at", createdAt);
            response.put("status", status);
            response.put("model", model);
            if (includeOutput) {
                response.set("output", completedOutputItems.deepCopy());
            } else {
                response.putArray("output");
            }
            return response;
        }

        private ObjectNode eventShell(String type) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("type", type);
            body.put("sequence_number", sequenceNumber++);
            return body;
        }

        private ObjectNode itemShell(int index, String type) {
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            item.put("id", "item_" + index);
            item.put("type", type);
            return item;
        }

        private ProtocolTurnRunner.SseFrame itemAdded(int index, String type) {
            ObjectNode item = itemShell(index, type);
            if (Strings.CS.equals("message", type)) {
                item.put("status", "in_progress");
                item.put("role", "assistant");
                item.putArray("content");
            } else if (Strings.CS.equals("reasoning", type)) {
                item.putArray("summary");
            }
            return itemAddedFrame(index, item);
        }

        private ProtocolTurnRunner.SseFrame functionCallAdded(int index, ToolUseBlock tool) {
            ObjectNode item = itemShell(index, "function_call");
            item.put("status", "in_progress");
            item.put("call_id", tool.id());
            item.put("name", tool.name());
            item.put("arguments",
                tool.input() == null ? "{}" : tool.input().toString());
            return itemAddedFrame(index, item);
        }

        private ObjectNode completedFunctionCallItem(int index, ToolUseBlock tool) {
            ObjectNode item = itemShell(index, "function_call");
            item.put("status", "completed");
            item.put("call_id", tool.id());
            item.put("name", tool.name());
            item.put("arguments",
                tool.input() == null ? "{}" : tool.input().toString());
            return item;
        }

        private ProtocolTurnRunner.SseFrame itemAddedFrame(int index, ObjectNode item) {
            ObjectNode body = eventShell("response.output_item.added");
            body.put("output_index", index);
            body.set("item", item);
            return frame("response.output_item.added", body);
        }

        private ProtocolTurnRunner.SseFrame itemDoneFrame(int index, ObjectNode item) {
            ObjectNode body = eventShell("response.output_item.done");
            body.put("output_index", index);
            body.set("item", item);
            return frame("response.output_item.done", body);
        }

        private ProtocolTurnRunner.SseFrame deltaEvent(String type, int index, String delta) {
            ObjectNode body = eventShell(type);
            body.put("output_index", index);
            body.put("item_id", "item_" + index);
            body.put("delta", delta);
            return frame(type, body);
        }

        private ProtocolTurnRunner.SseFrame frame(String type, ObjectNode body) {
            return new ProtocolTurnRunner.SseFrame(type, body.toString());
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
            error.put("code", status == 400 ? "invalid_request" : "server_error");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
