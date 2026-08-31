package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.http.HttpCalls;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-compatible API client.
 */
@Explanation("OpenAI-compatible Chat Completions wire adapter")
public class OpenAiCompatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ApiConfig.OpenAiConfig config;
    private final String apiUrl;
    private final OkHttpClient streamingHttpClient;
    private final OkHttpClient nonStreamingHttpClient;

    public OpenAiCompatClient(ApiConfig.OpenAiConfig config) {
        this(config, HttpClientFactory.openAiStreaming(), HttpClientFactory.openAiNonStreaming());
    }

    public OpenAiCompatClient(ApiConfig.OpenAiConfig config, OkHttpClient httpClient) {
        this(config, httpClient, httpClient);
    }

    OpenAiCompatClient(ApiConfig.OpenAiConfig config, OkHttpClient streamingHttpClient,
                       OkHttpClient nonStreamingHttpClient) {
        this.config = config;
        this.streamingHttpClient = streamingHttpClient;
        this.nonStreamingHttpClient = nonStreamingHttpClient;
        this.apiUrl = endpoint(config.baseUrl(), "chat/completions");
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request,
            Runnable onRequestSubmitted) {
        log.info("OpenAI-compat streaming request to {} model {}", apiUrl, config.model());
        try {
            Request httpRequest = buildRequest(request, true);
            ChunkStreamTranslator translator = new ChunkStreamTranslator();
            return EventSourceStreamBridge.connect(
                streamingHttpClient, httpRequest, translator,
                ApiTimeouts.apiTimeout(), ApiTimeouts.watchdog(),
                request.cancellationRegistrar(), onRequestSubmitted);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("OpenAI streaming request failed", e);
            throw new ApiException("Request failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        log.info("OpenAI-compat request to {} model {}", apiUrl, config.model());
        try {
            Request httpRequest = buildRequest(request, false);
            try (Response response = HttpCalls.execute(
                    nonStreamingHttpClient, httpRequest, ApiTimeouts.apiTimeout(),
                    request.cancellationRegistrar())) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    String promptTooLongMessage =
                        PromptTooLongException.extractFromResponseBody(responseBody);
                    if (promptTooLongMessage != null) {
                        throw new PromptTooLongException(
                            promptTooLongMessage,
                            response.code(), null, null);
                    }
                    throw new ApiException("OpenAI API error: " + response.code() + " - " + responseBody, response.code());
                }
                ApiMessage message = parseNonStreamingResponse(responseBody);
                ApiRequestTiming timing = response.request().tag(ApiRequestTiming.class);
                return ApiMessageTiming.attach(message,
                    timing != null ? timing.lastAttemptStartMs() : 0L);
            }
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", 0, e);
        } catch (Exception e) {
            log.error("OpenAI request failed", e);
            throw new ApiException("Request failed: " + e.getMessage(), 0, e);
        }
    }

    private Request buildRequest(CreateMessageRequest request, boolean streaming) throws Exception {
        String requestBody = buildChatRequest(request, streaming);
        Request.Builder b = new Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/json")
                .tag(RetryRequestPolicy.class,
                    RetryRequestPolicy.forQuerySource(request.querySource()));
        boolean azure = config.baseUrl() != null && Strings.CS.contains(config.baseUrl(), "azure");
        if (!azure && config.apiKey() != null && !StringUtils.isBlank(config.apiKey())) {
            b.header("Authorization", "Bearer " + config.apiKey());
        }
        if (azure) {
            if (StringUtils.isNotBlank(config.apiKey())) b.header("api-key", config.apiKey());
        }
        config.headers().forEach(b::header);
        return b.post(RequestBody.create(requestBody, JSON)).build();
    }

    @Override
    public String getModel() {
        return config.model();
    }

    public String getBaseUrl() {
        return config.baseUrl();
    }

    private String buildChatRequest(CreateMessageRequest request, boolean streaming) throws Exception {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("model", request.model() != null ? request.model() : config.model());
        root.put("max_tokens", request.maxTokens());
        root.put("stream", streaming);
        if (streaming) root.putObject("stream_options").put("include_usage", true);

        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            root.put("top_p", request.topP());
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            root.set("stop", JsonUtils.getMapper().valueToTree(request.stopSequences()));
        }
        String effort = request.outputConfig() != null && request.outputConfig().effort() != null
            ? request.outputConfig().effort() : request.effort();
        if (StringUtils.isNotBlank(effort)) root.put("reasoning_effort", effort);
        if (request.outputConfig() != null && request.outputConfig().format() != null) {
            root.set("response_format", chatResponseFormat(request.outputConfig().format()));
        }

        ArrayNode messages = JsonUtils.getMapper().createArrayNode();

        if (StringUtils.isNotEmpty(request.systemPrompt())) {
            ObjectNode systemMsg = JsonUtils.getMapper().createObjectNode();
            systemMsg.put("role", "system");
            // Strip the internal cache-boundary sentinel — wire bodies must
            // never carry it (same treatment as AnthropicSdkClient).
            systemMsg.put("content", request.systemPrompt()
                .replace("\n\n" + SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n\n", "\n\n")
                .replace(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, ""));
            messages.add(systemMsg);
        }

        if (request.messages() != null) {
            for (int i = 0; i < request.messages().size(); i++) {
                CreateMessageRequest.RequestMessage msg = request.messages().get(i);
                if (msg.content() instanceof List<?> blocks && containsToolResult(blocks)) {
                    List<Object> consecutiveResults = new ArrayList<>(blocks);
                    while (i + 1 < request.messages().size()) {
                        CreateMessageRequest.RequestMessage next = request.messages().get(i + 1);
                        if (!(next.content() instanceof List<?> nextBlocks)
                                || !containsToolResult(nextBlocks)) break;
                        consecutiveResults.addAll(nextBlocks);
                        i++;
                    }
                    appendToolResultMessages(messages, consecutiveResults);
                } else {
                    appendRequestMessages(messages, msg);
                }
            }
        }

        root.set("messages", messages);

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = JsonUtils.getMapper().createArrayNode();
            for (CreateMessageRequest.ToolDefinition tool : request.tools()) {
                ObjectNode toolNode = JsonUtils.getMapper().createObjectNode();
                toolNode.put("type", "function");

                ObjectNode function = JsonUtils.getMapper().createObjectNode();
                function.put("name", tool.name());
                if (tool.description() != null) {
                    function.put("description", tool.description());
                }
                if (tool.inputSchema() != null) {
                    function.set("parameters", OpenAiToolSchemaProjection.project(tool.inputSchema()));
                }
                if (tool.strict() != null) function.put("strict", tool.strict());
                toolNode.set("function", function);
                tools.add(toolNode);
            }
            root.set("tools", tools);
        }

        if (request.toolChoice() != null) {
            if (Strings.CS.equals("tool", request.toolChoice().type())
                    && request.toolChoice().name() != null) {
                ObjectNode choice = root.putObject("tool_choice");
                choice.put("type", "function");
                choice.putObject("function").put("name", request.toolChoice().name());
            } else {
                root.put("tool_choice", Strings.CS.equals("any", request.toolChoice().type())
                    ? "required" : request.toolChoice().type());
            }
        }

        return JsonUtils.getMapper().writeValueAsString(
            LlmWireBodyFinalizer.finalizeForApi(root));
    }

    private static JsonNode chatResponseFormat(JsonNode format) {
        if (!Strings.CS.equals("json_schema", format.path("type").asText())) return format;
        ObjectNode responseFormat = JsonUtils.getMapper().createObjectNode().put("type", "json_schema");
        ObjectNode schema = responseFormat.putObject("json_schema");
        schema.put("name", format.path("name").asText("response"));
        schema.put("strict", format.path("strict").asBoolean(true));
        schema.set("schema", format.path("schema"));
        return responseFormat;
    }

    private void appendRequestMessages(ArrayNode messages, CreateMessageRequest.RequestMessage msg) {
        if (msg.content() instanceof String strContent) {
            ObjectNode node = messages.addObject();
            node.put("role", msg.role());
            node.put("content", strContent);
            return;
        }
        if (!(msg.content() instanceof List<?> listContent)) {
            ObjectNode node = messages.addObject();
            node.put("role", msg.role());
            node.put("content", String.valueOf(msg.content()));
            return;
        }

        if (Strings.CS.equals("assistant", msg.role())) {
            appendAssistantMessage(messages, listContent);
            return;
        }

        if (containsToolResult(listContent)) {
            appendToolResultMessages(messages, listContent);
            return;
        }

        ObjectNode node = messages.addObject();
        node.put("role", msg.role());
        ArrayNode content = node.putArray("content");
        for (Object item : listContent) appendChatUserContent(content, item);
        if (content.isEmpty()) node.put("content", "");
    }

    private static void appendAssistantMessage(ArrayNode messages, List<?> contentBlocks) {
        ObjectNode node = messages.addObject();
        node.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ArrayNode toolCalls = JsonUtils.getMapper().createArrayNode();
        for (Object item : contentBlocks) {
            JsonNode block = JsonUtils.getMapper().valueToTree(item);
            switch (block.path("type").asText("")) {
                case "text" -> appendJoined(text, block.path("text").asText(""));
                case "thinking" -> appendJoined(reasoning, block.path("thinking").asText(""));
                case "tool_use" -> toolCalls.add(chatToolCall(block));
                default -> throw new ApiException("OpenAI Chat assistant messages do not support "
                    + block.path("type").asText("unknown") + " content", 0);
            }
        }
        if (text.isEmpty()) node.putNull("content");
        else node.put("content", text.toString());
        if (!reasoning.isEmpty()) node.put("reasoning_content", reasoning.toString());
        if (!toolCalls.isEmpty()) node.set("tool_calls", toolCalls);
    }

    private static ObjectNode chatToolCall(JsonNode block) {
        ObjectNode call = JsonUtils.getMapper().createObjectNode();
        call.put("id", block.path("id").asText());
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", block.path("name").asText());
        JsonNode input = block.get("input");
        function.put("arguments", input == null || input.isNull() ? "{}" : input.toString());
        return call;
    }

    private static boolean containsToolResult(List<?> blocks) {
        for (Object item : blocks) {
            if (item instanceof ToolResultBlock) return true;
            JsonNode node = JsonUtils.getMapper().valueToTree(item);
            if (Strings.CS.equals("tool_result", node.path("type").asText())) return true;
        }
        return false;
    }

    private static void appendToolResultMessages(ArrayNode messages, List<?> blocks) {
        List<ImageBlock> pendingImages = new ArrayList<>();
        for (Object item : blocks) {
            if (item instanceof ToolResultBlock result) {
                ObjectNode tool = messages.addObject();
                tool.put("role", "tool");
                tool.put("tool_call_id", result.toolUseId());
                tool.put("content", OpenAiWireSupport.textContent(result.content()));
                if (result.content() != null) {
                    result.content().stream().filter(ImageBlock.class::isInstance)
                        .map(ImageBlock.class::cast).forEach(pendingImages::add);
                }
                continue;
            }
            JsonNode block = JsonUtils.getMapper().valueToTree(item);
            if (!Strings.CS.equals("tool_result", block.path("type").asText())) continue;
            ObjectNode tool = messages.addObject();
            tool.put("role", "tool");
            tool.put("tool_call_id", block.path("tool_use_id").asText());
            tool.put("content", textContent(block.get("content")));
            for (JsonNode child : block.path("content")) {
                if (Strings.CS.equals("image", child.path("type").asText())) {
                    pendingImages.add(new ImageBlock(child.get("source")));
                }
            }
        }
        if (!pendingImages.isEmpty()) {
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");
            pendingImages.forEach(image -> content.add(OpenAiWireSupport.chatImage(image.source())));
        }
    }

    private static void appendChatUserContent(ArrayNode content, Object item) {
        if (item instanceof String text) {
            content.add(OpenAiWireSupport.chatText(text));
            return;
        }
        if (item instanceof TextBlock text) {
            content.add(OpenAiWireSupport.chatText(text.text()));
            return;
        }
        if (item instanceof ImageBlock image) {
            content.add(OpenAiWireSupport.chatImage(image.source()));
            return;
        }
        JsonNode block = JsonUtils.getMapper().valueToTree(item);
        if (Strings.CS.equals("text", block.path("type").asText())) {
            content.add(OpenAiWireSupport.chatText(block.path("text").asText("")));
            return;
        }
        if (Strings.CS.equals("image", block.path("type").asText())) {
            content.add(OpenAiWireSupport.chatImage(block.get("source")));
            return;
        }
        throw new ApiException("OpenAI Chat user messages do not support "
            + block.path("type").asText("unknown") + " content", 0);
    }

    private static void appendJoined(StringBuilder target, String value) {
        if (!target.isEmpty()) target.append('\n');
        target.append(value);
    }

    private static String textContent(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.toString();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            if (!Strings.CS.equals("text", part.path("type").asText())) continue;
            appendJoined(text, part.path("text").asText(""));
        }
        return text.toString();
    }

    private static String endpoint(String baseUrl, String path) {
        String base = StringUtils.isBlank(baseUrl) ? "https://api.openai.com/v1" : baseUrl;
        while (Strings.CS.endsWith(base, "/")) base = base.substring(0, base.length() - 1);
        return Strings.CS.endsWith(base, "/" + path) ? base : base + "/" + path;
    }


    /**
     * Stateful per-stream chunk accumulator — a single OpenAI-compat chunk
     * can produce zero, one, or several {@link StreamEvent}s (a tool call's
     * arguments stream across many chunks before becoming one complete
     * {@code ContentBlockStart}), unlike Anthropic's 1:1 named-event
     * mapping. One instance per {@link #createMessageStream} call.
     *
     * <p>{@link #onClosed} flushes the same trailing synthetic events
     * ({@code ContentBlockStart}/{@code Stop} for accumulated tool calls, a
     * generic {@code MessageDelta("stop")}, then {@code MessageStop}) that
     * used to run unconditionally after the read loop — whether the stream
     * ended via an explicit {@code [DONE]} chunk or the connection just
     * closed. {@link #finish} guards against running twice (both paths can
     * fire: {@code [DONE]} then the connection closing right after).
     */
    private static final class ChunkStreamTranslator implements EventSourceStreamBridge.EventTranslator {
        private final List<ToolUseBlock> toolCalls = new ArrayList<>();
        private final List<String> toolCallArgs = new ArrayList<>();
        private final List<String> toolCallNames = new ArrayList<>();
        private final List<String> toolCallIds = new ArrayList<>();
        private Integer textIndex;
        private Integer reasoningIndex;
        private int nextBlockIndex;
        private String messageId;
        private String model;
        private String finalStopReason;
        private Usage latestUsage = Usage.EMPTY;
        private boolean finished = false;

        @Override
        public void translate(String type, String data, Consumer<StreamEvent> sink) {
            if (Strings.CS.equals("[DONE]", data)) {
                finish(sink);
                return;
            }
            JsonNode chunk;
            try {
                chunk = JsonUtils.getMapper().readTree(data);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid OpenAI Chat SSE event", e);
            }
            processChunk(chunk, sink);
        }

        @Override
        public void onClosed(Consumer<StreamEvent> sink) {
            finish(sink);
        }

        private void processChunk(JsonNode chunk, Consumer<StreamEvent> sink) {
            ensureMessageStarted(chunk, sink);
            JsonNode usage = chunk.get("usage");
            if (usage != null && !usage.isNull()) {
                latestUsage = parseUsage(usage);
            }
            JsonNode choices = chunk.get("choices");
            if (choices == null || choices.isNull()) return;

            for (JsonNode choice : choices) {
                JsonNode delta = choice.get("delta");
                if (delta == null) continue;

                if (delta.hasNonNull("reasoning_content")) {
                    if (reasoningIndex == null) {
                        reasoningIndex = nextBlockIndex++;
                        sink.accept(new StreamEvent.ContentBlockStart(reasoningIndex, new ThinkingBlock("", null), chunk));
                    }
                    sink.accept(new StreamEvent.ContentBlockDelta(reasoningIndex,
                        new Delta.ThinkingDelta(delta.get("reasoning_content").asText()), chunk));
                }

                if (delta.has("content")) {
                    String content = delta.get("content").asText();
                    if (StringUtils.isNotEmpty(content)) {
                        closeReasoning(chunk, sink);
                        if (textIndex == null) {
                            textIndex = nextBlockIndex++;
                            sink.accept(new StreamEvent.ContentBlockStart(textIndex, new TextBlock(""), chunk));
                        }
                        sink.accept(new StreamEvent.ContentBlockDelta(textIndex, new Delta.TextDelta(content), chunk));
                    }
                }

                if (delta.has("tool_calls")) {
                    JsonNode toolCallsDelta = delta.get("tool_calls");
                    for (JsonNode tc : toolCallsDelta) {
                        int index = tc.has("index") ? tc.get("index").asInt() : toolCalls.size();

                        while (toolCalls.size() <= index) {
                            toolCalls.add(null);
                            toolCallArgs.add("");
                            toolCallNames.add(null);
                            toolCallIds.add(null);
                        }

                        if (tc.hasNonNull("id")) toolCallIds.set(index, tc.get("id").asText());

                        if (tc.has("function")) {
                            if (tc.get("function").has("name")) {
                                String name = tc.get("function").get("name").asText();
                                toolCallNames.set(index, name);
                                String id = toolCallIds.get(index) != null ? toolCallIds.get(index)
                                    : "toolu_" + UUID.randomUUID().toString().substring(0, 8);
                                toolCalls.set(index, new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode()));
                            }

                            if (tc.get("function").has("arguments")) {
                                String args = tc.get("function").get("arguments").asText();
                                String currentArgs = toolCallArgs.get(index);
                                toolCallArgs.set(index, currentArgs + args);

                                String name = toolCallNames.get(index);
                                if (name != null) {
                                    try {
                                        JsonNode argsNode = JsonUtils.getMapper().readTree(toolCallArgs.get(index));
                                        String id = toolCallIds.get(index) != null ? toolCallIds.get(index)
                                            : toolCalls.get(index) != null ? toolCalls.get(index).id()
                                            : "toolu_" + UUID.randomUUID().toString().substring(0, 8);
                                        toolCalls.set(index, new ToolUseBlock(id, name, argsNode));
                                    } catch (Exception _) {
                                    }
                                }
                            }
                        }
                    }
                }

                JsonNode finishReason = choice.get("finish_reason");
                if (finishReason != null && !finishReason.isNull()) {
                    String reason = finishReason.asText();
                    finalStopReason = switch (reason) {
                        case "stop" -> "end_turn";
                        case "length" -> "max_tokens";
                        case "tool_calls", "function_call" -> "tool_use";
                        default -> reason;
                    };
                }
            }
        }

        private void ensureMessageStarted(JsonNode chunk, Consumer<StreamEvent> sink) {
            if (messageId != null) return;
            messageId = chunk.path("id").asText("chatcmpl_" + UUID.randomUUID());
            model = chunk.path("model").asText("");
            sink.accept(new StreamEvent.MessageStart(new ApiMessage(
                messageId, "message", "assistant", List.of(), model,
                null, null, Usage.EMPTY), chunk));
        }

        private void closeReasoning(JsonNode raw, Consumer<StreamEvent> sink) {
            if (reasoningIndex == null) return;
            sink.accept(new StreamEvent.ContentBlockStop(reasoningIndex, raw));
            reasoningIndex = null;
        }

        private void closeText(JsonNode raw, Consumer<StreamEvent> sink) {
            if (textIndex == null) return;
            sink.accept(new StreamEvent.ContentBlockStop(textIndex, raw));
            textIndex = null;
        }

        private void finish(Consumer<StreamEvent> sink) {
            if (finished) return;
            finished = true;

            closeReasoning(null, sink);
            closeText(null, sink);

            if (!toolCalls.isEmpty() && finalStopReason != null) {
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolUseBlock tool = toolCalls.get(i);
                    if (tool == null) continue;
                    int blockIndex = nextBlockIndex++;
                    sink.accept(new StreamEvent.ContentBlockStart(blockIndex,
                        new ToolUseBlock(tool.id(), tool.name(), JsonUtils.getMapper().createObjectNode())));
                    String arguments = StringUtils.isBlank(toolCallArgs.get(i)) ? "{}" : toolCallArgs.get(i);
                    requireJsonArguments(arguments, tool.name());
                    sink.accept(new StreamEvent.ContentBlockDelta(blockIndex,
                        new Delta.InputJsonDelta(arguments)));
                    sink.accept(new StreamEvent.ContentBlockStop(blockIndex));
                }
            }

            sink.accept(new StreamEvent.MessageDelta(
                new MessageDeltaData(finalStopReason != null ? finalStopReason : "end_turn", null), latestUsage));
            sink.accept(new StreamEvent.MessageStop());
        }
    }

    private ApiMessage parseNonStreamingResponse(String responseBody) throws Exception {
        JsonNode root = JsonUtils.getMapper().readTree(responseBody);

        String id = root.has("id") ? root.get("id").asText() : "chatcmpl_" + UUID.randomUUID();
        String model = root.has("model") ? root.get("model").asText() : config.model();
        JsonNode choices = root.get("choices");
        String stopReason = "stop";
        List<ContentBlock> contentBlocks = new ArrayList<>();

        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message != null) {
                if (message.hasNonNull("reasoning_content")) {
                    contentBlocks.add(new ThinkingBlock(message.get("reasoning_content").asText(), null));
                }

                if (message.has("content")) {
                    String content = message.get("content").asText();
                    if (StringUtils.isNotEmpty(content)) {
                        contentBlocks.add(new TextBlock(content));
                    }
                }

                if (message.has("tool_calls")) {
                    for (JsonNode tc : message.get("tool_calls")) {
                        String name = tc.get("function").get("name").asText();
                        String args = tc.get("function").get("arguments").asText();
                        String toolId = tc.has("id") ? tc.get("id").asText() :
                                "toolu_" + UUID.randomUUID().toString().substring(0, 8);

                        JsonNode argsNode = requireJsonArguments(args, name);
                        contentBlocks.add(new ToolUseBlock(toolId, name, argsNode));
                    }
                }

                JsonNode finish = choice.get("finish_reason");
                if (finish != null) {
                    stopReason = finish.asText();
                    stopReason = switch (stopReason) {
                        case "stop" -> "end_turn";
                        case "length" -> "max_tokens";
                        case "tool_calls", "function_call" -> "tool_use";
                        default -> stopReason;
                    };
                }
            }
        }

        JsonNode usage = root.get("usage");
        Usage tokenUsage = Usage.EMPTY;
        if (usage != null) {
            tokenUsage = parseUsage(usage);
        }

        return new ApiMessage(id, "message", "assistant", contentBlocks, model,
                stopReason, null, tokenUsage);
    }

    @Explanation("normalizes overlapping OpenAI cache detail into disjoint token buckets")
    private static Usage parseUsage(JsonNode usage) {
        long inputTokens = usage.path("prompt_tokens").asLong();
        long cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
        return new Usage(Math.max(0, inputTokens - cachedTokens),
            usage.path("completion_tokens").asLong(), 0, cachedTokens,
            usage.has("total_tokens") ? usage.path("total_tokens").asLong() : null);
    }

    private static JsonNode requireJsonArguments(String arguments, String toolName) {
        try {
            return JsonUtils.getMapper().readTree(StringUtils.isBlank(arguments) ? "{}" : arguments);
        } catch (Exception e) {
            throw new ApiException("Invalid JSON input for OpenAI Chat tool call " + toolName, 0, e);
        }
    }
}
