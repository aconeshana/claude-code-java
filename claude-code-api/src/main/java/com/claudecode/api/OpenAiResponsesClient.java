package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.OpenAiResponsesItemProjector;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.http.HttpCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.Strings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * OpenAI Responses API adapter translating {@code /v1/responses} wire data to the shared
 * Anthropic-shaped {@link LlmClient} contract.
 */
@Explanation("OpenAI Responses wire adapter")
public final class OpenAiResponsesClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ApiConfig.OpenAiConfig config;
    private final String apiUrl;
    private final OkHttpClient streamingHttpClient;
    private final OkHttpClient nonStreamingHttpClient;

    public OpenAiResponsesClient(ApiConfig.OpenAiConfig config) {
        this(config, HttpClientFactory.openAiStreaming(), HttpClientFactory.openAiNonStreaming());
    }

    public OpenAiResponsesClient(ApiConfig.OpenAiConfig config, OkHttpClient httpClient) {
        this(config, httpClient, httpClient);
    }

    OpenAiResponsesClient(ApiConfig.OpenAiConfig config, OkHttpClient streamingHttpClient,
                          OkHttpClient nonStreamingHttpClient) {
        this.config = config;
        this.streamingHttpClient = streamingHttpClient;
        this.nonStreamingHttpClient = nonStreamingHttpClient;
        this.apiUrl = endpoint(config.baseUrl(), "responses");
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request, Runnable onRequestSubmitted) {
        log.info("OpenAI Responses streaming request to {} model {}", apiUrl, config.model());
        try {
            Request httpRequest = buildRequest(request, true);
            return EventSourceStreamBridge.connect(
                streamingHttpClient, httpRequest, new ResponsesEventTranslator(),
                ApiTimeouts.apiTimeout(), ApiTimeouts.watchdog(),
                request.cancellationRegistrar(), onRequestSubmitted);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Request failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        return createMessage(request, 0);
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        log.info("OpenAI Responses request to {} model {}", apiUrl, config.model());
        try {
            Request httpRequest = buildRequest(request, false);
            var timeout = timeoutMillis > 0
                ? Duration.ofMillis(timeoutMillis) : ApiTimeouts.apiTimeout();
            try (Response response = HttpCalls.execute(
                    nonStreamingHttpClient, httpRequest, timeout, request.cancellationRegistrar())) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) throw apiError(response.code(), responseBody);
                ApiMessage message = parseResponse(responseBody);
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
            throw new ApiException("Request failed: " + e.getMessage(), 0, e);
        }
    }

    private Request buildRequest(CreateMessageRequest request, boolean streaming) throws Exception {
        ObjectNode body = buildResponseRequest(request, streaming);
        Request.Builder builder = new Request.Builder()
            .url(apiUrl)
            .header("Accept", streaming ? "text/event-stream" : "application/json")
            .tag(RetryRequestPolicy.class,
                RetryRequestPolicy.forQuerySource(request.querySource()));
        if (StringUtils.isNotBlank(config.apiKey())) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }
        config.headers().forEach(builder::header);
        return builder.post(RequestBody.create(JsonUtils.getMapper().writeValueAsBytes(body), JSON)).build();
    }

    private ObjectNode buildResponseRequest(CreateMessageRequest request, boolean streaming) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("model", request.model() != null ? request.model() : config.model());
        root.put("max_output_tokens", request.maxTokens());
        root.put("stream", streaming);
        root.put("store", false);

        String instructions = stripInternalBoundary(request.systemPrompt());
        if (StringUtils.isNotBlank(instructions)) root.put("instructions", instructions);
        if (request.temperature() != null) root.put("temperature", request.temperature());
        if (request.topP() != null) root.put("top_p", request.topP());

        String effort = request.outputConfig() != null && request.outputConfig().effort() != null
            ? request.outputConfig().effort() : request.effort();
        boolean thinkingDisabled = request.thinking() != null
            && Strings.CS.equals("disabled", request.thinking().type());
        boolean reasoningEnabled = !thinkingDisabled
            && (request.thinking() != null || StringUtils.isNotBlank(effort));
        if (reasoningEnabled) {
            ObjectNode reasoning = root.putObject("reasoning");
            if (StringUtils.isNotBlank(effort)) reasoning.put("effort", effort);
            reasoning.put("summary", "auto");
            root.putArray("include").add("reasoning.encrypted_content");
        }
        if (request.outputConfig() != null && request.outputConfig().format() != null) {
            root.set("text", responsesTextFormat(request.outputConfig().format()));
        }

        ArrayNode input = root.putArray("input");
        if (request.messages() != null) {
            for (CreateMessageRequest.RequestMessage message : request.messages()) {
                try {
                    OpenAiResponsesItemProjector.project(
                        message.role(), message.content(), OpenAiWireSupport::imageUrl,
                        OpenAiWireSupport::responsesToolContent)
                        .forEach(input::add);
                } catch (IllegalArgumentException e) {
                    throw new ApiException(e.getMessage(), 0, e);
                }
            }
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (CreateMessageRequest.ToolDefinition tool : request.tools()) {
                if (tool.inputSchema() == null) continue;
                ObjectNode item = tools.addObject();
                item.put("type", "function");
                item.put("name", tool.name());
                if (tool.description() != null) item.put("description", tool.description());
                item.set("parameters", OpenAiToolSchemaProjection.project(tool.inputSchema()));
                if (tool.strict() != null) item.put("strict", tool.strict());
            }
        }
        if (request.toolChoice() != null) {
            if (Strings.CS.equals("tool", request.toolChoice().type())
                    && request.toolChoice().name() != null) {
                ObjectNode choice = root.putObject("tool_choice");
                choice.put("type", "function");
                choice.put("name", request.toolChoice().name());
            } else {
                root.put("tool_choice", openAiToolChoice(request.toolChoice().type()));
            }
        }
        return LlmWireBodyFinalizer.finalizeForApi(root);
    }

    private static String openAiToolChoice(String type) {
        return Strings.CS.equals("any", type) ? "required" : type;
    }

    private static ObjectNode responsesTextFormat(JsonNode format) {
        ObjectNode text = JsonUtils.getMapper().createObjectNode();
        if (!Strings.CS.equals("json_schema", format.path("type").asText())) {
            text.set("format", format);
            return text;
        }
        ObjectNode nativeFormat = text.putObject("format");
        nativeFormat.put("type", "json_schema");
        nativeFormat.put("name", format.path("name").asText("response"));
        nativeFormat.put("strict", format.path("strict").asBoolean(true));
        nativeFormat.set("schema", format.path("schema"));
        return text;
    }

    private ApiMessage parseResponse(String responseBody) throws Exception {
        JsonNode root = JsonUtils.getMapper().readTree(responseBody);
        List<ContentBlock> content = new ArrayList<>();
        JsonNode output = root.get("output");
        if (output != null && output.isArray()) {
            for (JsonNode item : output) appendOutputItem(content, item);
        }
        return new ApiMessage(
            root.path("id").asText("resp_" + UUID.randomUUID()),
            "message", "assistant", List.copyOf(content),
            root.path("model").asText(config.model()), stopReason(root), null,
            parseUsage(root.get("usage")));
    }

    private static void appendOutputItem(List<ContentBlock> content, JsonNode item) {
        switch (item.path("type").asText()) {
            case "message" -> {
                JsonNode parts = item.get("content");
                if (parts != null) {
                    for (JsonNode part : parts) {
                        if (Strings.CS.equals("output_text", part.path("type").asText())) {
                            content.add(new TextBlock(part.path("text").asText("")));
                        }
                    }
                }
            }
            case "function_call" -> content.add(new ToolUseBlock(
                item.path("call_id").asText(item.path("id").asText("call_" + UUID.randomUUID())),
                item.path("name").asText(), parseArguments(item.path("arguments").asText("{}"))));
            case "reasoning" -> {
                StringBuilder summary = new StringBuilder();
                for (JsonNode part : item.path("summary")) {
                    if (!summary.isEmpty()) summary.append('\n');
                    summary.append(part.path("text").asText(""));
                }
                content.add(new ThinkingBlock(summary.toString(),
                    item.path("encrypted_content").isTextual()
                        ? item.path("encrypted_content").asText() : null));
            }
            default -> appendHostedTool(content, item);
        }
    }

    private static void appendHostedTool(List<ContentBlock> content, JsonNode item) {
        HostedTool hosted = HostedTool.from(item);
        if (hosted == null) return;
        content.add(new ServerToolUseBlock(hosted.id(), hosted.name(), hosted.input()));
        content.add(new ServerToolResultBlock(hosted.id(), hosted.name(), item.deepCopy(),
            item.hasNonNull("error"), item.path("type").asText()));
    }

    private static JsonNode parseArguments(String arguments) {
        try {
            return JsonUtils.getMapper().readTree(arguments);
        } catch (Exception e) {
            throw new ApiException("Invalid JSON input for OpenAI Responses tool call", 0, e);
        }
    }

    @Explanation("normalizes overlapping OpenAI cache detail into disjoint token buckets")
    private static Usage parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) return Usage.EMPTY;
        long input = usage.path("input_tokens").asLong();
        long cached = usage.path("input_tokens_details").path("cached_tokens").asLong();
        return new Usage(Math.max(0, input - cached), usage.path("output_tokens").asLong(), 0,
            cached,
            usage.has("total_tokens") ? usage.path("total_tokens").asLong() : null);
    }

    private static String stopReason(JsonNode response) {
        if (Strings.CS.equals("incomplete", response.path("status").asText())) return "max_tokens";
        for (JsonNode item : response.path("output")) {
            if (Strings.CS.equals("function_call", item.path("type").asText())) return "tool_use";
        }
        return "end_turn";
    }

    private static ApiException apiError(int status, String body) {
        String promptTooLong = PromptTooLongException.extractFromResponseBody(body);
        if (promptTooLong != null) return new PromptTooLongException(promptTooLong, status, null, null);
        String message = body;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(body);
            if (root.path("error").has("message")) message = root.path("error").path("message").asText();
        } catch (Exception _) { }
        return new ApiException("OpenAI Responses API error: " + status + " - " + message, status);
    }

    private static String stripInternalBoundary(String value) {
        if (value == null) return null;
        return value
            .replace("\n\n" + SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n\n", "\n\n")
            .replace(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, "");
    }

    private static String endpoint(String baseUrl, String path) {
        String base = StringUtils.isBlank(baseUrl) ? "https://api.openai.com/v1" : baseUrl;
        while (Strings.CS.endsWith(base, "/")) base = base.substring(0, base.length() - 1);
        return Strings.CS.endsWith(base, "/" + path) ? base : base + "/" + path;
    }

    @Override
    public String getModel() {
        return config.model();
    }

    private static final class ResponsesEventTranslator implements EventSourceStreamBridge.EventTranslator {
        private final Map<Integer, FunctionCallState> calls = new LinkedHashMap<>();
        private final Map<Integer, Boolean> textBlocks = new LinkedHashMap<>();
        private final Map<Integer, Boolean> reasoningBlocks = new LinkedHashMap<>();
        private final Map<String, Integer> reasoningParts = new LinkedHashMap<>();
        private final Map<String, Integer> activeReasoningByItem = new LinkedHashMap<>();
        private int nextReasoningIndex = -1;
        private String responseId;
        private String model;
        private boolean finished;

        @Override
        public void translate(String eventType, String data, Consumer<StreamEvent> sink) {
            JsonNode event;
            try {
                event = JsonUtils.getMapper().readTree(data);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Responses SSE event", e);
            }
            String type = event.path("type").asText(eventType == null ? "" : eventType);
            switch (type) {
                case "response.created", "response.in_progress" -> startMessage(event, sink);
                case "response.output_text.delta" -> {
                    int index = event.path("output_index").asInt(0);
                    ensureTextStarted(index, sink);
                    sink.accept(new StreamEvent.ContentBlockDelta(index,
                        new Delta.TextDelta(event.path("delta").asText("")), event));
                }
                case "response.output_text.done" -> stopText(event.path("output_index").asInt(0), event, sink);
                case "response.reasoning_text.delta", "response.reasoning_summary.delta",
                     "response.reasoning_summary_text.delta" -> {
                    int index = reasoningIndex(event, sink);
                    sink.accept(new StreamEvent.ContentBlockDelta(index,
                        new Delta.ThinkingDelta(event.path("delta").asText("")), event));
                }
                case "response.reasoning_summary_part.added" -> reasoningIndex(event, sink);
                case "response.reasoning_summary_part.done" -> { /* closed when the next part or item arrives */ }
                case "response.reasoning_summary_text.done" -> stopReasoningPart(event, sink);
                case "response.output_item.added" -> addOutputItem(event);
                case "response.function_call_arguments.delta" -> {
                    int index = event.path("output_index").asInt();
                    FunctionCallState state = calls.computeIfAbsent(index, _ -> new FunctionCallState());
                    state.arguments.append(event.path("delta").asText(""));
                }
                case "response.output_item.done" -> finishOutputItem(event, sink);
                case "response.completed", "response.incomplete" -> finish(event.path("response"), event, sink);
                case "response.failed", "error" -> throw streamApiException(event, type);
                default -> { }
            }
        }

        private static ApiException streamApiException(JsonNode event, String type) {
            JsonNode error = event.path("error");
            if (error.isMissingNode() || error.isNull()) error = event.path("response").path("error");
            String message = event.path("message").asText(error.path("message").asText(""));
            String code = event.path("code").asText(error.path("code").asText(""));
            String formatted;
            if (!StringUtils.isBlank(code) && !StringUtils.isBlank(message)) formatted = code + ": " + message;
            else if (!StringUtils.isBlank(message)) formatted = message;
            else if (!StringUtils.isBlank(code)) formatted = code;
            else formatted = Strings.CS.equals("response.failed", type)
                ? "OpenAI Responses response failed" : "OpenAI Responses stream error";
            return new ApiException(formatted, 0, StringUtils.isBlank(code) ? null : code);
        }

        private void startMessage(JsonNode event, Consumer<StreamEvent> sink) {
            JsonNode response = event.path("response");
            if (responseId != null) return;
            responseId = response.path("id").asText("resp_" + UUID.randomUUID());
            model = response.path("model").asText("");
            sink.accept(new StreamEvent.MessageStart(new ApiMessage(
                responseId, "message", "assistant", List.of(), model,
                null, null, Usage.EMPTY), event));
        }

        private void ensureTextStarted(int index, Consumer<StreamEvent> sink) {
            if (textBlocks.putIfAbsent(index, Boolean.TRUE) == null) {
                sink.accept(new StreamEvent.ContentBlockStart(index, new TextBlock("")));
            }
        }

        private void stopText(int index, JsonNode event, Consumer<StreamEvent> sink) {
            if (textBlocks.remove(index) != null) sink.accept(new StreamEvent.ContentBlockStop(index, event));
        }

        private void addOutputItem(JsonNode event) {
            JsonNode item = event.path("item");
            int index = event.path("output_index").asInt();
            if (Strings.CS.equals("reasoning", item.path("type").asText())) {
                return;
            }
            if (!Strings.CS.equals("function_call", item.path("type").asText())) return;
            FunctionCallState state = calls.computeIfAbsent(index, _ -> new FunctionCallState());
            state.id = item.path("call_id").asText(item.path("id").asText("call_" + UUID.randomUUID()));
            state.name = item.path("name").asText();
            state.arguments.append(item.path("arguments").asText(""));
        }

        private void finishOutputItem(JsonNode event, Consumer<StreamEvent> sink) {
            JsonNode item = event.path("item");
            int index = event.path("output_index").asInt();
            if (Strings.CS.equals("reasoning", item.path("type").asText())) {
                String itemId = item.path("id").asText(event.path("item_id").asText("reasoning"));
                Integer reasoningIndex = activeReasoningByItem.get(itemId);
                if (reasoningIndex == null) {
                    reasoningIndex = startReasoningPart(itemId, 0, event, sink);
                }
                if (item.path("encrypted_content").isTextual()) {
                    sink.accept(new StreamEvent.ContentBlockDelta(reasoningIndex,
                        new Delta.SignatureDelta(item.path("encrypted_content").asText()), event));
                }
                stopReasoning(reasoningIndex, event, sink);
                activeReasoningByItem.remove(itemId);
                return;
            }
            HostedTool hosted = HostedTool.from(item);
            if (hosted != null) {
                sink.accept(new StreamEvent.ContentBlockStart(index,
                    new ServerToolUseBlock(hosted.id(), hosted.name(), hosted.input()), event));
                sink.accept(new StreamEvent.ContentBlockStop(index, event));
                sink.accept(new StreamEvent.ContentBlockStart(index,
                    new ServerToolResultBlock(hosted.id(), hosted.name(), item.deepCopy(),
                        item.hasNonNull("error"), item.path("type").asText()), event));
                sink.accept(new StreamEvent.ContentBlockStop(index, event));
                return;
            }
            if (!Strings.CS.equals("function_call", item.path("type").asText())) return;
            FunctionCallState state = calls.computeIfAbsent(index, _ -> new FunctionCallState());
            if (state.id == null) state.id = item.path("call_id").asText(item.path("id").asText());
            if (state.name == null) state.name = item.path("name").asText();
            if (state.arguments.isEmpty()) state.arguments.append(item.path("arguments").asText(""));
            emitCall(index, state, event, sink);
            calls.remove(index);
        }

        private void emitCall(int index, FunctionCallState state, JsonNode event, Consumer<StreamEvent> sink) {
            if (state.emitted) return;
            state.emitted = true;
            String arguments = state.arguments.isEmpty() ? "{}" : state.arguments.toString();
            parseArguments(arguments);
            sink.accept(new StreamEvent.ContentBlockStart(index,
                new ToolUseBlock(state.id, state.name, JsonUtils.getMapper().createObjectNode()), event));
            sink.accept(new StreamEvent.ContentBlockDelta(index,
                new Delta.InputJsonDelta(arguments), event));
            sink.accept(new StreamEvent.ContentBlockStop(index, event));
        }

        private void ensureReasoningStarted(int index, Consumer<StreamEvent> sink) {
            if (reasoningBlocks.putIfAbsent(index, Boolean.TRUE) == null) {
                sink.accept(new StreamEvent.ContentBlockStart(index, new ThinkingBlock("", null)));
            }
        }

        private int reasoningIndex(JsonNode event, Consumer<StreamEvent> sink) {
            String itemId = event.path("item_id").asText("reasoning");
            int summaryIndex = event.path("summary_index").asInt(0);
            String key = itemId + ":" + summaryIndex;
            Integer existing = reasoningParts.get(key);
            return existing != null ? existing : startReasoningPart(itemId, summaryIndex, event, sink);
        }

        private int startReasoningPart(String itemId, int summaryIndex, JsonNode event,
                                       Consumer<StreamEvent> sink) {
            Integer previous = activeReasoningByItem.get(itemId);
            if (previous != null) stopReasoning(previous, event, sink);
            int index = nextReasoningIndex--;
            reasoningParts.put(itemId + ":" + summaryIndex, index);
            activeReasoningByItem.put(itemId, index);
            ensureReasoningStarted(index, sink);
            return index;
        }

        private void stopReasoningPart(JsonNode event, Consumer<StreamEvent> sink) {
            String itemId = event.path("item_id").asText("reasoning");
            Integer index = activeReasoningByItem.remove(itemId);
            if (index != null) stopReasoning(index, event, sink);
        }

        private void stopReasoning(int index, JsonNode event, Consumer<StreamEvent> sink) {
            if (reasoningBlocks.remove(index) != null) {
                sink.accept(new StreamEvent.ContentBlockStop(index, event));
            }
        }

        private void finish(JsonNode response, JsonNode raw, Consumer<StreamEvent> sink) {
            if (finished) return;
            finished = true;
            List<Integer> texts = new ArrayList<>(textBlocks.keySet());
            for (int index : texts) stopText(index, raw, sink);
            List<Integer> reasoning = new ArrayList<>(reasoningBlocks.keySet());
            for (int index : reasoning) stopReasoning(index, raw, sink);
            activeReasoningByItem.clear();
            for (Map.Entry<Integer, FunctionCallState> call : calls.entrySet()) {
                emitCall(call.getKey(), call.getValue(), raw, sink);
            }
            sink.accept(new StreamEvent.MessageDelta(
                new MessageDeltaData(stopReason(response), null), parseUsage(response.get("usage")), raw));
            sink.accept(new StreamEvent.MessageStop(raw));
        }

        @Override
        public void onClosed(Consumer<StreamEvent> sink) {
            if (!finished) throw new ApiException("Responses stream ended before response.completed", 0);
        }

        private static final class FunctionCallState {
            private String id;
            private String name;
            private final StringBuilder arguments = new StringBuilder();
            private boolean emitted;
        }
    }

    private record HostedTool(String id, String name, JsonNode input) {
        private static HostedTool from(JsonNode item) {
            String id = item.path("id").asText("");
            if (StringUtils.isBlank(id)) return null;
            String type = item.path("type").asText("");
            return switch (type) {
                case "web_search_call" -> new HostedTool(id, "web_search", objectOrEmpty(item.get("action")));
                case "web_search_preview_call" -> new HostedTool(id, "web_search_preview", objectOrEmpty(item.get("action")));
                case "file_search_call" -> new HostedTool(id, "file_search",
                    JsonUtils.getMapper().createObjectNode().set("queries", item.path("queries").deepCopy()));
                case "code_interpreter_call" -> {
                    ObjectNode input = JsonUtils.getMapper().createObjectNode();
                    if (item.has("code")) input.set("code", item.get("code"));
                    if (item.has("container_id")) input.set("container_id", item.get("container_id"));
                    yield new HostedTool(id, "code_interpreter", input);
                }
                case "computer_use_call" -> new HostedTool(id, "computer_use", objectOrEmpty(item.get("action")));
                case "image_generation_call" -> new HostedTool(id, "image_generation",
                    JsonUtils.getMapper().createObjectNode());
                case "mcp_call" -> {
                    ObjectNode input = JsonUtils.getMapper().createObjectNode();
                    for (String field : List.of("server_label", "name", "arguments")) {
                        if (item.has(field)) input.set(field, item.get(field));
                    }
                    yield new HostedTool(id, "mcp", input);
                }
                case "local_shell_call" -> new HostedTool(id, "local_shell", objectOrEmpty(item.get("action")));
                default -> null;
            };
        }

        private static JsonNode objectOrEmpty(JsonNode value) {
            return value != null && value.isObject()
                ? value.deepCopy() : JsonUtils.getMapper().createObjectNode();
        }
    }
}
