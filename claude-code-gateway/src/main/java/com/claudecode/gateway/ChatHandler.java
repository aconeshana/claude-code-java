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
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code POST /v1/chat/completions}: the OpenAI Chat Completions protocol
 * face for submitting one turn to the active session and streaming that turn
 * back as {@code chat.completion.chunk} frames.
 *
 * <p>The turn lifecycle is the shared {@link ProtocolTurnRunner} flow; this
 * class is the OpenAI wire projection. The chunk shapes mirror what
 * {@code OpenAiCompatClient} parses inbound: {@code delta.content},
 * {@code delta.reasoning_content}, and {@code delta.tool_calls[].function}.
 */
public final class ChatHandler implements ProtocolTurnRunner.TurnProjection {

    private final ProtocolTurnRunner runner;

    public ChatHandler(ProtocolTurnRunner.SessionResolver sessions, InFlightGuard inFlight) {
        this.runner = new ProtocolTurnRunner(sessions, inFlight);
    }

    /** Handles one request exchange; owns the SSE lane it opens. */
    public void handle(HttpExchange exchange) throws IOException {
        runner.handle(exchange.getRequestBody(), new HttpProtocolResponder(exchange), this);
    }

    @Override
    public String extractPrompt(JsonNode request) {
        return MessagesHandler.extractLastUserText(request);
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

    /**
     * The Chat Completions per-turn projection.
     *
     * <p>OpenAI chunks carry no block lifecycle — only deltas — so this
     * projection maps each translated content block to its delta frame and
     * finishes with the terminal chunk carrying {@code finish_reason}.
     */
    private static final class ChatProjection implements ProtocolTurnRunner.PerTurnProjection {

        private final String model;
        private final String responseId;
        private final String created;
        private boolean anyToolCall;

        private ChatProjection(String model) {
            this.model = model;
            this.responseId = "chatcmpl-gw-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24);
            this.created = Long.toString(System.currentTimeMillis() / 1_000);
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> openingFrames() {
            // The first chunk establishes the role, matching OpenAI's opening
            // chunk shape before content deltas arrive.
            ObjectNode delta = JsonUtils.getMapper().createObjectNode();
            delta.putObject("role").put("", "assistant");
            delta.set("content", JsonUtils.getMapper().createObjectNode().textNode(""));
            return List.of(chunk(delta, null));
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
                        call.put("index", toolCallsIndex());
                        call.put("id", tool.id());
                        ObjectNode function = call.putObject("function");
                        function.put("name", tool.name());
                        function.put("arguments",
                            tool.input() == null ? "{}" : tool.input().toString());
                        frames.add(chunk(delta, null));
                    }
                    default -> { /* Rich blocks have no Chat face here. */ }
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
                if (!result.isError()) continue; // Successful tool results are silent in chat.
                ObjectNode delta = deltaObject();
                delta.put("content", "Tool " + result.toolUseId() + " failed");
                frames.add(chunk(delta, null));
            }
        }

        @Override
        public List<ProtocolTurnRunner.SseFrame> closingFrames(boolean userCancel) {
            ObjectNode delta = deltaObject();
            delta.putNull("content");
            String finishReason = userCancel ? "cancelled"
                : anyToolCall ? "tool_calls" : "stop";
            return List.of(chunk(delta, finishReason));
        }

        private int toolCallsIndex() {
            return anyToolCallIndex++;
        }

        private int anyToolCallIndex;

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

    /** Bridges the runner onto one HTTP exchange with OpenAI error bodies. */
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
