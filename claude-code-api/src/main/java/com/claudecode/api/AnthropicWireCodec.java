package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class AnthropicWireCodec implements ProtocolCodec {

    @Override
    public CreateMessageRequest parseRequest(ObjectNode root) {
        return parseAnthropicRequest(root);
    }

    @Override
    public ObjectNode encodeRequest(
            CreateMessageRequest request, String targetBaseUrl, boolean forClientRelay) {
        return encodeAnthropicRequest(request);
    }

    @Override
    public ApiMessage parseResponse(String jsonBody, Set<String> offeredTools) throws Exception {
        return parseAnthropicResponse(jsonBody, offeredTools);
    }

    @Override
    public ObjectNode encodeResponse(ApiMessage message) {
        return encodeAnthropicResponse(message);
    }

    @Override
    public EventSourceStreamBridge.EventTranslator newSseTranslator(
            String fallbackModel, Set<String> offeredTools) {
        return new AnthropicSseEventTranslator(offeredTools);
    }

    @Override
    public String encodeSse(List<StreamEvent> events, String fallbackModel) {
        StringBuilder sb = new StringBuilder();
        for (StreamEvent ev : events) {
            StreamEventSseCodec.EncodedEvent enc = StreamEventSseCodec.encode(ev);
            if (enc != null) {
                sb.append("event: ").append(enc.type()).append("\ndata: ").append(enc.data()).append("\n\n");
            }
        }
        return sb.toString();
    }

    @Override
    public ProtocolWireCodec.StreamProjector newStreamProjector(String fallbackModel) {
        return new AnthropicLiveProjector();
    }

    private static CreateMessageRequest parseAnthropicRequest(ObjectNode root) {
        CreateMessageRequest.Builder b = CreateMessageRequest.builder();
        b.model(root.path("model").asText(""));
        b.maxTokens(root.path("max_tokens").asInt(4096));
        b.stream(root.path("stream").asBoolean(false));
        if (root.hasNonNull("temperature")) b.temperature(root.get("temperature").asDouble());
        if (root.hasNonNull("top_p")) b.topP(root.get("top_p").asDouble());
        if (root.hasNonNull("top_k")) b.topK(root.get("top_k").asInt());
        if (root.has("stop_sequences") && root.get("stop_sequences").isArray()) {
            List<String> stops = new ArrayList<>();
            root.get("stop_sequences").forEach(s -> stops.add(s.asText()));
            b.stopSequences(stops);
        }
        String sys = extractAnthropicSystem(root.get("system"));
        if (StringUtils.isNotEmpty(sys)) b.systemPrompt(sys);

        if (root.has("thinking") && root.get("thinking").isObject()) {
            JsonNode t = root.get("thinking");
            String type = t.path("type").asText("");
            if (Strings.CS.equals("disabled", type)) {
                b.thinking(CreateMessageRequest.ThinkingConfig.disabled());
            } else if (Strings.CS.equals("adaptive", type)) {
                b.thinking(CreateMessageRequest.ThinkingConfig.adaptive());
            } else if (Strings.CS.equals("enabled", type)) {
                b.thinking(CreateMessageRequest.ThinkingConfig.enabled(t.path("budget_tokens").asInt(10_000)));
            }
        }
        if (root.has("output_config") && root.get("output_config").isObject()) {
            JsonNode oc = root.get("output_config");
            String effort = oc.path("effort").isTextual() ? oc.path("effort").asText() : null;
            JsonNode fmt = oc.has("format") && oc.get("format").isObject() ? oc.get("format").deepCopy() : null;
            b.outputConfig(new CreateMessageRequest.OutputConfig(effort, fmt));
            if (effort != null) b.effort(effort);
        }

        List<CreateMessageRequest.RequestMessage> msgs = new ArrayList<>();
        if (root.has("messages") && root.get("messages").isArray()) {
            for (JsonNode m : root.get("messages")) {
                String role = m.path("role").asText("user");
                JsonNode c = m.get("content");
                if (c != null && c.isTextual()) {
                    msgs.add(new CreateMessageRequest.RequestMessage(role, c.asText()));
                } else if (c != null && c.isArray()) {
                    List<ContentBlock> blocks = new ArrayList<>();
                    for (JsonNode part : c) {
                        ContentBlock cb = ToolPairingHealer.jsonNodeToBlock(part);
                        if (cb != null) blocks.add(cb);
                    }
                    msgs.add(new CreateMessageRequest.RequestMessage(role, List.copyOf(blocks)));
                }
            }
        }
        b.messages(msgs);

        if (root.has("tools") && root.get("tools").isArray()) {
            List<CreateMessageRequest.ToolDefinition> tools = new ArrayList<>();
            for (JsonNode t : root.get("tools")) {
                if (t.hasNonNull("name")) {
                    tools.add(new CreateMessageRequest.ToolDefinition(
                        t.path("name").asText(),
                        t.path("description").isTextual() ? t.path("description").asText() : null,
                        t.has("input_schema") ? t.get("input_schema").deepCopy()
                            : JsonUtils.getMapper().createObjectNode().put("type", "object")));
                }
            }
            b.tools(tools);
        }
        if (root.has("tool_choice") && root.get("tool_choice").isObject()) {
            JsonNode tc = root.get("tool_choice");
            b.toolChoice(new CreateMessageRequest.ToolChoice(
                tc.path("type").asText("auto"),
                tc.path("name").isTextual() ? tc.path("name").asText() : null));
        }
        return b.build();
    }


    private static ObjectNode encodeAnthropicRequest(CreateMessageRequest request) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("model", request.model() != null ? request.model() : "");
        root.put("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : 4096);
        if (request.stream()) root.put("stream", true);
        if (StringUtils.isNotBlank(request.systemPrompt())) {
            root.put("system", request.systemPrompt());
        }
        if (request.temperature() != null) root.put("temperature", request.temperature());
        if (request.topP() != null) root.put("top_p", request.topP());
        if (request.topK() != null) root.put("top_k", request.topK());
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            root.set("stop_sequences", JsonUtils.getMapper().valueToTree(request.stopSequences()));
        }

        if (request.thinking() != null && Strings.CS.equals("disabled", request.thinking().type())) {
            root.putObject("thinking").put("type", "disabled");
        } else {
            String effort = ReasoningNormalizer.resolveEffectiveEffort(request);
            if (StringUtils.isNotBlank(effort)) {
                Integer rawBudget = request.thinking() != null ? request.thinking().budgetTokens() : null;
                int budget = rawBudget != null && rawBudget > 0
                    ? rawBudget
                    : ReasoningNormalizer.budgetOfEffort(effort);
                ObjectNode t = root.putObject("thinking");
                t.put("type", "enabled");
                t.put("budget_tokens", budget);
                if (root.path("max_tokens").asInt() <= budget) {
                    root.put("max_tokens", budget + 4096);
                }
            }
        }

        if (request.outputConfig() != null && request.outputConfig().format() != null) {
            JsonNode fmt = request.outputConfig().format();
            if (Strings.CS.equals("json_schema", fmt.path("type").asText(""))) {
                ObjectNode oc = root.putObject("output_config");
                ObjectNode outFmt = oc.putObject("format");
                outFmt.put("type", "json_schema");
                outFmt.set("schema", fmt.path("schema").deepCopy());
            }
        }

        root.set("messages", encodeAnthropicMessagesNode(request.messages()));

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (CreateMessageRequest.ToolDefinition tool : request.tools()) {
                ObjectNode t = tools.addObject();
                t.put("name", tool.name());
                if (tool.description() != null) t.put("description", tool.description());
                t.set("input_schema", tool.inputSchema() != null
                    ? tool.inputSchema().deepCopy()
                    : JsonUtils.getMapper().createObjectNode().put("type", "object"));
            }
        }
        if (request.toolChoice() != null) {
            ObjectNode tc = root.putObject("tool_choice");
            String type = request.toolChoice().type();
            tc.put("type", Strings.CS.equals("required", type) ? "any" : type);
            if (Strings.CS.equals("tool", type) && request.toolChoice().name() != null) {
                tc.put("name", request.toolChoice().name());
            }
        }
        return LlmWireBodyFinalizer.finalizeForApi(root);
    }

    static boolean hasContentBlockMessages(List<CreateMessageRequest.RequestMessage> requestMessages) {
        if (requestMessages == null) return false;
        for (CreateMessageRequest.RequestMessage msg : requestMessages) {
            if (msg != null && msg.content() instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof ContentBlock) return true;
                }
            }
        }
        return false;
    }

    static ArrayNode encodeAnthropicMessagesNode(List<CreateMessageRequest.RequestMessage> requestMessages) {
        List<ToolPairingHealer.HealedTurn> turns = ToolPairingHealer.healRequestMessages(requestMessages);
        ArrayNode messages = JsonUtils.getMapper().createArrayNode();
        for (ToolPairingHealer.HealedTurn turn : turns) {
            String role = Strings.CS.equals("assistant", turn.role()) ? "assistant" : "user";
            ArrayNode content = JsonUtils.getMapper().createArrayNode();
            for (ContentBlock block : turn.blocks()) {
                ObjectNode b = encodeAnthropicBlock(block);
                if (b != null) content.add(b);
            }
            if (content.isEmpty()) continue;
            if (!messages.isEmpty()
                    && Strings.CS.equals(role, messages.get(messages.size() - 1).path("role").asText())) {
                ((ArrayNode) messages.get(messages.size() - 1).path("content")).addAll(content);
            } else {
                ObjectNode msg = messages.addObject();
                msg.put("role", role);
                msg.set("content", content);
            }
        }
        return messages;
    }

    private static ObjectNode encodeAnthropicBlock(ContentBlock block) {
        return switch (block) {
            case TextBlock(String text) -> JsonUtils.getMapper().createObjectNode()
                .put("type", "text").put("text", text);
            case ThinkingBlock thinking -> {
                if (StringUtils.isBlank(thinking.signature())) {
                    // Unsigned foreign thinking cannot be sent as an Anthropic thinking block without a signature.
                    yield StringUtils.isEmpty(thinking.thinking()) ? null
                        : JsonUtils.getMapper().createObjectNode()
                            .put("type", "text").put("text", thinking.thinking());
                }
                ObjectNode t = JsonUtils.getMapper().createObjectNode();
                t.put("type", "thinking");
                t.put("thinking", thinking.thinking() == null ? "" : thinking.thinking());
                t.put("signature", thinking.signature());
                yield t;
            }
            case RedactedThinkingBlock(String data) -> JsonUtils.getMapper().createObjectNode()
                .put("type", "redacted_thinking").put("data", data);
            case ImageBlock(JsonNode source) -> {
                ObjectNode img = JsonUtils.getMapper().createObjectNode().put("type", "image");
                img.set("source", source.deepCopy());
                yield img;
            }
            case DocumentBlock(JsonNode source) -> {
                ObjectNode doc = JsonUtils.getMapper().createObjectNode().put("type", "document");
                doc.set("source", source.deepCopy());
                yield doc;
            }
            case ToolUseBlock tu -> {
                GeminiThoughtSignature.UnsignedToolId u = GeminiThoughtSignature.unsignToolId(tu.id());
                ObjectNode node = JsonUtils.getMapper().createObjectNode();
                node.put("type", "tool_use");
                node.put("id", ToolPairingHealer.sanitizeAnthropicToolId(u.id()));
                node.put("name", tu.name());
                node.set("input", tu.input() != null ? tu.input().deepCopy()
                    : JsonUtils.getMapper().createObjectNode());
                yield node;
            }
            case ToolResultBlock tr -> {
                GeminiThoughtSignature.UnsignedToolId u = GeminiThoughtSignature.unsignToolId(tr.toolUseId());
                ObjectNode node = JsonUtils.getMapper().createObjectNode();
                node.put("type", "tool_result");
                node.put("tool_use_id", ToolPairingHealer.sanitizeAnthropicToolId(u.id()));
                if (tr.isError()) node.put("is_error", true);
                ArrayNode inner = node.putArray("content");
                if (tr.content() != null) {
                    for (ContentBlock child : tr.content()) {
                        ObjectNode enc = encodeAnthropicBlock(child);
                        if (enc != null) inner.add(enc);
                    }
                }
                yield node;
            }
            default -> (ObjectNode) JsonUtils.getMapper().valueToTree(block);
        };
    }

    private static ApiMessage parseAnthropicResponse(String jsonBody, Set<String> offeredTools) throws Exception {
        JsonNode root = JsonUtils.getMapper().readTree(jsonBody);
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode c : root.path("content")) {
            ContentBlock cb = ToolPairingHealer.jsonNodeToBlock(c);
            if (cb != null) blocks.add(cb);
        }
        String stopReason = root.path("stop_reason").asText("end_turn");
        TextCallRescue.RescueOutcome rescued = TextCallRescue.rescueBlocks(blocks, offeredTools);
        if (rescued.rescuedTools()) {
            blocks = rescued.blocks();
            stopReason = "tool_use";
        }
        JsonNode u = root.get("usage");
        Usage usage = u == null || u.isNull() ? Usage.EMPTY : new Usage(
            u.path("input_tokens").asLong(0),
            u.path("output_tokens").asLong(0),
            u.path("cache_creation_input_tokens").asLong(0),
            u.path("cache_read_input_tokens").asLong(0));
        return new ApiMessage(
            root.path("id").asText("msg_" + UUID.randomUUID()),
            "message",
            "assistant",
            List.copyOf(blocks),
            root.path("model").asText(""),
            stopReason,
            null,
            usage);
    }

    private static ObjectNode encodeAnthropicResponse(ApiMessage msg) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("id", StringUtils.defaultIfBlank(msg.id(), "msg_" + UUID.randomUUID()));
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", StringUtils.defaultIfBlank(msg.model(), ""));
        ArrayNode content = out.putArray("content");
        for (ContentBlock block : msg.content()) {
            if (block instanceof ThinkingBlock thinking) {
                ObjectNode t = content.addObject();
                t.put("type", "thinking");
                t.put("thinking", thinking.thinking() == null ? "" : thinking.thinking());
                t.put("signature", StringUtils.defaultIfBlank(thinking.signature(), ""));
            } else if (block instanceof ToolUseBlock tu) {
                // Keep base64url __ts<thoughtSignature> intact for downstream Anthropic clients
                // so multi-turn tool loops against a Gemini upstream preserve thoughtSignature.
                ObjectNode node = content.addObject();
                node.put("type", "tool_use");
                node.put("id", ToolPairingHealer.sanitizeAnthropicToolId(tu.id()));
                node.put("name", tu.name());
                node.set("input", tu.input() != null ? tu.input().deepCopy()
                    : JsonUtils.getMapper().createObjectNode());
            } else {
                ObjectNode encoded = encodeAnthropicBlock(block);
                if (encoded != null) content.add(encoded);
            }
        }
        out.put("stop_reason", StringUtils.defaultIfBlank(msg.stopReason(), "end_turn"));
        out.putNull("stop_sequence");
        Usage u = msg.usage() == null ? Usage.EMPTY : msg.usage();
        ObjectNode usage = out.putObject("usage");
        usage.put("input_tokens", u.inputTokens());
        usage.put("output_tokens", u.outputTokens());
        if (u.cacheCreationInputTokens() > 0) {
            usage.put("cache_creation_input_tokens", u.cacheCreationInputTokens());
        }
        if (u.cacheReadInputTokens() > 0) {
            usage.put("cache_read_input_tokens", u.cacheReadInputTokens());
        }
        return out;
    }


    private static String extractAnthropicSystem(JsonNode sys) {
        if (sys == null || sys.isNull()) return "";
        if (sys.isTextual()) return sys.asText();
        if (sys.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : sys) {
                String t = b.path("text").asText("");
                if (StringUtils.isNotEmpty(t)) {
                    if (!sb.isEmpty()) sb.append("\n\n");
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }


    private static final class AnthropicSseEventTranslator implements EventSourceStreamBridge.EventTranslator {
        private final Set<String> offeredTools;
        private final TextCallRescue.StreamBuffer textRescue;
        private final List<StreamEvent> buffered = new ArrayList<>();
        private Integer activeTextIdx;
        private int maxIndex = -1;

        AnthropicSseEventTranslator(Set<String> offeredTools) {
            this.offeredTools = offeredTools;
            this.textRescue = new TextCallRescue.StreamBuffer(offeredTools);
        }

        @Override
        public void translate(String type, String data, Consumer<StreamEvent> sink) {
            StreamEvent ev = decodeAnthropicSseEvent(type, data);
            if (ev == null) return;
            if (offeredTools == null || offeredTools.isEmpty()) {
                sink.accept(ev);
                return;
            }
            switch (ev) {
                case StreamEvent.ContentBlockStart cbs -> {
                    maxIndex = Math.max(maxIndex, cbs.index());
                    if (cbs.contentBlock() instanceof ToolUseBlock) {
                        textRescue.markNativeToolCall();
                    }
                    if (cbs.contentBlock() instanceof TextBlock) {
                        activeTextIdx = cbs.index();
                    }
                    buffered.add(ev);
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    if (cbd.delta() instanceof Delta.TextDelta(String t)) {
                        String immediate = textRescue.onTextDelta(t);
                        if (StringUtils.isNotEmpty(immediate)) {
                            buffered.add(new StreamEvent.ContentBlockDelta(
                                cbd.index(), new Delta.TextDelta(immediate), cbd.rawEvent()));
                        }
                    } else {
                        buffered.add(ev);
                    }
                }
                case StreamEvent.ContentBlockStop cbStop -> {
                    if (activeTextIdx != null && activeTextIdx == cbStop.index()) {
                        TextCallRescue.StreamFlushResult flushed = textRescue.finish();
                        if (StringUtils.isNotEmpty(flushed.trailingText())) {
                            buffered.add(new StreamEvent.ContentBlockDelta(
                                cbStop.index(), new Delta.TextDelta(flushed.trailingText()), null));
                        }
                        buffered.add(cbStop);
                        activeTextIdx = null;
                        for (TextCallRescue.WrittenCall wc : flushed.calls()) {
                            int idx = ++maxIndex;
                            buffered.add(new StreamEvent.ContentBlockStart(
                                idx, new ToolUseBlock(TextCallRescue.nextCallId(), wc.name(),
                                    JsonUtils.getMapper().createObjectNode())));
                            buffered.add(new StreamEvent.ContentBlockDelta(
                                idx, new Delta.InputJsonDelta(wc.args().toString())));
                            buffered.add(new StreamEvent.ContentBlockStop(idx));
                        }
                        rescuingStopReason = !flushed.calls().isEmpty();
                    } else {
                        buffered.add(ev);
                    }
                }
                case StreamEvent.MessageDelta md -> {
                    if (rescuingStopReason) {
                        buffered.add(new StreamEvent.MessageDelta(
                            new MessageDeltaData("tool_use", md.delta().stopSequence()),
                            md.usage(), md.rawEvent()));
                    } else {
                        buffered.add(ev);
                    }
                }
                default -> buffered.add(ev);
            }
        }

        private boolean rescuingStopReason;

        private static StreamEvent decodeAnthropicSseEvent(String type, String data) {
            if (StringUtils.isBlank(data)) return null;
            try {
                JsonNode root = JsonUtils.getMapper().readTree(data);
                String evType = StringUtils.isNotBlank(type) ? type : root.path("type").asText("");
                return switch (evType) {
                    case "message_start" -> new StreamEvent.MessageStart(
                        JsonUtils.getMapper().treeToValue(root.get("message"), ApiMessage.class), root);
                    case "content_block_start" -> new StreamEvent.ContentBlockStart(
                        root.path("index").asInt(0),
                        ToolPairingHealer.jsonNodeToBlock(root.get("content_block")),
                        root);
                    case "content_block_delta" -> new StreamEvent.ContentBlockDelta(
                        root.path("index").asInt(0),
                        JsonUtils.getMapper().treeToValue(root.get("delta"), Delta.class),
                        root);
                    case "content_block_stop" -> new StreamEvent.ContentBlockStop(
                        root.path("index").asInt(0), root);
                    case "message_delta" -> new StreamEvent.MessageDelta(
                        JsonUtils.getMapper().treeToValue(root.get("delta"), MessageDeltaData.class),
                        root.has("usage")
                            ? JsonUtils.getMapper().treeToValue(root.get("usage"), Usage.class)
                            : Usage.EMPTY,
                        root);
                    case "message_stop" -> new StreamEvent.MessageStop(root);
                    default -> null;
                };
            } catch (Exception _) {
                return null;
            }
        }

        @Override
        public void onClosed(Consumer<StreamEvent> sink) {
            buffered.forEach(sink);
            buffered.clear();
        }
    }


    private static final class AnthropicLiveProjector implements ProtocolWireCodec.StreamProjector {
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;

        @Override
        public List<String> onEvent(StreamEvent event) {
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (ms.message().usage() != null) usage = WireCodecSupport.mergeStreamUsage(usage, ms.message().usage());
                }
                case StreamEvent.ContentBlockDelta _ -> contentEmitted = true;
                case StreamEvent.MessageDelta md -> usage = WireCodecSupport.mergeStreamUsage(usage, md.usage());
                default -> { }
            }
            StreamEventSseCodec.EncodedEvent enc = StreamEventSseCodec.encode(event);
            if (enc == null) return List.of();
            return List.of("event: " + enc.type() + "\ndata: " + enc.data() + "\n\n");
        }

        @Override
        public List<String> finish() {
            return List.of();
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
