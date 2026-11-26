package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class OpenAiChatWireCodec implements ProtocolCodec {

    @Override
    public CreateMessageRequest parseRequest(ObjectNode root) {
        return parseChatRequest(root);
    }

    @Override
    public ObjectNode encodeRequest(
            CreateMessageRequest request, String targetBaseUrl, boolean forClientRelay) {
        return OpenAiCompatClient.buildChatRequestNode(
            request, request.model(), request.stream(), targetBaseUrl);
    }

    @Override
    public ApiMessage parseResponse(String jsonBody, Set<String> offeredTools) throws Exception {
        return OpenAiCompatClient.parseChatResponseStatic(jsonBody, "", offeredTools);
    }

    @Override
    public ObjectNode encodeResponse(ApiMessage message) {
        return encodeChatResponse(message);
    }

    @Override
    public EventSourceStreamBridge.EventTranslator newSseTranslator(
            String fallbackModel, Set<String> offeredTools) {
        return new OpenAiCompatClient.ChunkStreamTranslator(offeredTools);
    }

    @Override
    public String encodeSse(List<StreamEvent> events, String fallbackModel) {
        return encodeChatSse(events, fallbackModel);
    }

    @Override
    public ProtocolWireCodec.StreamProjector newStreamProjector(String fallbackModel) {
        return new ChatLiveProjector(fallbackModel);
    }

    private static CreateMessageRequest parseChatRequest(ObjectNode root) {
        CreateMessageRequest.Builder b = CreateMessageRequest.builder();
        b.model(root.path("model").asText(""));
        int maxTok = root.hasNonNull("max_completion_tokens")
            ? root.path("max_completion_tokens").asInt(4096)
            : root.path("max_tokens").asInt(4096);
        b.maxTokens(maxTok);
        b.stream(root.path("stream").asBoolean(false));
        if (root.hasNonNull("temperature")) b.temperature(root.get("temperature").asDouble());
        if (root.hasNonNull("top_p")) b.topP(root.get("top_p").asDouble());
        if (root.has("stop")) {
            JsonNode stop = root.get("stop");
            if (stop.isTextual()) b.stopSequences(List.of(stop.asText()));
            else if (stop.isArray()) {
                List<String> stops = new ArrayList<>();
                stop.forEach(s -> stops.add(s.asText()));
                b.stopSequences(stops);
            }
        }
        String effort = root.path("reasoning_effort").asText("");
        if (Strings.CS.equals("none", effort.toLowerCase(Locale.ROOT))) {
            b.thinking(CreateMessageRequest.ThinkingConfig.disabled());
        } else if (StringUtils.isNotBlank(effort)) {
            String norm = ReasoningNormalizer.normalizeEffort(effort);
            if (StringUtils.isNotBlank(norm)) {
                b.effort(norm);
                b.thinking(CreateMessageRequest.ThinkingConfig.enabled(
                    ReasoningNormalizer.budgetOfEffort(norm)));
            }
        }

        JsonNode fmt = WireCodecSupport.parseOpenAiFormat(root.get("response_format"));
        if (fmt != null) {
            b.outputConfig(new CreateMessageRequest.OutputConfig(
                StringUtils.isNotBlank(effort) ? ReasoningNormalizer.normalizeEffort(effort) : null, fmt));
        }

        StringBuilder system = new StringBuilder();
        List<CreateMessageRequest.RequestMessage> msgs = new ArrayList<>();
        if (root.has("messages") && root.get("messages").isArray()) {
            for (JsonNode m : root.get("messages")) {
                String role = m.path("role").asText("user");
                if (Strings.CS.equals("system", role) || Strings.CS.equals("developer", role)) {
                    String sysPart = extractChatContentText(m.get("content"));
                    if (StringUtils.isNotEmpty(sysPart)) {
                        if (!system.isEmpty()) system.append("\n\n");
                        system.append(sysPart);
                    }
                    continue;
                }
                if (Strings.CS.equals("tool", role)) {
                    String callId = m.path("tool_call_id").asText("");
                    String out = extractChatContentText(m.get("content"));
                    msgs.add(new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock(callId, List.of(new TextBlock(out)),
                            m.path("is_error").asBoolean(false)))));
                    continue;
                }
                if (Strings.CS.equals("assistant", role)) {
                    List<ContentBlock> blocks = new ArrayList<>();
                    String reasoning = ReasoningNormalizer.extractReasoningField(m);
                    if (StringUtils.isNotEmpty(reasoning)) {
                        blocks.add(new ThinkingBlock(reasoning, null));
                    }
                    String text = extractChatContentText(m.get("content"));
                    if (StringUtils.isNotEmpty(text)) {
                        ReasoningNormalizer.SplitThinkResult split = ReasoningNormalizer.splitThinkTags(text);
                        if (StringUtils.isNotEmpty(split.thinking()) && StringUtils.isEmpty(reasoning)) {
                            blocks.add(new ThinkingBlock(split.thinking(), null));
                        }
                        if (StringUtils.isNotEmpty(split.content())) {
                            blocks.add(new TextBlock(split.content()));
                        }
                    }
                    if (m.has("tool_calls") && m.get("tool_calls").isArray()) {
                        for (JsonNode tc : m.get("tool_calls")) {
                            JsonNode fn = tc.path("function");
                            String rawId = tc.path("id").asText("");
                            String extraSig = GeminiThoughtSignature.extractExtraSignature(tc.get("extra_content"));
                            String finalId = StringUtils.isNotEmpty(extraSig)
                                ? GeminiThoughtSignature.signToolId(
                                    GeminiThoughtSignature.unsignToolId(rawId).id(), extraSig)
                                : rawId;
                            JsonNode args = WireCodecSupport.parseJsonObjectOrEmpty(fn.path("arguments").asText("{}"));
                            blocks.add(new ToolUseBlock(finalId, fn.path("name").asText(""), args));
                        }
                    }
                    msgs.add(new CreateMessageRequest.RequestMessage("assistant", List.copyOf(blocks)));
                    continue;
                }
                // User message
                msgs.add(new CreateMessageRequest.RequestMessage("user", parseChatUserBlocks(m.get("content"))));
            }
        }
        if (!system.isEmpty()) b.systemPrompt(system.toString());
        b.messages(msgs);

        if (root.has("tools") && root.get("tools").isArray()) {
            List<CreateMessageRequest.ToolDefinition> tools = new ArrayList<>();
            for (JsonNode t : root.get("tools")) {
                JsonNode fn = t.has("function") ? t.get("function") : t;
                if (fn.hasNonNull("name")) {
                    tools.add(new CreateMessageRequest.ToolDefinition(
                        fn.path("name").asText(),
                        fn.path("description").isTextual() ? fn.path("description").asText() : null,
                        fn.has("parameters") ? fn.get("parameters").deepCopy()
                            : JsonUtils.getMapper().createObjectNode().put("type", "object"),
                        fn.has("strict") && fn.get("strict").isBoolean() ? fn.get("strict").asBoolean() : null));
                }
            }
            b.tools(tools);
        }
        if (root.has("tool_choice")) {
            JsonNode tc = root.get("tool_choice");
            if (tc.isTextual()) {
                String mode = tc.asText();
                b.toolChoice(new CreateMessageRequest.ToolChoice(
                    Strings.CS.equals("required", mode) ? "any" : mode, null));
            } else if (tc.isObject()) {
                String name = tc.path("function").path("name").asText(tc.path("name").asText(null));
                b.toolChoice(new CreateMessageRequest.ToolChoice("tool", name));
            }
        }
        return b.build();
    }


    private static ObjectNode encodeChatResponse(ApiMessage msg) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("id", StringUtils.defaultIfBlank(msg.id(), "chatcmpl_" + UUID.randomUUID()));
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", StringUtils.defaultIfBlank(msg.model(), ""));
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ArrayNode toolCalls = JsonUtils.getMapper().createArrayNode();
        int tcIdx = 0;
        for (ContentBlock block : msg.content()) {
            switch (block) {
                case ThinkingBlock t -> {
                    if (StringUtils.isNotEmpty(t.thinking())) {
                        if (!reasoning.isEmpty()) reasoning.append('\n');
                        reasoning.append(t.thinking());
                    }
                }
                case TextBlock(String s) -> text.append(s);
                case ToolUseBlock tu -> {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("index", tcIdx++);
                    tc.put("id", tu.id());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", tu.name());
                    fn.put("arguments", tu.input() == null ? "{}" : tu.input().toString());
                }
                default -> { }
            }
        }
        if (text.isEmpty() && !toolCalls.isEmpty()) message.putNull("content");
        else message.put("content", text.toString());
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning.toString());
        if (!toolCalls.isEmpty()) message.set("tool_calls", toolCalls);

        String finish = !toolCalls.isEmpty() || Strings.CS.equals("tool_use", msg.stopReason())
            ? "tool_calls"
            : Strings.CS.equals("max_tokens", msg.stopReason()) ? "length" : "stop";
        choice.put("finish_reason", finish);

        Usage u = msg.usage() == null ? Usage.EMPTY : msg.usage();
        long prompt = u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens();
        ObjectNode usage = out.putObject("usage");
        usage.put("prompt_tokens", prompt);
        usage.put("completion_tokens", u.outputTokens());
        usage.put("total_tokens", prompt + u.outputTokens());
        if (u.cacheReadInputTokens() > 0) {
            usage.putObject("prompt_tokens_details").put("cached_tokens", u.cacheReadInputTokens());
        }
        return out;
    }


    private static String encodeChatSse(List<StreamEvent> events, String fallbackModel) {
        StringBuilder sb = new StringBuilder();
        String id = "chatcmpl_" + UUID.randomUUID().toString().substring(0, 12);
        String model = StringUtils.defaultIfBlank(fallbackModel, "");
        long created = Instant.now().getEpochSecond();
        Map<Integer, Integer> toolBlockToCallIdx = new LinkedHashMap<>();
        int nextToolIdx = 0;
        boolean roleSent = false;

        for (StreamEvent ev : events) {
            switch (ev) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) id = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                    delta.put("role", "assistant");
                    delta.put("content", "");
                    appendChatChunk(sb, id, model, created, delta, null, null);
                    roleSent = true;
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    if (!roleSent) {
                        ObjectNode d = JsonUtils.getMapper().createObjectNode().put("role", "assistant");
                        appendChatChunk(sb, id, model, created, d, null, null);
                        roleSent = true;
                    }
                    if (cbs.contentBlock() instanceof ToolUseBlock tu) {
                        int callIdx = nextToolIdx++;
                        toolBlockToCallIdx.put(cbs.index(), callIdx);
                        ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                        ObjectNode tc = delta.putArray("tool_calls").addObject();
                        tc.put("index", callIdx);
                        tc.put("id", tu.id());
                        tc.put("type", "function");
                        tc.putObject("function").put("name", tu.name()).put("arguments", "");
                        appendChatChunk(sb, id, model, created, delta, null, null);
                    }
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                    switch (cbd.delta()) {
                        case Delta.TextDelta(String text) -> {
                            delta.put("content", text);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        case Delta.ThinkingDelta(String thinking) -> {
                            delta.put("reasoning_content", thinking);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        case Delta.InputJsonDelta(String partialJson) -> {
                            int callIdx = toolBlockToCallIdx.getOrDefault(cbd.index(), 0);
                            ObjectNode tc = delta.putArray("tool_calls").addObject();
                            tc.put("index", callIdx);
                            tc.putObject("function").put("arguments", partialJson);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        default -> { }
                    }
                }
                case StreamEvent.MessageDelta md -> {
                    String reason = md.delta().stopReason();
                    String finish = Strings.CS.equals("tool_use", reason) ? "tool_calls"
                        : Strings.CS.equals("max_tokens", reason) ? "length" : "stop";
                    appendChatChunk(sb, id, model, created,
                        JsonUtils.getMapper().createObjectNode(), finish, md.usage());
                }
                default -> { }
            }
        }
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    private static void appendChatChunk(
            StringBuilder sb, String id, String model, long created,
            ObjectNode delta, String finishReason, Usage usage) {
        ObjectNode chunk = JsonUtils.getMapper().createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) choice.put("finish_reason", finishReason);
        else choice.putNull("finish_reason");
        if (usage != null) {
            long prompt = usage.inputTokens() + usage.cacheReadInputTokens() + usage.cacheCreationInputTokens();
            ObjectNode u = chunk.putObject("usage");
            u.put("prompt_tokens", prompt);
            u.put("completion_tokens", usage.outputTokens());
            u.put("total_tokens", prompt + usage.outputTokens());
            if (usage.cacheReadInputTokens() > 0) {
                u.putObject("prompt_tokens_details").put("cached_tokens", usage.cacheReadInputTokens());
            }
        }
        sb.append("data: ").append(chunk).append("\n\n");
    }


    private static String extractChatContentText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (Strings.CS.equals("text", part.path("type").asText(""))) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static List<ContentBlock> parseChatUserBlocks(JsonNode content) {
        if (content == null || content.isNull()) return List.of();
        if (content.isTextual()) return List.of(new TextBlock(content.asText()));
        if (!content.isArray()) return List.of();
        List<ContentBlock> out = new ArrayList<>();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (Strings.CS.equals("text", type)) {
                out.add(new TextBlock(part.path("text").asText("")));
            } else if (Strings.CS.equals("image_url", type)) {
                String url = part.path("image_url").isTextual()
                    ? part.path("image_url").asText()
                    : part.path("image_url").path("url").asText("");
                ContentBlock img = WireCodecSupport.dataOrRemoteUrlToBlock(url, true);
                if (img != null) out.add(img);
            } else if (Strings.CS.equals("file", type)) {
                JsonNode f = part.path("file");
                String data = f.path("file_data").asText("");
                ContentBlock doc = WireCodecSupport.dataOrRemoteUrlToBlock(data, false);
                if (doc != null) out.add(doc);
            }
        }
        return List.copyOf(out);
    }


    private static final class ChatLiveProjector implements ProtocolWireCodec.StreamProjector {
        private String id = "chatcmpl_" + UUID.randomUUID().toString().substring(0, 12);
        private String model;
        private final long created = Instant.now().getEpochSecond();
        private final Map<Integer, Integer> toolBlockToCallIdx = new LinkedHashMap<>();
        private int nextToolIdx;
        private boolean roleSent;
        private boolean doneSent;
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;

        ChatLiveProjector(String fallbackModel) {
            this.model = StringUtils.defaultIfBlank(fallbackModel, "");
        }

        @Override
        public List<String> onEvent(StreamEvent event) {
            StringBuilder sb = new StringBuilder();
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) id = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    if (ms.message().usage() != null) usage = WireCodecSupport.mergeStreamUsage(usage, ms.message().usage());
                    ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                    delta.put("role", "assistant");
                    delta.put("content", "");
                    appendChatChunk(sb, id, model, created, delta, null, null);
                    roleSent = true;
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    if (!roleSent) {
                        ObjectNode d = JsonUtils.getMapper().createObjectNode().put("role", "assistant");
                        appendChatChunk(sb, id, model, created, d, null, null);
                        roleSent = true;
                    }
                    if (cbs.contentBlock() instanceof ToolUseBlock tu) {
                        contentEmitted = true;
                        int callIdx = nextToolIdx++;
                        toolBlockToCallIdx.put(cbs.index(), callIdx);
                        ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                        ObjectNode tc = delta.putArray("tool_calls").addObject();
                        tc.put("index", callIdx);
                        tc.put("id", tu.id());
                        tc.put("type", "function");
                        tc.putObject("function").put("name", tu.name()).put("arguments", "");
                        appendChatChunk(sb, id, model, created, delta, null, null);
                    }
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    contentEmitted = true;
                    ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                    switch (cbd.delta()) {
                        case Delta.TextDelta(String text) -> {
                            delta.put("content", text);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        case Delta.ThinkingDelta(String thinking) -> {
                            delta.put("reasoning_content", thinking);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        case Delta.InputJsonDelta(String partialJson) -> {
                            int callIdx = toolBlockToCallIdx.getOrDefault(cbd.index(), 0);
                            ObjectNode tc = delta.putArray("tool_calls").addObject();
                            tc.put("index", callIdx);
                            tc.putObject("function").put("arguments", partialJson);
                            appendChatChunk(sb, id, model, created, delta, null, null);
                        }
                        default -> { }
                    }
                }
                case StreamEvent.MessageDelta md -> {
                    usage = WireCodecSupport.mergeStreamUsage(usage, md.usage());
                    String reason = md.delta().stopReason();
                    String finish = Strings.CS.equals("tool_use", reason) ? "tool_calls"
                        : Strings.CS.equals("max_tokens", reason) ? "length" : "stop";
                    appendChatChunk(sb, id, model, created,
                        JsonUtils.getMapper().createObjectNode(), finish, usage);
                }
                case StreamEvent.MessageStop _ -> {
                    if (!doneSent) {
                        doneSent = true;
                        sb.append("data: [DONE]\n\n");
                    }
                }
                default -> { }
            }
            return sb.isEmpty() ? List.of() : List.of(sb.toString());
        }

        @Override
        public List<String> finish() {
            if (doneSent) return List.of();
            doneSent = true;
            return List.of("data: [DONE]\n\n");
        }

        @Override
        public Usage usage() {
            return usage;
        }

        @Override
        public boolean emittedContent() {
            return contentEmitted;
        }
    }

}
