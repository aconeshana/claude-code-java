package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
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

final class OpenAiResponsesWireCodec implements ProtocolCodec {

    @Override
    public CreateMessageRequest parseRequest(ObjectNode root) {
        return parseResponsesRequest(root);
    }

    @Override
    public ObjectNode encodeRequest(
            CreateMessageRequest request, String targetBaseUrl, boolean forClientRelay) {
        return OpenAiResponsesClient.buildResponsesRequestNode(
            request, request.model(), request.stream(), targetBaseUrl, forClientRelay);
    }

    @Override
    public ApiMessage parseResponse(String jsonBody, Set<String> offeredTools) throws Exception {
        return OpenAiResponsesClient.parseResponsesBodyStatic(jsonBody, "", offeredTools);
    }

    @Override
    public ObjectNode encodeResponse(ApiMessage message) {
        return encodeResponsesResponse(message);
    }

    @Override
    public EventSourceStreamBridge.EventTranslator newSseTranslator(
            String fallbackModel, Set<String> offeredTools) {
        return new OpenAiResponsesClient.ResponsesEventTranslator(offeredTools);
    }

    @Override
    public String encodeSse(List<StreamEvent> events, String fallbackModel) {
        return encodeResponsesSse(events, fallbackModel);
    }

    @Override
    public ProtocolWireCodec.StreamProjector newStreamProjector(String fallbackModel) {
        return new ResponsesLiveProjector(fallbackModel);
    }

    private static CreateMessageRequest parseResponsesRequest(ObjectNode root) {
        CreateMessageRequest.Builder b = CreateMessageRequest.builder();
        b.model(root.path("model").asText(""));
        b.maxTokens(root.path("max_output_tokens").asInt(4096));
        b.stream(root.path("stream").asBoolean(false));
        if (root.hasNonNull("temperature")) b.temperature(root.get("temperature").asDouble());
        if (root.hasNonNull("top_p")) b.topP(root.get("top_p").asDouble());

        StringBuilder system = new StringBuilder();
        if (root.hasNonNull("instructions") && root.get("instructions").isTextual()) {
            system.append(root.get("instructions").asText());
        }

        String effort = root.path("reasoning").path("effort").asText("");
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

        JsonNode fmt = WireCodecSupport.parseOpenAiFormat(root.path("text").get("format"));
        if (fmt != null) {
            b.outputConfig(new CreateMessageRequest.OutputConfig(
                StringUtils.isNotBlank(effort) ? ReasoningNormalizer.normalizeEffort(effort) : null, fmt));
        }

        List<CreateMessageRequest.RequestMessage> msgs = new ArrayList<>();
        JsonNode input = root.get("input");
        if (input != null && input.isTextual()) {
            msgs.add(new CreateMessageRequest.RequestMessage("user", input.asText()));
        } else if (input != null && input.isArray()) {
            for (JsonNode item : input) {
                String type = item.path("type").asText("");
                String role = item.path("role").asText("");
                if (Strings.CS.equals("system", role) || Strings.CS.equals("developer", role)) {
                    String sysPart = extractResponsesContentText(item.get("content"));
                    if (StringUtils.isNotEmpty(sysPart)) {
                        if (!system.isEmpty()) system.append("\n\n");
                        system.append(sysPart);
                    }
                    continue;
                }
                if (Strings.CS.equals("reasoning", type)) {
                    String enc = item.path("encrypted_content").asText("");
                    ContentBlock unsealed = ClaudeSeal.openToContentBlock(enc);
                    if (unsealed != null) {
                        msgs.add(new CreateMessageRequest.RequestMessage("assistant", List.of(unsealed)));
                    } else {
                        StringBuilder sum = new StringBuilder();
                        for (JsonNode s : item.path("summary")) {
                            if (!sum.isEmpty()) sum.append('\n');
                            sum.append(s.path("text").asText(""));
                        }
                        if (!sum.isEmpty() || StringUtils.isNotEmpty(enc)) {
                            msgs.add(new CreateMessageRequest.RequestMessage("assistant", List.of(
                                new ThinkingBlock(sum.toString(), StringUtils.isEmpty(enc) ? null : enc))));
                        }
                    }
                    continue;
                }
                if (Strings.CS.equals("function_call", type)) {
                    String callId = item.path("call_id").asText(item.path("id").asText(""));
                    JsonNode args = WireCodecSupport.parseJsonObjectOrEmpty(item.path("arguments").asText("{}"));
                    msgs.add(new CreateMessageRequest.RequestMessage("assistant", List.of(
                        new ToolUseBlock(callId, item.path("name").asText(""), args))));
                    continue;
                }
                if (Strings.CS.equals("function_call_output", type)) {
                    String callId = item.path("call_id").asText("");
                    List<ContentBlock> outBlocks = parseResponsesToolOutput(item.get("output"));
                    msgs.add(new CreateMessageRequest.RequestMessage("user", List.of(
                        new ToolResultBlock(callId, outBlocks, false))));
                    continue;
                }
                if (Strings.CS.equals("input_file", type)) {
                    ContentBlock doc = responsesInputFileToDocument(item);
                    if (doc != null) {
                        msgs.add(new CreateMessageRequest.RequestMessage("user", List.of(doc)));
                    }
                    continue;
                }
                // Message item
                String msgRole = Strings.CS.equals("assistant", role) ? "assistant" : "user";
                msgs.add(new CreateMessageRequest.RequestMessage(
                    msgRole, parseResponsesMessageBlocks(item.get("content"))));
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
            } else if (tc.isObject() && tc.hasNonNull("name")) {
                b.toolChoice(new CreateMessageRequest.ToolChoice("tool", tc.path("name").asText()));
            }
        }
        return b.build();
    }

