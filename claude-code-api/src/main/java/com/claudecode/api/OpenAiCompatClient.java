package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.http.HttpCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI-compatible Chat Completions API client.
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

    OpenAiCompatClient(
            ApiConfig.OpenAiConfig config,
            OkHttpClient streamingHttpClient,
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
            ChunkStreamTranslator translator = new ChunkStreamTranslator(
                TextCallRescue.offeredToolNames(request.tools()));
            return EventSourceStreamBridge.connect(
                streamingHttpClient, httpRequest, translator,
                ApiTimeouts.streamConnectTimeout(request), ApiTimeouts.watchdog(),
                request.cancellationRegistrar(), onRequestSubmitted,
                ApiTimeouts.byteWatchdog(ApiConfig.ApiProvider.OPENAI_COMPAT, config.baseUrl()));
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
                    throw new ApiException(
                        "OpenAI API error: " + response.code() + " - " + responseBody,
                        response.code());
                }
                ApiMessage message = parseChatResponseStatic(
                    responseBody, config.model(), TextCallRescue.offeredToolNames(request.tools()));
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
        ObjectNode body = buildChatRequestNode(request, config.model(), streaming, config.baseUrl());
        String requestBody = JsonUtils.getMapper().writeValueAsString(body);
        Request.Builder b = new Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .tag(RetryRequestPolicy.class,
                RetryRequestPolicy.forQuerySource(request.querySource()));
        boolean azure = config.baseUrl() != null && Strings.CS.contains(config.baseUrl(), "azure");
        if (!azure && StringUtils.isNotBlank(config.apiKey())) {
            b.header("Authorization", "Bearer " + config.apiKey());
        }
        if (azure && StringUtils.isNotBlank(config.apiKey())) {
            b.header("api-key", config.apiKey());
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

    static ObjectNode buildChatRequestNode(
            CreateMessageRequest request,
            String defaultModel,
            boolean streaming,
            String baseUrl) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        String effectiveModel = request.model() != null ? request.model() : defaultModel;
        root.put("model", effectiveModel);
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

        String effort = ReasoningNormalizer.resolveExplicitOrBudgetEffort(request);
        if (StringUtils.isNotBlank(effort)) {
            root.put("reasoning_effort", effort);
        }
        if (request.outputConfig() != null && request.outputConfig().format() != null) {
            root.set("response_format", chatResponseFormat(request.outputConfig().format()));
        }

        boolean geminiCompat = GeminiThoughtSignature.isGeminiCompatEndpoint(baseUrl, effectiveModel);
        boolean deepSeekBackfill = ReasoningNormalizer.requiresDeepSeekToolReasoningBackfill(
            baseUrl, effectiveModel, StringUtils.isNotBlank(effort));

        ArrayNode messages = JsonUtils.getMapper().createArrayNode();
        if (StringUtils.isNotEmpty(request.systemPrompt())) {
            ObjectNode systemMsg = JsonUtils.getMapper().createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.systemPrompt()
                .replace("\n\n" + SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n\n", "\n\n")
                .replace(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, ""));
            messages.add(systemMsg);
        }

        List<ToolPairingHealer.HealedTurn> healedTurns =
            ToolPairingHealer.healRequestMessages(request.messages());
        boolean afterTool = false;
        for (ToolPairingHealer.HealedTurn turn : healedTurns) {
            if (Strings.CS.equals("assistant", turn.role())) {
                appendAssistantTurn(messages, turn.blocks(), geminiCompat, deepSeekBackfill, afterTool);
                afterTool = false;
            } else if (Strings.CS.equals("tool_results", turn.role())) {
                appendHealedToolResults(messages, turn.blocks());
                afterTool = true;
            } else {
                appendUserTurn(messages, turn.blocks());
                afterTool = false;
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
                    ObjectNode projected = OpenAiToolSchemaProjection.project(tool.inputSchema());
                    function.set("parameters",
                        VendorSchemaSanitizer.sanitizeToolSchema(projected, baseUrl, effectiveModel));
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

        return LlmWireBodyFinalizer.finalizeForApi(root);
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

    private static void appendAssistantTurn(
            ArrayNode messages,
            List<ContentBlock> blocks,
            boolean geminiCompat,
            boolean deepSeekBackfill,
            boolean afterTool) {
        ObjectNode node = messages.addObject();
        node.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ArrayNode toolCalls = JsonUtils.getMapper().createArrayNode();

        boolean stepSigned = false;
        for (ContentBlock b : blocks) {
            if (b instanceof ToolUseBlock tu) {
                GeminiThoughtSignature.UnsignedToolId u = GeminiThoughtSignature.unsignToolId(tu.id());
                if (StringUtils.isNotEmpty(u.signature())) {
                    stepSigned = true;
                    break;
                }
            }
        }

        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock(String s) -> appendJoined(text, s);
                case ThinkingBlock t -> {
                    if (StringUtils.isNotEmpty(t.thinking())) {
                        appendJoined(reasoning, t.thinking());
                    }
                }
                case RedactedThinkingBlock _ -> {
                    // Safely omit Claude-sealed redacted thinking blocks on Chat Completions.
                }
                case ToolUseBlock tu -> {
                    GeminiThoughtSignature.UnsignedToolId unsigned =
                        GeminiThoughtSignature.unsignToolId(tu.id());
                    ObjectNode call = JsonUtils.getMapper().createObjectNode();
                    call.put("id", unsigned.id());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", tu.name());
                    fn.put("arguments", tu.input() == null || tu.input().isNull()
                        ? "{}" : tu.input().toString());
                    if (geminiCompat) {
                        if (StringUtils.isNotEmpty(unsigned.signature())) {
                            call.set("extra_content",
                                GeminiThoughtSignature.buildExtraSignature(unsigned.signature()));
                        } else if (!stepSigned) {
                            call.set("extra_content",
                                GeminiThoughtSignature.buildExtraSignature(
                                    GeminiThoughtSignature.SKIP_SIGNATURE));
                        }
                    }
                    toolCalls.add(call);
                }
                default -> { }
            }
        }
        if (text.isEmpty()) node.putNull("content");
        else node.put("content", text.toString());
        if (!reasoning.isEmpty()) {
            node.put("reasoning_content", reasoning.toString());
        } else if (deepSeekBackfill && (!toolCalls.isEmpty() || afterTool)) {
            node.put("reasoning_content", "");
        }
        if (!toolCalls.isEmpty()) node.set("tool_calls", toolCalls);
    }

    private static void appendHealedToolResults(ArrayNode messages, List<ContentBlock> blocks) {
        List<ImageBlock> pendingImages = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock result)) continue;
            GeminiThoughtSignature.UnsignedToolId unsigned =
                GeminiThoughtSignature.unsignToolId(result.toolUseId());
            ObjectNode tool = messages.addObject();
            tool.put("role", "tool");
            tool.put("tool_call_id", unsigned.id());
            tool.put("content", OpenAiWireSupport.textContent(result.content()));
            if (result.content() != null) {
                for (ContentBlock child : result.content()) {
                    if (child instanceof ImageBlock img) pendingImages.add(img);
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

    private static void appendUserTurn(ArrayNode messages, List<ContentBlock> blocks) {
        ObjectNode node = messages.addObject();
        node.put("role", "user");
        if (blocks.size() == 1 && blocks.getFirst() instanceof TextBlock(String s)) {
            node.put("content", s);
            return;
        }
        ArrayNode content = node.putArray("content");
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock(String s) -> content.add(OpenAiWireSupport.chatText(s));
                case ImageBlock(JsonNode source) -> content.add(OpenAiWireSupport.chatImage(source));
                case DocumentBlock(JsonNode source) -> content.add(OpenAiWireSupport.chatDocumentNote(source));
                default -> { }
            }
        }
        if (content.isEmpty()) node.put("content", "");
    }

    private static void appendJoined(StringBuilder target, String value) {
        if (!target.isEmpty()) target.append('\n');
        target.append(value);
    }

    private static String endpoint(String baseUrl, String path) {
        String base = StringUtils.isBlank(baseUrl) ? "https://api.openai.com/v1" : baseUrl;
        while (Strings.CS.endsWith(base, "/")) base = base.substring(0, base.length() - 1);
        return Strings.CS.endsWith(base, "/" + path) ? base : base + "/" + path;
    }

    /**
     * Stateful per-stream chunk accumulator supporting reasoning dialects, {@code <think>} tags,
     * Gemini {@code extra_content} thought signatures, and pseudo-text tool-call rescue.
     */
    static final class ChunkStreamTranslator implements EventSourceStreamBridge.EventTranslator {
        private final List<ToolUseBlock> toolCalls = new ArrayList<>();
        private final List<String> toolCallArgs = new ArrayList<>();
        private final List<String> toolCallNames = new ArrayList<>();
        private final List<String> toolCallIds = new ArrayList<>();
        private final List<String> toolCallSigs = new ArrayList<>();
        private final ReasoningNormalizer.ThinkStreamFilter thinkFilter =
            new ReasoningNormalizer.ThinkStreamFilter();
        private final TextCallRescue.StreamBuffer textRescue;
        private Integer textIndex;
        private Integer reasoningIndex;
        private int nextBlockIndex;
        private String messageId;
        private String model;
        private String finalStopReason;
        private Usage latestUsage = Usage.EMPTY;
        private boolean finished;

        ChunkStreamTranslator(Set<String> offeredTools) {
            this.textRescue = new TextCallRescue.StreamBuffer(offeredTools);
        }

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
                latestUsage = ReasoningNormalizer.parseChatUsage(usage);
            }
            JsonNode choices = chunk.get("choices");
            if (choices == null || choices.isNull()) return;

            for (JsonNode choice : choices) {
                JsonNode delta = choice.get("delta");
                if (delta != null) {
                    String explicitReasoning = ReasoningNormalizer.extractReasoningField(delta);
                    if (StringUtils.isNotEmpty(explicitReasoning)) {
                        emitReasoningDelta(explicitReasoning, chunk, sink);
                    }

                    if (delta.hasNonNull("content")) {
                        String content = delta.get("content").asText();
                        if (StringUtils.isNotEmpty(content)) {
                            thinkFilter.feed(
                                content,
                                thinkChunk -> emitReasoningDelta(thinkChunk, chunk, sink),
                                visibleChunk -> handleVisibleContent(visibleChunk, chunk, sink));
                        }
                    }

                    if (delta.hasNonNull("tool_calls")) {
                        textRescue.markNativeToolCall();
                        JsonNode toolCallsDelta = delta.get("tool_calls");
                        for (JsonNode tc : toolCallsDelta) {
                            int index = tc.hasNonNull("index") ? tc.get("index").asInt() : toolCalls.size();
                            while (toolCalls.size() <= index) {
                                toolCalls.add(null);
                                toolCallArgs.add("");
                                toolCallNames.add(null);
                                toolCallIds.add(null);
                                toolCallSigs.add("");
                            }

                            if (tc.hasNonNull("id")) toolCallIds.set(index, tc.get("id").asText());
                            String extraSig = GeminiThoughtSignature.extractExtraSignature(tc.get("extra_content"));
                            if (StringUtils.isNotEmpty(extraSig)) {
                                toolCallSigs.set(index, extraSig);
                            }

                            if (tc.hasNonNull("function")) {
                                JsonNode fn = tc.get("function");
                                if (fn.hasNonNull("name")) {
                                    String name = fn.get("name").asText();
                                    toolCallNames.set(index, name);
                                    String id = resolveSlotId(index);
                                    toolCalls.set(index,
                                        new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode()));
                                }
                                if (fn.hasNonNull("arguments")) {
                                    String args = fn.get("arguments").asText();
                                    toolCallArgs.set(index, toolCallArgs.get(index) + args);
                                    String name = toolCallNames.get(index);
                                    if (name != null) {
                                        String id = resolveSlotId(index);
                                        try {
                                            JsonNode argsNode = JsonUtils.getMapper().readTree(toolCallArgs.get(index));
                                            toolCalls.set(index, new ToolUseBlock(id, name, argsNode));
                                        } catch (Exception _) {
                                            toolCalls.set(index, new ToolUseBlock(
                                                id, name, JsonUtils.getMapper().createObjectNode()));
                                        }
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

        private String resolveSlotId(int index) {
            String rawId = toolCallIds.get(index);
            if (rawId == null) {
                rawId = toolCalls.get(index) != null
                    ? GeminiThoughtSignature.unsignToolId(toolCalls.get(index).id()).id()
                    : "toolu_" + UUID.randomUUID().toString().substring(0, 8);
            }
            return GeminiThoughtSignature.signToolId(rawId, toolCallSigs.get(index));
        }

        private void emitReasoningDelta(String text, JsonNode chunk, Consumer<StreamEvent> sink) {
            if (StringUtils.isEmpty(text)) return;
            closeText(chunk, sink);
            if (reasoningIndex == null) {
                reasoningIndex = nextBlockIndex++;
                sink.accept(new StreamEvent.ContentBlockStart(
                    reasoningIndex, new ThinkingBlock("", null), chunk));
            }
            sink.accept(new StreamEvent.ContentBlockDelta(
                reasoningIndex, new Delta.ThinkingDelta(text), chunk));
        }

        private void handleVisibleContent(String visible, JsonNode chunk, Consumer<StreamEvent> sink) {
            if (StringUtils.isEmpty(visible)) return;
            closeReasoning(chunk, sink);
            String immediate = textRescue.onTextDelta(visible);
            emitTextDelta(immediate, chunk, sink);
        }

        private void emitTextDelta(String text, JsonNode chunk, Consumer<StreamEvent> sink) {
            if (StringUtils.isEmpty(text)) return;
            if (textIndex == null) {
                textIndex = nextBlockIndex++;
                sink.accept(new StreamEvent.ContentBlockStart(textIndex, new TextBlock(""), chunk));
            }
            sink.accept(new StreamEvent.ContentBlockDelta(
                textIndex, new Delta.TextDelta(text), chunk));
        }

        private void ensureMessageStarted(JsonNode chunk, Consumer<StreamEvent> sink) {
            if (messageId != null) return;
            messageId = chunk != null ? chunk.path("id").asText("chatcmpl_" + UUID.randomUUID())
                : "chatcmpl_" + UUID.randomUUID();
            model = chunk != null ? chunk.path("model").asText("") : "";
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

            thinkFilter.finish(
                thinkTail -> emitReasoningDelta(thinkTail, null, sink),
                visibleTail -> handleVisibleContent(visibleTail, null, sink));
            closeReasoning(null, sink);

            TextCallRescue.StreamFlushResult rescued = textRescue.finish();
            if (StringUtils.isNotEmpty(rescued.trailingText())) {
                emitTextDelta(rescued.trailingText(), null, sink);
            }
            closeText(null, sink);

            if (!toolCalls.isEmpty() && finalStopReason != null) {
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolUseBlock tool = toolCalls.get(i);
                    if (tool == null) continue;
                    int blockIndex = nextBlockIndex++;
                    String finalId = resolveSlotId(i);
                    sink.accept(new StreamEvent.ContentBlockStart(blockIndex,
                        new ToolUseBlock(finalId, tool.name(), JsonUtils.getMapper().createObjectNode())));
                    String arguments = StringUtils.isBlank(toolCallArgs.get(i)) ? "{}" : toolCallArgs.get(i);
                    requireJsonArguments(arguments, tool.name());
                    sink.accept(new StreamEvent.ContentBlockDelta(blockIndex,
                        new Delta.InputJsonDelta(arguments)));
                    sink.accept(new StreamEvent.ContentBlockStop(blockIndex));
                }
            } else if (!rescued.calls().isEmpty()) {
                for (TextCallRescue.WrittenCall wc : rescued.calls()) {
                    int blockIndex = nextBlockIndex++;
                    sink.accept(new StreamEvent.ContentBlockStart(blockIndex,
                        new ToolUseBlock(TextCallRescue.nextCallId(), wc.name(),
                            JsonUtils.getMapper().createObjectNode())));
                    sink.accept(new StreamEvent.ContentBlockDelta(blockIndex,
                        new Delta.InputJsonDelta(wc.args().toString())));
                    sink.accept(new StreamEvent.ContentBlockStop(blockIndex));
                }
                finalStopReason = "tool_use";
            }

            sink.accept(new StreamEvent.MessageDelta(
                new MessageDeltaData(finalStopReason != null ? finalStopReason : "end_turn", null),
                latestUsage));
            sink.accept(new StreamEvent.MessageStop());
        }
    }

    static ApiMessage parseChatResponseStatic(
            String responseBody, String fallbackModel, Set<String> offeredTools) throws Exception {
        JsonNode root = JsonUtils.getMapper().readTree(responseBody);

        String id = root.hasNonNull("id") ? root.get("id").asText() : "chatcmpl_" + UUID.randomUUID();
        String model = root.hasNonNull("model") ? root.get("model").asText() : fallbackModel;
        JsonNode choices = root.get("choices");
        String stopReason = "stop";
        List<ContentBlock> contentBlocks = new ArrayList<>();
        boolean sawStructuredToolCall = false;

        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode choice = choices.get(0);
            JsonNode message = choice.get("message");
            if (message != null) {
                String explicitReasoning = ReasoningNormalizer.extractReasoningField(message);
                if (StringUtils.isNotEmpty(explicitReasoning)) {
                    contentBlocks.add(new ThinkingBlock(explicitReasoning, null));
                }

                if (message.hasNonNull("content")) {
                    String content = message.get("content").asText();
                    if (StringUtils.isNotEmpty(content)) {
                        ReasoningNormalizer.SplitThinkResult split = ReasoningNormalizer.splitThinkTags(content);
                        if (StringUtils.isNotEmpty(split.thinking()) && StringUtils.isEmpty(explicitReasoning)) {
                            contentBlocks.add(new ThinkingBlock(split.thinking(), null));
                        }
                        if (StringUtils.isNotEmpty(split.content())) {
                            contentBlocks.add(new TextBlock(split.content()));
                        }
                    }
                }

                if (message.hasNonNull("tool_calls")) {
                    for (JsonNode tc : message.get("tool_calls")) {
                        if (!tc.hasNonNull("function")) {
                            throw new ApiException(
                                "OpenAI Chat tool call is missing its function object", 0);
                        }
                        JsonNode function = tc.get("function");
                        if (!function.hasNonNull("name")) {
                            throw new ApiException(
                                "OpenAI Chat tool call is missing its function name", 0);
                        }
                        String name = function.get("name").asText();
                        String args = function.path("arguments").asText("");
                        String rawId = tc.hasNonNull("id") ? tc.get("id").asText()
                            : "toolu_" + UUID.randomUUID().toString().substring(0, 8);
                        String extraSig = GeminiThoughtSignature.extractExtraSignature(tc.get("extra_content"));
                        String toolId = GeminiThoughtSignature.signToolId(rawId, extraSig);

                        JsonNode argsNode = requireJsonArguments(args, name);
                        contentBlocks.add(new ToolUseBlock(toolId, name, argsNode));
                        sawStructuredToolCall = true;
                    }
                }

                JsonNode finish = choice.get("finish_reason");
                if (finish != null && !finish.isNull()) {
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

        if (!sawStructuredToolCall) {
            TextCallRescue.RescueOutcome rescued =
                TextCallRescue.rescueBlocks(contentBlocks, offeredTools);
            if (rescued.rescuedTools()) {
                contentBlocks = rescued.blocks();
                stopReason = "tool_use";
            }
        }

        Usage tokenUsage = ReasoningNormalizer.parseChatUsage(root.get("usage"));
        return new ApiMessage(id, "message", "assistant", contentBlocks, model,
            stopReason, null, tokenUsage);
    }

    private static JsonNode requireJsonArguments(String arguments, String toolName) {
        try {
            return JsonUtils.getMapper().readTree(StringUtils.isBlank(arguments) ? "{}" : arguments);
        } catch (Exception e) {
            throw new ApiException("Invalid JSON input for OpenAI Chat tool call " + toolName, 0, e);
        }
    }
}
