package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class GeminiWireCodec implements ProtocolCodec {

    @Override
    public CreateMessageRequest parseRequest(ObjectNode root) {
        return parseGeminiRequest((ObjectNode) camelCaseGeminiBody(root));
    }

    @Override
    public ObjectNode encodeRequest(
            CreateMessageRequest request, String targetBaseUrl, boolean forClientRelay) {
        return GeminiClient.buildGeminiRequestBody(
            request, GeminiClient.cleanModelPathId(request.model()));
    }

    @Override
    public ApiMessage parseResponse(String jsonBody, Set<String> offeredTools) throws Exception {
        return GeminiClient.parseUnaryResponse(jsonBody, "", offeredTools);
    }

    @Override
    public ObjectNode encodeResponse(ApiMessage message) {
        return encodeGeminiResponse(message);
    }

    @Override
    public EventSourceStreamBridge.EventTranslator newSseTranslator(
            String fallbackModel, Set<String> offeredTools) {
        return new GeminiSseAdapter(fallbackModel, offeredTools);
    }

    @Override
    public String encodeSse(List<StreamEvent> events, String fallbackModel) {
        return encodeGeminiSse(events, fallbackModel);
    }

    @Override
    public ProtocolWireCodec.StreamProjector newStreamProjector(String fallbackModel) {
        return new GeminiLiveProjector(fallbackModel);
    }

    @Override
    public String encodeCountTokensResponse(long inputTokens) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("totalTokens", inputTokens);
        return out.toString();
    }

    private static final Set<String> GEMINI_CALLER_OWN_KEYS = Set.of(
        "args", "response", "parameters", "parametersJsonSchema",
        "responseSchema", "responseJsonSchema", "labels"
    );

    private static CreateMessageRequest parseGeminiRequest(ObjectNode root) {
        CreateMessageRequest.Builder b = CreateMessageRequest.builder();
        b.model(GeminiClient.cleanModelPathId(root.path("model").asText("")));
        b.stream(root.path("stream").asBoolean(false));

        String sys = extractGeminiSystemText(root.get("systemInstruction"));
        JsonNode gc = root.get("generationConfig");
        if (gc != null && gc.isObject()) {
            if (gc.hasNonNull("maxOutputTokens")) b.maxTokens(gc.get("maxOutputTokens").asInt(4096));
            if (gc.hasNonNull("temperature")) b.temperature(gc.get("temperature").asDouble());
            if (gc.hasNonNull("topP")) b.topP(gc.get("topP").asDouble());
            if (gc.hasNonNull("topK")) b.topK(gc.get("topK").asInt());
            if (gc.has("stopSequences") && gc.get("stopSequences").isArray()) {
                List<String> stops = new ArrayList<>();
                gc.get("stopSequences").forEach(s -> stops.add(s.asText()));
                b.stopSequences(stops);
            }
            JsonNode tc = gc.get("thinkingConfig");
            if (tc != null && tc.isObject()) {
                String effort = parseGeminiThinkingEffort(tc);
                if (tc.hasNonNull("thinkingBudget") && tc.get("thinkingBudget").asInt() == 0
                        && StringUtils.isEmpty(tc.path("thinkingLevel").asText(""))) {
                    b.thinking(CreateMessageRequest.ThinkingConfig.disabled());
                } else if (StringUtils.isNotBlank(effort)) {
                    b.effort(effort);
                    int budget = tc.hasNonNull("thinkingBudget") && tc.get("thinkingBudget").asInt() > 0
                        ? tc.get("thinkingBudget").asInt()
                        : ReasoningNormalizer.budgetOfEffort(effort);
                    b.thinking(CreateMessageRequest.ThinkingConfig.enabled(budget));
                }
            }
            String respMime = gc.path("responseMimeType").asText("");
            if (Strings.CS.startsWith(respMime, "application/json")) {
                JsonNode rawSchema = gc.has("responseJsonSchema") ? gc.get("responseJsonSchema")
                    : gc.get("responseSchema");
                ObjectNode fmt = JsonUtils.getMapper().createObjectNode();
                if (rawSchema != null && rawSchema.isObject()) {
                    JsonNode normalized = VendorSchemaSanitizer.fromGeminiOpenApiSchema(rawSchema);
                    fmt.put("type", "json_schema");
                    fmt.put("name", "response");
                    fmt.set("schema", normalized);
                } else {
                    fmt.put("type", "json_object");
                }
                b.outputConfig(new CreateMessageRequest.OutputConfig(null, fmt));
            }
        }
        if (StringUtils.isNotEmpty(sys)) b.systemPrompt(sys);

        // Parse contents, matching legacy name-only functionResponses to open functionCalls in order.
        Map<String, String> latestIdByName = new LinkedHashMap<>();
        Map<String, List<String>> openIdsByName = new LinkedHashMap<>();
        List<CreateMessageRequest.RequestMessage> msgs = new ArrayList<>();

        if (root.has("contents") && root.get("contents").isArray()) {
            for (JsonNode content : root.get("contents")) {
                String rawRole = content.path("role").asText("user");
                String defaultRole = (Strings.CS.equals("model", rawRole)
                    || Strings.CS.equals("assistant", rawRole)) ? "assistant" : "user";
                String activeRole = defaultRole;
                List<ContentBlock> currentBlocks = new ArrayList<>();

                for (JsonNode p : content.path("parts")) {
                    String partRole = p.has("functionResponse") ? "user" : defaultRole;
                    if (!Strings.CS.equals(activeRole, partRole)) {
                        if (!currentBlocks.isEmpty()) {
                            msgs.add(new CreateMessageRequest.RequestMessage(activeRole, List.copyOf(currentBlocks)));
                            currentBlocks = new ArrayList<>();
                        }
                        activeRole = partRole;
                    }

                    if (p.has("functionCall") && p.get("functionCall").isObject()) {
                        JsonNode fc = p.get("functionCall");
                        String id = fc.path("id").asText("");
                        if (StringUtils.isEmpty(id)) {
                            id = "call_" + UUID.randomUUID().toString().substring(0, 8);
                        }
                        String name = GeminiClient.fromGeminiFuncName(fc.path("name").asText(""));
                        latestIdByName.put(name, id);
                        openIdsByName.computeIfAbsent(name, _ -> new ArrayList<>()).add(id);
                        String sig = p.path("thoughtSignature").asText("");
                        String signedId = GeminiThoughtSignature.signToolId(id, sig);
                        JsonNode args = fc.has("args") && fc.get("args").isObject()
                            ? fc.get("args").deepCopy() : JsonUtils.getMapper().createObjectNode();
                        currentBlocks.add(new ToolUseBlock(signedId, name, args));
                    } else if (p.has("functionResponse") && p.get("functionResponse").isObject()) {
                        JsonNode fr = p.get("functionResponse");
                        String name = GeminiClient.fromGeminiFuncName(fr.path("name").asText(""));
                        String id = fr.path("id").asText("");
                        List<String> queue = openIdsByName.get(name);
                        if (StringUtils.isEmpty(id)) {
                            if (queue != null && !queue.isEmpty()) {
                                id = queue.getFirst();
                            } else {
                                id = latestIdByName.getOrDefault(name, "call_" + name);
                            }
                        }
                        if (queue != null) {
                            queue.remove(id);
                        }
                        GeminiFunctionOutput out = extractGeminiFunctionResponse(fr.get("response"));
                        List<ContentBlock> resultInner = new ArrayList<>();
                        if (StringUtils.isNotEmpty(out.text())) {
                            resultInner.add(new TextBlock(out.text()));
                        }
                        List<ContentBlock> trailingFiles = new ArrayList<>();
                        for (JsonNode fp : fr.path("parts")) {
                            ContentBlock media = geminiMediaToBlock(fp);
                            if (media instanceof ImageBlock) {
                                resultInner.add(media);
                            } else if (media != null) {
                                trailingFiles.add(media);
                            }
                        }
                        currentBlocks.add(new ToolResultBlock(id, List.copyOf(resultInner), out.isError()));
                        currentBlocks.addAll(trailingFiles);
                    } else if (p.has("inlineData") || p.has("fileData")) {
                        ContentBlock media = geminiMediaToBlock(p);
                        if (media != null) currentBlocks.add(media);
                    } else if (p.path("thought").asBoolean(false)) {
                        currentBlocks.add(new ThinkingBlock(
                            p.path("text").asText(""),
                            p.path("thoughtSignature").isTextual() ? p.path("thoughtSignature").asText() : null));
                    } else if (p.has("executableCode") || p.has("codeExecutionResult")) {
                        String code = GeminiClient.formatExecutableCodePart(p);
                        if (StringUtils.isNotEmpty(code)) currentBlocks.add(new TextBlock(code));
                    } else if (StringUtils.isNotEmpty(p.path("text").asText(""))) {
                        currentBlocks.add(new TextBlock(p.path("text").asText("")));
                    }
                }
                if (!currentBlocks.isEmpty()) {
                    msgs.add(new CreateMessageRequest.RequestMessage(activeRole, List.copyOf(currentBlocks)));
                }
            }
        }
        b.messages(msgs);

        List<CreateMessageRequest.ToolDefinition> tools = new ArrayList<>();
        if (root.has("tools") && root.get("tools").isArray()) {
            for (JsonNode t : root.get("tools")) {
                for (JsonNode decl : t.path("functionDeclarations")) {
                    JsonNode schema = decl.has("parametersJsonSchema")
                        ? decl.get("parametersJsonSchema").deepCopy()
                        : VendorSchemaSanitizer.fromGeminiOpenApiSchema(decl.get("parameters"));
                    if (schema == null || !schema.isObject()) {
                        schema = JsonUtils.getMapper().createObjectNode().put("type", "object");
                    }
                    tools.add(new CreateMessageRequest.ToolDefinition(
                        GeminiClient.fromGeminiFuncName(decl.path("name").asText("")),
                        decl.path("description").isTextual() ? decl.path("description").asText() : null,
                        schema));
                }
            }
        }
        JsonNode fcConfig = root.path("toolConfig").path("functionCallingConfig");
        if (fcConfig.isObject()) {
            String mode = fcConfig.path("mode").asText("AUTO").toUpperCase(Locale.ROOT);
            JsonNode allowedNode = fcConfig.get("allowedFunctionNames");
            Set<String> allowedSet = new LinkedHashSet<>();
            if (allowedNode != null && allowedNode.isArray()) {
                for (JsonNode n : allowedNode) {
                    String fn = GeminiClient.fromGeminiFuncName(n.asText(""));
                    if (StringUtils.isNotBlank(fn)) allowedSet.add(fn);
                }
            }
            if ((Strings.CS.equals("ANY", mode) || Strings.CS.equals("VALIDATED", mode))
                    && !allowedSet.isEmpty()) {
                tools = tools.stream()
                    .filter(td -> allowedSet.contains(td.name()))
                    .toList();
            }
            if (Strings.CS.equals("NONE", mode)) {
                b.toolChoice(new CreateMessageRequest.ToolChoice("none", null));
            } else if (Strings.CS.equals("ANY", mode)) {
                if (allowedSet.size() == 1) {
                    b.toolChoice(new CreateMessageRequest.ToolChoice(
                        "tool", allowedSet.iterator().next()));
                } else {
                    b.toolChoice(new CreateMessageRequest.ToolChoice("any", null));
                }
            } else {
                b.toolChoice(new CreateMessageRequest.ToolChoice("auto", null));
            }
        }
        if (!tools.isEmpty()) b.tools(tools);
        return b.build();
    }

    // ── Outbound Anthropic Request & Response Encoders ───────────────────────


    private static ObjectNode encodeGeminiResponse(ApiMessage msg) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        ObjectNode cand = out.putArray("candidates").addObject();
        cand.put("index", 0);
        ObjectNode content = cand.putObject("content");
        content.put("role", "model");
        ArrayNode parts = content.putArray("parts");
        for (ContentBlock block : msg.content()) {
            switch (block) {
                case ThinkingBlock t -> {
                    if (StringUtils.isNotEmpty(t.thinking())) {
                        parts.addObject().put("text", t.thinking()).put("thought", true);
                    }
                }
                case TextBlock(String s) -> {
                    if (StringUtils.isNotEmpty(s)) {
                        parts.addObject().put("text", s);
                    }
                }
                case ImageBlock(JsonNode src) -> GeminiClient.appendMediaPart(parts, src);
                case ToolUseBlock tu -> {
                    GeminiThoughtSignature.UnsignedToolId u = GeminiThoughtSignature.unsignToolId(tu.id());
                    ObjectNode p = parts.addObject();
                    ObjectNode fc = p.putObject("functionCall");
                    fc.put("id", StringUtils.defaultIfBlank(u.id(), "call_" + UUID.randomUUID().toString().substring(0, 8)));
                    fc.put("name", tu.name());
                    fc.set("args", tu.input() != null ? tu.input().deepCopy()
                        : JsonUtils.getMapper().createObjectNode());
                    if (StringUtils.isNotEmpty(u.signature())) {
                        p.put("thoughtSignature", u.signature());
                    }
                }
                default -> { }
            }
        }
        String finish = Strings.CS.equals("max_tokens", msg.stopReason()) ? "MAX_TOKENS"
            : Strings.CS.equals("refusal", msg.stopReason()) ? "SAFETY" : "STOP";
        cand.put("finishReason", finish);
        out.put("modelVersion", StringUtils.defaultIfBlank(msg.model(), ""));
        out.put("responseId", StringUtils.defaultIfBlank(msg.id(), "gem_" + UUID.randomUUID()));

        Usage u = msg.usage() == null ? Usage.EMPTY : msg.usage();
        long prompt = u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens();
        ObjectNode meta = out.putObject("usageMetadata");
        meta.put("promptTokenCount", prompt);
        meta.put("candidatesTokenCount", u.outputTokens());
        meta.put("totalTokenCount", prompt + u.outputTokens());
        if (u.cacheReadInputTokens() > 0) {
            meta.put("cachedContentTokenCount", u.cacheReadInputTokens());
        }
        return out;
    }

    // ── SSE Stream Translation (Wire SSE -> StreamEvents -> Wire SSE) ─────────


    private static String encodeGeminiSse(List<StreamEvent> events, String fallbackModel) {
        ApiMessage aggregated = WireCodecSupport.aggregateEventsToMessage(events, fallbackModel);
        ObjectNode unary = encodeGeminiResponse(aggregated);
        return "data: " + unary + "\n\n";
    }


    private static JsonNode camelCaseGeminiBody(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode arr = JsonUtils.getMapper().createArrayNode();
            for (JsonNode child : node) arr.add(camelCaseGeminiBody(child));
            return arr;
        }
        if (!node.isObject()) return node;
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String rawKey = entry.getKey();
            String camelKey = Strings.CS.contains(rawKey, "_") ? snakeToCamel(rawKey) : rawKey;
            JsonNode val = GEMINI_CALLER_OWN_KEYS.contains(camelKey)
                ? entry.getValue().deepCopy()
                : camelCaseGeminiBody(entry.getValue());
            if (!Strings.CS.equals(rawKey, camelKey) && node.has(camelKey)) {
                continue;
            }
            out.set(camelKey, val);
        }
        return out;
    }

    private static String snakeToCamel(String s) {
        String[] parts = s.split("_", -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (sb.isEmpty() && i == 0) {
                sb.append(p);
            } else {
                sb.append(p.substring(0, 1).toUpperCase(Locale.ROOT)).append(p.substring(1));
            }
        }
        return sb.isEmpty() ? s : sb.toString();
    }


    private static ContentBlock geminiMediaToBlock(JsonNode p) {
        if (p.has("inlineData") && p.get("inlineData").isObject()) {
            JsonNode inl = p.get("inlineData");
            String mime = inl.path("mimeType").asText("application/octet-stream");
            ObjectNode src = JsonUtils.getMapper().createObjectNode();
            src.put("type", "base64");
            src.put("media_type", mime);
            src.put("data", inl.path("data").asText(""));
            return Strings.CS.startsWith(mime, "image/") ? new ImageBlock(src) : new DocumentBlock(src);
        }
        if (p.has("fileData") && p.get("fileData").isObject()) {
            JsonNode fd = p.get("fileData");
            String mime = fd.path("mimeType").asText("application/octet-stream");
            ObjectNode src = JsonUtils.getMapper().createObjectNode();
            src.put("type", "url");
            src.put("media_type", mime);
            src.put("url", fd.path("fileUri").asText(""));
            return Strings.CS.startsWith(mime, "image/") ? new ImageBlock(src) : new DocumentBlock(src);
        }
        return null;
    }

    private static String extractGeminiSystemText(JsonNode sys) {
        if (sys == null || sys.isNull()) return "";
        if (sys.isTextual()) return sys.asText();
        JsonNode parts = sys.isArray() ? sys : sys.path("parts");
        if (!parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            if (p.path("thought").asBoolean(false)) continue;
            String t = p.path("text").asText("");
            if (StringUtils.isNotEmpty(t)) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String parseGeminiThinkingEffort(JsonNode tc) {
        String level = tc.path("thinkingLevel").asText("");
        if (StringUtils.isNotBlank(level)) {
            return ReasoningNormalizer.normalizeEffort(level);
        }
        if (tc.hasNonNull("thinkingBudget")) {
            int budget = tc.get("thinkingBudget").asInt();
            if (budget < 0) return "medium";
            return ReasoningNormalizer.effortOfBudget(budget);
        }
        return "";
    }

    private record GeminiFunctionOutput(String text, boolean isError) {}

    private static GeminiFunctionOutput extractGeminiFunctionResponse(JsonNode raw) {
        if (raw == null || raw.isNull()) return new GeminiFunctionOutput("", false);
        if (raw.isTextual()) return new GeminiFunctionOutput(raw.asText(), false);
        if (raw.isObject()) {
            if (raw.size() == 1 && raw.has("error")) {
                JsonNode e = raw.get("error");
                return new GeminiFunctionOutput(e.isTextual() ? e.asText() : e.toString(), true);
            }
            if (raw.size() == 1 && raw.has("output")) {
                JsonNode o = raw.get("output");
                return new GeminiFunctionOutput(o.isTextual() ? o.asText() : o.toString(), false);
            }
            if (raw.size() == 1 && raw.has("result")) {
                JsonNode r = raw.get("result");
                return new GeminiFunctionOutput(r.isTextual() ? r.asText() : r.toString(), false);
            }
        }
        return new GeminiFunctionOutput(raw.toString(), false);
    }


    private static final class GeminiSseAdapter implements EventSourceStreamBridge.EventTranslator {
        private final String fallbackModel;
        private final Set<String> offeredTools;
        private final List<JsonNode> chunks = new ArrayList<>();

        GeminiSseAdapter(String fallbackModel, Set<String> offeredTools) {
            this.fallbackModel = fallbackModel;
            this.offeredTools = offeredTools;
        }

        @Override
        public void translate(String type, String data, Consumer<StreamEvent> sink) {
            if (StringUtils.isBlank(data) || Strings.CS.equals("[DONE]", data.trim())) return;
            try {
                chunks.add(JsonUtils.getMapper().readTree(data));
            } catch (Exception _) { }
        }

        @Override
        public void onClosed(Consumer<StreamEvent> sink) {
            String responseId = "gem_" + UUID.randomUUID().toString().substring(0, 8);
            String model = fallbackModel;
            Usage usage = Usage.EMPTY;
            List<ContentBlock> rawBlocks = new ArrayList<>();
            boolean sawTool = false;
            String finishReason = "";

            for (JsonNode raw : chunks) {
                JsonNode chunk = raw.has("response") && raw.get("response").isObject()
                    ? raw.get("response") : raw;
                if (StringUtils.isNotEmpty(chunk.path("responseId").asText(""))) {
                    responseId = chunk.path("responseId").asText();
                }
                if (StringUtils.isNotEmpty(chunk.path("modelVersion").asText(""))) {
                    model = chunk.path("modelVersion").asText();
                }
                if (chunk.has("usageMetadata")) {
                    usage = GeminiClient.parseGeminiUsage(chunk.get("usageMetadata"));
                }
                for (JsonNode cand : chunk.path("candidates")) {
                    for (JsonNode part : cand.path("content").path("parts")) {
                        if (GeminiClient.appendCandidatePart(rawBlocks, part)) {
                            sawTool = true;
                        }
                    }
                    if (StringUtils.isNotEmpty(cand.path("finishReason").asText(""))) {
                        finishReason = cand.path("finishReason").asText();
                    }
                }
            }
            List<ContentBlock> coalesced = coalesceBlocks(rawBlocks);
            if (!sawTool) {
                TextCallRescue.RescueOutcome rescued = TextCallRescue.rescueBlocks(coalesced, offeredTools);
                if (rescued.rescuedTools()) {
                    coalesced = rescued.blocks();
                    sawTool = true;
                }
            }
            String stop = GeminiClient.mapFinishReason(finishReason, sawTool);
            sink.accept(new StreamEvent.MessageStart(new ApiMessage(
                responseId, "message", "assistant", List.of(), model, null, null,
                new Usage(usage.inputTokens(), 0L, usage.cacheCreationInputTokens(),
                    usage.cacheReadInputTokens()))));
            int idx = 0;
            for (ContentBlock b : coalesced) {
                switch (b) {
                    case ThinkingBlock t -> {
                        sink.accept(new StreamEvent.ContentBlockStart(idx, new ThinkingBlock("", null)));
                        if (StringUtils.isNotEmpty(t.thinking())) {
                            sink.accept(new StreamEvent.ContentBlockDelta(idx, new Delta.ThinkingDelta(t.thinking())));
                        }
                        if (StringUtils.isNotEmpty(t.signature())) {
                            sink.accept(new StreamEvent.ContentBlockDelta(idx, new Delta.SignatureDelta(t.signature())));
                        }
                        sink.accept(new StreamEvent.ContentBlockStop(idx++));
                    }
                    case TextBlock(String s) -> {
                        sink.accept(new StreamEvent.ContentBlockStart(idx, new TextBlock("")));
                        sink.accept(new StreamEvent.ContentBlockDelta(idx, new Delta.TextDelta(s)));
                        sink.accept(new StreamEvent.ContentBlockStop(idx++));
                    }
                    case ToolUseBlock tu -> {
                        sink.accept(new StreamEvent.ContentBlockStart(idx,
                            new ToolUseBlock(tu.id(), tu.name(), JsonUtils.getMapper().createObjectNode())));
                        sink.accept(new StreamEvent.ContentBlockDelta(idx,
                            new Delta.InputJsonDelta(tu.input() == null ? "{}" : tu.input().toString())));
                        sink.accept(new StreamEvent.ContentBlockStop(idx++));
                    }
                    default -> { }
                }
            }
            sink.accept(new StreamEvent.MessageDelta(new MessageDeltaData(stop, null), usage));
            sink.accept(new StreamEvent.MessageStop());
        }

        private static List<ContentBlock> coalesceBlocks(List<ContentBlock> raw) {
            List<ContentBlock> out = new ArrayList<>();
            for (ContentBlock b : raw) {
                if (!out.isEmpty() && b instanceof TextBlock(String s)
                        && out.getLast() instanceof TextBlock(String prev)) {
                    out.removeLast();
                    out.add(new TextBlock(prev + s));
                } else if (!out.isEmpty() && b instanceof ThinkingBlock t
                        && out.getLast() instanceof ThinkingBlock prevT) {
                    out.removeLast();
                    String sig = StringUtils.isNotEmpty(t.signature()) ? t.signature() : prevT.signature();
                    out.add(new ThinkingBlock(prevT.thinking() + t.thinking(), sig));
                } else {
                    out.add(b);
                }
            }
            return out;
        }
    }

    // ── Live Incremental SSE Projectors (StreamEvent -> Target SSE Frames) ───


    private static final class GeminiLiveProjector implements ProtocolWireCodec.StreamProjector {
        private String responseId = "gem_" + UUID.randomUUID().toString().substring(0, 12);
        private String model;
        private String stopReason = "end_turn";
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;
        private boolean finished;
        private final Map<Integer, WireCodecSupport.BlockAccum> toolBlocks = new LinkedHashMap<>();

        GeminiLiveProjector(String fallbackModel) {
            this.model = StringUtils.defaultIfBlank(fallbackModel, "");
        }

        @Override
        public List<String> onEvent(StreamEvent event) {
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) responseId = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    if (ms.message().usage() != null) usage = WireCodecSupport.mergeStreamUsage(usage, ms.message().usage());
                    return List.of();
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    if (cbs.contentBlock() instanceof ToolUseBlock) {
                        contentEmitted = true;
                        toolBlocks.put(cbs.index(), new WireCodecSupport.BlockAccum(cbs.contentBlock()));
                    }
                    return List.of();
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    contentEmitted = true;
                    WireCodecSupport.BlockAccum toolAcc = toolBlocks.get(cbd.index());
                    if (toolAcc != null) {
                        toolAcc.apply(cbd.delta());
                        return List.of();
                    }
                    return switch (cbd.delta()) {
                        case Delta.ThinkingDelta(String t) -> StringUtils.isEmpty(t) ? List.of()
                            : List.of(encodePartChunk(
                                JsonUtils.getMapper().createObjectNode().put("text", t).put("thought", true),
                                null, false));
                        case Delta.TextDelta(String t) -> StringUtils.isEmpty(t) ? List.of()
                            : List.of(encodePartChunk(
                                JsonUtils.getMapper().createObjectNode().put("text", t),
                                null, false));
                        default -> List.of();
                    };
                }
                case StreamEvent.ContentBlockStop cbStop -> {
                    WireCodecSupport.BlockAccum acc = toolBlocks.remove(cbStop.index());
                    if (acc != null && acc.build() instanceof ToolUseBlock tu) {
                        GeminiThoughtSignature.UnsignedToolId u = GeminiThoughtSignature.unsignToolId(tu.id());
                        ObjectNode part = JsonUtils.getMapper().createObjectNode();
                        ObjectNode fc = part.putObject("functionCall");
                        fc.put("id", StringUtils.defaultIfBlank(u.id(),
                            "call_" + UUID.randomUUID().toString().substring(0, 8)));
                        fc.put("name", tu.name());
                        fc.set("args", tu.input() != null ? tu.input().deepCopy()
                            : JsonUtils.getMapper().createObjectNode());
                        if (StringUtils.isNotEmpty(u.signature())) {
                            part.put("thoughtSignature", u.signature());
                        }
                        return List.of(encodePartChunk(part, null, false));
                    }
                    return List.of();
                }
                case StreamEvent.MessageDelta md -> {
                    if (StringUtils.isNotEmpty(md.delta().stopReason())) {
                        stopReason = md.delta().stopReason();
                    }
                    usage = WireCodecSupport.mergeStreamUsage(usage, md.usage());
                    return List.of();
                }
                case StreamEvent.MessageStop _ -> {
                    return finish();
                }
                default -> {
                    return List.of();
                }
            }
        }

        @Override
        public List<String> finish() {
            if (finished) return List.of();
            finished = true;
            String finish = Strings.CS.equals("max_tokens", stopReason) ? "MAX_TOKENS"
                : Strings.CS.equals("refusal", stopReason) ? "SAFETY" : "STOP";
            return List.of(encodePartChunk(null, finish, true));
        }

        private String encodePartChunk(ObjectNode part, String finishReason, boolean includeUsage) {
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            ObjectNode cand = out.putArray("candidates").addObject();
            cand.put("index", 0);
            ObjectNode content = cand.putObject("content");
            content.put("role", "model");
            ArrayNode parts = content.putArray("parts");
            if (part != null) parts.add(part);
            if (finishReason != null) cand.put("finishReason", finishReason);
            out.put("modelVersion", model);
            out.put("responseId", responseId);
            if (includeUsage) {
                long prompt = usage.inputTokens() + usage.cacheReadInputTokens() + usage.cacheCreationInputTokens();
                ObjectNode meta = out.putObject("usageMetadata");
                meta.put("promptTokenCount", prompt);
                meta.put("candidatesTokenCount", usage.outputTokens());
                meta.put("totalTokenCount", prompt + usage.outputTokens());
                if (usage.cacheReadInputTokens() > 0) {
                    meta.put("cachedContentTokenCount", usage.cacheReadInputTokens());
                }
            }
            return "data: " + out + "\n\n";
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