    private static final Set<String> GEMINI_USER_OWNED_FIELDS = Set.of(
        "args", "response", "parameters", "parametersJsonSchema",
        "responseSchema", "responseJsonSchema", "labels");


    private static ObjectNode encodeResponsesResponse(ApiMessage msg) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("id", StringUtils.defaultIfBlank(msg.id(), "resp_" + UUID.randomUUID()));
        out.put("object", "response");
        out.put("created_at", Instant.now().getEpochSecond());
        out.put("status", Strings.CS.equals("max_tokens", msg.stopReason()) ? "incomplete" : "completed");
        out.put("model", StringUtils.defaultIfBlank(msg.model(), ""));
        ArrayNode output = out.putArray("output");
        int itemIdx = 0;
        ArrayNode textParts = JsonUtils.getMapper().createArrayNode();

        for (ContentBlock block : msg.content()) {
            switch (block) {
                case ThinkingBlock thinking -> {
                    ObjectNode r = output.addObject();
                    r.put("id", "rs_" + itemIdx++);
                    r.put("type", "reasoning");
                    ArrayNode sum = r.putArray("summary");
                    if (StringUtils.isNotEmpty(thinking.thinking())) {
                        sum.addObject().put("type", "summary_text").put("text", thinking.thinking());
                    }
                    if (StringUtils.isNotEmpty(thinking.signature())) {
                        String enc = ClaudeSeal.isSealed(thinking.signature())
                            ? thinking.signature()
                            : ClaudeSeal.sealThinking(thinking.thinking(), thinking.signature());
                        r.put("encrypted_content", enc);
                    }
                }
                case RedactedThinkingBlock(String data) -> {
                    ObjectNode r = output.addObject();
                    r.put("id", "rs_" + itemIdx++);
                    r.put("type", "reasoning");
                    r.putArray("summary");
                    r.put("encrypted_content", ClaudeSeal.sealRedacted(data));
                }
                case TextBlock(String s) -> textParts.addObject()
                    .put("type", "output_text")
                    .put("text", s)
                    .putArray("annotations");
                case ToolUseBlock tu -> {
                    if (!textParts.isEmpty()) {
                        ObjectNode m = output.addObject();
                        m.put("id", "msg_" + itemIdx++);
                        m.put("type", "message");
                        m.put("status", "completed");
                        m.put("role", "assistant");
                        m.set("content", textParts);
                        textParts = JsonUtils.getMapper().createArrayNode();
                    }
                    ObjectNode fc = output.addObject();
                    fc.put("id", "fc_" + itemIdx++);
                    fc.put("type", "function_call");
                    fc.put("status", "completed");
                    fc.put("call_id", tu.id());
                    fc.put("name", tu.name());
                    fc.put("arguments", tu.input() == null ? "{}" : tu.input().toString());
                }
                default -> { }
            }
        }
        if (!textParts.isEmpty()) {
            ObjectNode m = output.addObject();
            m.put("id", "msg_" + itemIdx);
            m.put("type", "message");
            m.put("status", "completed");
            m.put("role", "assistant");
            m.set("content", textParts);
        }

