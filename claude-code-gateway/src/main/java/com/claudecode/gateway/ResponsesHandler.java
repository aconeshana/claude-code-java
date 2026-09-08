package com.claudecode.gateway;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
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
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /v1/responses}: the OpenAI Responses protocol face for
 * submitting one turn to the active session and streaming that turn back as
 * {@code response.*} events.
 *
 * <p>The turn lifecycle is the shared {@link ProtocolTurnRunner} flow; this
 * class is the Responses wire projection. The event shapes mirror what
 * {@code OpenAiResponsesClient} parses inbound: {@code response.created},
 * {@code response.output_text.delta}, {@code response.output_item.*}, and
 * {@code response.completed}.
 */
public final class ResponsesHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    public ResponsesHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

    /** Handles one request exchange; owns the SSE lane it opens. */
    public void handle(HttpExchange exchange) throws IOException {
        runner.handle(exchange.getRequestBody(), new HttpProtocolResponder(exchange), this);
    }

    @Override
    public String extractPrompt(JsonNode request) {
        return extractResponsesPrompt(request);
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

    /**
     * The Responses prompt: a plain string {@code input}, or the last user
     * message of an {@code input[]} array ({@code role:"user"} with
     * {@code content[].text} or plain string content).
     */
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
        for (JsonNode block : (ArrayNode) content) {
            if (Strings.CS.equals("input_text", block.path("type").asText())
                    && block.path("text").isTextual()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.path("text").asText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /**
     * The Responses per-turn projection.
     *
     * <p>Responses events carry output items: this projection opens a
     * reasoning item for thinking, a message item for text, and function_call
     * items for tool use, with delta events inside each item — the same
     * item/delta split {@code OpenAiResponsesClient} reassembles inbound.
     */
    private static final class ResponsesProjection
            implements ProtocolTurnRunner.PerTurnProjection {

        private final String model;
        private final String responseId;
        private int outputIndex;
        private Integer reasoningItemIndex = null;
        private Integer messageItemIndex = null;

        private ResponsesProjection(String model) {
            this.model = model;
            this.responseId = "resp-gw-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> openingFrames() {
            ObjectNode response = responseShell("in_progress");
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
                default -> { /* Only assistant output and tool results project. */ }
            }
            return frames;
        }

        private void assistantFrames(SDKMessage.Assistant assistant,
                List<ProtocolTurnRunner.SseFrame> frames) {
            AssistantMessage body = assistant.message();
            if (body == null || body.message() == null) return;
            List<ContentBlock> blocks = body.message().content();
            if (blocks == null) return;
            for (ContentBlock block : blocks) {
                switch (block) {
                    case ThinkingBlock thinking -> {
                        if (reasoningItemIndex == null) {
                            reasoningItemIndex = outputIndex++;
                            frames.add(itemAdded(reasoningItemIndex, "reasoning"));
                        }
                        frames.add(deltaEvent("response.reasoning_text.delta",
                            reasoningItemIndex, thinking.thinking()));
                    }
                    case TextBlock text -> {
                        if (reasoningItemIndex != null) {
                            frames.add(itemDone(reasoningItemIndex, "reasoning"));
                            reasoningItemIndex = null;
                        }
                        if (messageItemIndex == null) {
                            messageItemIndex = outputIndex++;
                            frames.add(itemAdded(messageItemIndex, "message"));
                        }
                        frames.add(deltaEvent("response.output_text.delta",
                            messageItemIndex, text.text()));
                    }
                    case ToolUseBlock tool -> {
                        if (messageItemIndex != null) {
                            frames.add(itemDone(messageItemIndex, "message"));
                            messageItemIndex = null;
                        }
                        int itemIndex = outputIndex++;
                        frames.add(functionCallAdded(itemIndex, tool));
                        frames.add(functionCallDone(itemIndex, tool));
                    }
                    default -> { /* Rich blocks have no Responses face here. */ }
                }
            }
        }

        private void toolResultFrames(SDKMessage.User user,
                List<ProtocolTurnRunner.SseFrame> frames) {
            UserMessage body = user.message();
            if (body == null || body.message() == null) return;
            List<ContentBlock> blocks = body.message().blocks();
            if (blocks == null) return;
            for (ContentBlock block : blocks) {
                if (!(block instanceof ToolResultBlock result)) continue;
                if (!result.isError()) continue; // Successful tool results are silent.
                int itemIndex = outputIndex++;
                ObjectNode item = itemShell(itemIndex, "function_call_output");
                item.put("call_id", result.toolUseId());
                StringBuilder text = new StringBuilder("Tool failed");
                if (result.content() != null) {
                    for (ContentBlock inner : result.content()) {
                        if (inner instanceof TextBlock innerText) {
                            text.append(": ").append(innerText.text());
                        }
                    }
                }
                item.put("output", text.toString());
                frames.add(itemDoneFrame(itemIndex, item));
            }
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> closingFrames(boolean userCancel) {
            List<ProtocolTurnRunner.SseFrame> frames = new ArrayList<>();
            if (reasoningItemIndex != null) {
                frames.add(itemDone(reasoningItemIndex, "reasoning"));
                reasoningItemIndex = null;
            }
            if (messageItemIndex != null) {
                frames.add(itemDone(messageItemIndex, "message"));
                messageItemIndex = null;
            }
            String status = userCancel ? "incomplete" : "completed";
            ObjectNode response = responseShell(status);
            ObjectNode body = eventShell("response." + status);
            body.set("response", response);
            frames.add(frame("response." + status, body));
            return frames;
        }

        // ── frame builders ────────────────────────────────────────────────

        private ObjectNode responseShell(String status) {
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("id", responseId);
            response.put("object", "response");
            response.put("status", status);
            if (!model.isEmpty()) response.put("model", model);
            response.putArray("output");
            return response;
        }

        private ObjectNode eventShell(String type) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("type", type);
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
            return itemAddedFrame(index, item);
        }

        private ProtocolTurnRunner.SseFrame functionCallAdded(int index, ToolUseBlock tool) {
            ObjectNode item = itemShell(index, "function_call");
            item.put("call_id", tool.id());
            item.put("name", tool.name());
            item.put("arguments",
                tool.input() == null ? "{}" : tool.input().toString());
            return itemAddedFrame(index, item);
        }

        private ProtocolTurnRunner.SseFrame functionCallDone(int index, ToolUseBlock tool) {
            ObjectNode item = itemShell(index, "function_call");
            item.put("call_id", tool.id());
            item.put("name", tool.name());
            return itemDoneFrame(index, item);
        }

        private ProtocolTurnRunner.SseFrame itemAddedFrame(int index, ObjectNode item) {
            ObjectNode body = eventShell("response.output_item.added");
            body.put("output_index", index);
            body.set("item", item);
            return frame("response.output_item.added", body);
        }

        private ProtocolTurnRunner.SseFrame itemDone(int index, String type) {
            return itemDoneFrame(index, itemShell(index, type));
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
            body.put("delta", delta);
            return frame(type, body);
        }

        private ProtocolTurnRunner.SseFrame frame(String type, ObjectNode body) {
            return new ProtocolTurnRunner.SseFrame(type, body.toString());
        }
    }

    /** Bridges the runner onto one HTTP exchange with Responses error bodies. */
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