        Usage u = msg.usage() == null ? Usage.EMPTY : msg.usage();
        long input = u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens();
        ObjectNode usage = out.putObject("usage");
        usage.put("input_tokens", input);
        usage.put("output_tokens", u.outputTokens());
        usage.put("total_tokens", input + u.outputTokens());
        if (u.cacheReadInputTokens() > 0) {
            usage.putObject("input_tokens_details").put("cached_tokens", u.cacheReadInputTokens());
        }
        return out;
    }


    private static String encodeResponsesSse(List<StreamEvent> events, String fallbackModel) {
        ApiMessage aggregated = WireCodecSupport.aggregateEventsToMessage(events, fallbackModel);
        ObjectNode completedResp = encodeResponsesResponse(aggregated);
        String respId = completedResp.path("id").asText();
        long createdAt = completedResp.path("created_at").asLong();
        String model = completedResp.path("model").asText();

        StringBuilder sb = new StringBuilder();
        int seq = 0;

        ObjectNode createdShell = JsonUtils.getMapper().createObjectNode();
        createdShell.put("id", respId);
        createdShell.put("object", "response");
        createdShell.put("created_at", createdAt);
        createdShell.put("status", "in_progress");
        createdShell.put("model", model);
        createdShell.putArray("output");

        ObjectNode evCreated = JsonUtils.getMapper().createObjectNode();
        evCreated.put("type", "response.created");
        evCreated.put("sequence_number", seq++);
        evCreated.set("response", createdShell);
        appendResponsesEvent(sb, "response.created", evCreated);

        ArrayNode outItems = (ArrayNode) completedResp.path("output");
        for (int i = 0; i < outItems.size(); i++) {
            JsonNode item = outItems.get(i);
            ObjectNode added = JsonUtils.getMapper().createObjectNode();
            added.put("type", "response.output_item.added");
            added.put("sequence_number", seq++);
            added.put("output_index", i);
            added.set("item", item.deepCopy());
            appendResponsesEvent(sb, "response.output_item.added", added);

            String itemType = item.path("type").asText("");
            if (Strings.CS.equals("message", itemType)) {
                for (JsonNode part : item.path("content")) {
                    ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                    delta.put("type", "response.output_text.delta");
                    delta.put("sequence_number", seq++);
                    delta.put("output_index", i);
                    delta.put("content_index", 0);
                    delta.put("item_id", item.path("id").asText());
                    delta.put("delta", part.path("text").asText(""));
                    appendResponsesEvent(sb, "response.output_text.delta", delta);
                }
            } else if (Strings.CS.equals("function_call", itemType)) {
                ObjectNode delta = JsonUtils.getMapper().createObjectNode();
                delta.put("type", "response.function_call_arguments.delta");
                delta.put("sequence_number", seq++);
                delta.put("output_index", i);
                delta.put("item_id", item.path("id").asText());
                delta.put("delta", item.path("arguments").asText("{}"));
                appendResponsesEvent(sb, "response.function_call_arguments.delta", delta);
            }

            ObjectNode done = JsonUtils.getMapper().createObjectNode();
            done.put("type", "response.output_item.done");
            done.put("sequence_number", seq++);
            done.put("output_index", i);
            done.set("item", item.deepCopy());
            appendResponsesEvent(sb, "response.output_item.done", done);
        }

        String terminalType = "response." + completedResp.path("status").asText("completed");
        ObjectNode completedEv = JsonUtils.getMapper().createObjectNode();
        completedEv.put("type", terminalType);
        completedEv.put("sequence_number", seq);
        completedEv.set("response", completedResp);
        appendResponsesEvent(sb, terminalType, completedEv);
        return sb.toString();
    }

    private static void appendResponsesEvent(StringBuilder sb, String type, ObjectNode payload) {
        sb.append("event: ").append(type).append("\ndata: ").append(payload).append("\n\n");
    }


    private static String extractResponsesContentText(JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : content) {
                if (p.hasNonNull("text")) sb.append(p.path("text").asText(""));
            }
            return sb.toString();
        }
        return "";
    }

    private static List<ContentBlock> parseResponsesMessageBlocks(JsonNode content) {
        if (content == null || content.isNull()) return List.of();
        if (content.isTextual()) return List.of(new TextBlock(content.asText()));
        if (!content.isArray()) return List.of();
        List<ContentBlock> out = new ArrayList<>();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            switch (type) {
                case "input_text", "output_text", "text" -> out.add(new TextBlock(part.path("text").asText("")));
                case "input_image" -> {
                    String url = part.path("image_url").isTextual()
                        ? part.path("image_url").asText()
                        : part.path("image_url").path("url").asText("");
                    ContentBlock img = WireCodecSupport.dataOrRemoteUrlToBlock(url, true);
                    if (img != null) out.add(img);
                }
                case "input_file" -> {
                    ContentBlock doc = responsesInputFileToDocument(part);
                    if (doc != null) out.add(doc);
                }
                default -> { }
            }
        }
        return List.copyOf(out);
    }

    private static List<ContentBlock> parseResponsesToolOutput(JsonNode output) {
        if (output == null || output.isNull()) return List.of(new TextBlock(""));
        if (output.isTextual()) return List.of(new TextBlock(output.asText()));
        if (output.isArray()) return parseResponsesMessageBlocks(output);
        return List.of(new TextBlock(output.toString()));
    }

    private static ContentBlock responsesInputFileToDocument(JsonNode item) {
        String fileData = item.path("file_data").asText("");
        if (StringUtils.isNotEmpty(fileData)) {
            if (Strings.CS.startsWith(fileData, "data:")) {
                return WireCodecSupport.dataOrRemoteUrlToBlock(fileData, false);
            }
            ObjectNode src = JsonUtils.getMapper().createObjectNode();
            src.put("type", "base64");
            src.put("media_type", "application/pdf");
            src.put("data", fileData);
            return new DocumentBlock(src);
        }
        String fileUrl = item.path("file_url").asText("");
        if (StringUtils.isNotEmpty(fileUrl)) {
            return WireCodecSupport.dataOrRemoteUrlToBlock(fileUrl, false);
        }
        return null;
    }


    private static final class ResponsesLiveProjector implements ProtocolWireCodec.StreamProjector {
        private String respId = "resp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        private final long createdAt = Instant.now().getEpochSecond();
        private String model;
        private int seq;
        private int nextOutputIdx;
        private boolean createdSent;
        private boolean completedSent;
        private String stopReason = "end_turn";
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;
        private final Map<Integer, WireCodecSupport.BlockAccum> activeBlocks = new LinkedHashMap<>();
        private final Map<Integer, Integer> blockToOutputIdx = new LinkedHashMap<>();
        private final Map<Integer, String> blockToItemId = new LinkedHashMap<>();
        private final ArrayNode completedItems = JsonUtils.getMapper().createArrayNode();

        ResponsesLiveProjector(String fallbackModel) {
            this.model = StringUtils.defaultIfBlank(fallbackModel, "");
        }

        @Override
        public List<String> onEvent(StreamEvent event) {
            StringBuilder sb = new StringBuilder();
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) respId = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    if (ms.message().usage() != null) usage = WireCodecSupport.mergeStreamUsage(usage, ms.message().usage());
                    ensureCreated(sb);
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    ensureCreated(sb);
                    int outIdx = nextOutputIdx++;
                    activeBlocks.put(cbs.index(), new WireCodecSupport.BlockAccum(cbs.contentBlock()));
                    blockToOutputIdx.put(cbs.index(), outIdx);
                    ObjectNode item = JsonUtils.getMapper().createObjectNode();
                    switch (cbs.contentBlock()) {
                        case ThinkingBlock _ -> {
                            String itemId = "rs_" + outIdx;
                            blockToItemId.put(cbs.index(), itemId);
                            item.put("id", itemId).put("type", "reasoning").putArray("summary");
                        }
                        case ToolUseBlock tu -> {
                            contentEmitted = true;
                            String itemId = "fc_" + outIdx;
                            blockToItemId.put(cbs.index(), itemId);
                            item.put("id", itemId)
                                .put("type", "function_call")
                                .put("status", "in_progress")
                                .put("call_id", tu.id())
                                .put("name", tu.name())
                                .put("arguments", "");
                        }
                        default -> {
                            String itemId = "msg_" + outIdx;
                            blockToItemId.put(cbs.index(), itemId);
                            item.put("id", itemId)
                                .put("type", "message")
                                .put("status", "in_progress")
                                .put("role", "assistant")
                                .putArray("content");
                        }
                    }
                    ObjectNode added = JsonUtils.getMapper().createObjectNode();
                    added.put("type", "response.output_item.added");
                    added.put("sequence_number", seq++);
                    added.put("output_index", outIdx);
                    added.set("item", item);
                    appendResponsesEvent(sb, "response.output_item.added", added);
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    contentEmitted = true;
                    WireCodecSupport.BlockAccum acc = activeBlocks.get(cbd.index());
                    if (acc != null) acc.apply(cbd.delta());
                    int outIdx = blockToOutputIdx.getOrDefault(cbd.index(), 0);
                    String itemId = blockToItemId.getOrDefault(cbd.index(), "msg_0");
                    switch (cbd.delta()) {
                        case Delta.TextDelta(String text) -> {
                            ObjectNode d = JsonUtils.getMapper().createObjectNode();
                            d.put("type", "response.output_text.delta");
                            d.put("sequence_number", seq++);
                            d.put("output_index", outIdx);
                            d.put("content_index", 0);
                            d.put("item_id", itemId);
                            d.put("delta", text);
                            appendResponsesEvent(sb, "response.output_text.delta", d);
                        }
                        case Delta.ThinkingDelta(String thinking) -> {
                            ObjectNode d = JsonUtils.getMapper().createObjectNode();
                            d.put("type", "response.reasoning_summary_text.delta");
                            d.put("sequence_number", seq++);
                            d.put("output_index", outIdx);
                            d.put("summary_index", 0);
                            d.put("item_id", itemId);
                            d.put("delta", thinking);
                            appendResponsesEvent(sb, "response.reasoning_summary_text.delta", d);
                        }
                        case Delta.InputJsonDelta(String partialJson) -> {
                            ObjectNode d = JsonUtils.getMapper().createObjectNode();
                            d.put("type", "response.function_call_arguments.delta");
                            d.put("sequence_number", seq++);
                            d.put("output_index", outIdx);
                            d.put("item_id", itemId);
                            d.put("delta", partialJson);
                            appendResponsesEvent(sb, "response.function_call_arguments.delta", d);
                        }
                        default -> { }
                    }
                }
                case StreamEvent.ContentBlockStop cbStop -> {
                    WireCodecSupport.BlockAccum acc = activeBlocks.remove(cbStop.index());
                    if (acc != null) {
                        ContentBlock built = acc.build();
                        int outIdx = blockToOutputIdx.getOrDefault(cbStop.index(), 0);
                        String itemId = blockToItemId.getOrDefault(cbStop.index(), "msg_" + outIdx);
                        ObjectNode doneItem = buildResponsesDoneItem(built, itemId);
                        completedItems.add(doneItem);
                        ObjectNode done = JsonUtils.getMapper().createObjectNode();
                        done.put("type", "response.output_item.done");
                        done.put("sequence_number", seq++);
                        done.put("output_index", outIdx);
                        done.set("item", doneItem.deepCopy());
                        appendResponsesEvent(sb, "response.output_item.done", done);
                    }
                }
                case StreamEvent.MessageDelta md -> {
                    if (StringUtils.isNotEmpty(md.delta().stopReason())) {
                        stopReason = md.delta().stopReason();
                    }
                    usage = WireCodecSupport.mergeStreamUsage(usage, md.usage());
                }
                case StreamEvent.MessageStop _ -> emitCompleted(sb);
                default -> { }
            }
            return sb.isEmpty() ? List.of() : List.of(sb.toString());
        }

        @Override
        public List<String> finish() {
            if (completedSent) return List.of();
            StringBuilder sb = new StringBuilder();
            ensureCreated(sb);
            emitCompleted(sb);
            return sb.isEmpty() ? List.of() : List.of(sb.toString());
        }

        private void ensureCreated(StringBuilder sb) {
            if (createdSent) return;
            createdSent = true;
            ObjectNode createdShell = JsonUtils.getMapper().createObjectNode();
            createdShell.put("id", respId);
            createdShell.put("object", "response");
            createdShell.put("created_at", createdAt);
            createdShell.put("status", "in_progress");
            createdShell.put("model", model);
            createdShell.putArray("output");
            ObjectNode ev = JsonUtils.getMapper().createObjectNode();
            ev.put("type", "response.created");
            ev.put("sequence_number", seq++);
            ev.set("response", createdShell);
            appendResponsesEvent(sb, "response.created", ev);
        }

        private void emitCompleted(StringBuilder sb) {
            if (completedSent) return;
            completedSent = true;
            boolean incomplete = Strings.CS.equals("max_tokens", stopReason);
            String status = incomplete ? "incomplete" : "completed";
            ObjectNode resp = JsonUtils.getMapper().createObjectNode();
            resp.put("id", respId);
            resp.put("object", "response");
            resp.put("created_at", createdAt);
            resp.put("status", status);
            resp.put("model", model);
            if (incomplete) {
                resp.putObject("incomplete_details").put("reason", "max_output_tokens");
            }
            resp.set("output", completedItems.deepCopy());
            long input = usage.inputTokens() + usage.cacheReadInputTokens() + usage.cacheCreationInputTokens();
            ObjectNode u = resp.putObject("usage");
            u.put("input_tokens", input);
            u.put("output_tokens", usage.outputTokens());
            u.put("total_tokens", input + usage.outputTokens());
            if (usage.cacheReadInputTokens() > 0) {
                u.putObject("input_tokens_details").put("cached_tokens", usage.cacheReadInputTokens());
            }
            String terminalType = "response." + status;
            ObjectNode ev = JsonUtils.getMapper().createObjectNode();
            ev.put("type", terminalType);
            ev.put("sequence_number", seq++);
            ev.set("response", resp);
            appendResponsesEvent(sb, terminalType, ev);
        }

        private static ObjectNode buildResponsesDoneItem(ContentBlock block, String itemId) {
            ObjectNode item = JsonUtils.getMapper().createObjectNode();
            switch (block) {
                case ThinkingBlock thinking -> {
                    item.put("id", itemId).put("type", "reasoning");
                    ArrayNode sum = item.putArray("summary");
                    if (StringUtils.isNotEmpty(thinking.thinking())) {
                        sum.addObject().put("type", "summary_text").put("text", thinking.thinking());
                    }
                    if (StringUtils.isNotEmpty(thinking.signature())) {
                        String enc = ClaudeSeal.isSealed(thinking.signature())
                            ? thinking.signature()
                            : ClaudeSeal.sealThinking(thinking.thinking(), thinking.signature());
                        item.put("encrypted_content", enc);
                    }
                }
                case ToolUseBlock tu -> item.put("id", itemId)
                    .put("type", "function_call")
                    .put("status", "completed")
                    .put("call_id", tu.id())
                    .put("name", tu.name())
                    .put("arguments", tu.input() == null ? "{}" : tu.input().toString());
                case TextBlock(String s) -> {
                    item.put("id", itemId)
                        .put("type", "message")
                        .put("status", "completed")
                        .put("role", "assistant");
                    item.putArray("content").addObject()
                        .put("type", "output_text")
                        .put("text", s)
                        .putArray("annotations");
                }
                default -> item.put("id", itemId).put("type", "message").put("status", "completed");
            }
            return item;
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
