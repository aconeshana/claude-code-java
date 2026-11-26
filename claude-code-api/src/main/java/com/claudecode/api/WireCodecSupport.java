package com.claudecode.api;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class WireCodecSupport {

    private WireCodecSupport() {}

    static List<StreamEvent> decodeSseToStreamEvents(
            EventSourceStreamBridge.EventTranslator translator, String rawSse) {
        List<StreamEvent> events = new ArrayList<>();
        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        for (String line : rawSse.split("\n", -1)) {
            String stripped = Strings.CS.endsWith(line, "\r")
                ? line.substring(0, line.length() - 1) : line;
            if (stripped.isEmpty()) {
                if (!currentData.isEmpty()) {
                    translator.translate(currentEvent, currentData.toString(), events::add);
                    currentData.setLength(0);
                    currentEvent = null;
                }
                continue;
            }
            if (Strings.CS.startsWith(stripped, ":")) continue;
            if (Strings.CS.startsWith(stripped, "event:")) {
                currentEvent = stripped.substring("event:".length()).trim();
            } else if (Strings.CS.startsWith(stripped, "data:")) {
                String payload = stripped.substring("data:".length());
                if (Strings.CS.startsWith(payload, " ")) payload = payload.substring(1);
                if (!currentData.isEmpty()) currentData.append('\n');
                currentData.append(payload);
            }
        }
        if (!currentData.isEmpty()) {
            translator.translate(currentEvent, currentData.toString(), events::add);
        }
        translator.onClosed(events::add);
        return events;
    }

    static ApiMessage aggregateEventsToMessage(List<StreamEvent> events, String fallbackModel) {
        String id = "msg_" + UUID.randomUUID().toString().substring(0, 12);
        String model = StringUtils.defaultIfBlank(fallbackModel, "");
        String stopReason = "end_turn";
        Usage usage = Usage.EMPTY;

        Map<Integer, BlockAccum> blocksByIdx = new LinkedHashMap<>();
        List<ContentBlock> completedBlocks = new ArrayList<>();

        for (StreamEvent ev : events) {
            switch (ev) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) id = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    if (ms.message().usage() != null) usage = ms.message().usage();
                }
                case StreamEvent.ContentBlockStart cbs ->
                    blocksByIdx.put(cbs.index(), new BlockAccum(cbs.contentBlock()));
                case StreamEvent.ContentBlockDelta cbd -> {
                    BlockAccum acc = blocksByIdx.get(cbd.index());
                    if (acc != null) acc.apply(cbd.delta());
                }
                case StreamEvent.ContentBlockStop cbStop -> {
                    BlockAccum acc = blocksByIdx.remove(cbStop.index());
                    if (acc != null) completedBlocks.add(acc.build());
                }
                case StreamEvent.MessageDelta md -> {
                    if (StringUtils.isNotEmpty(md.delta().stopReason())) {
                        stopReason = md.delta().stopReason();
                    }
                    if (md.usage() != null) {
                        long inTok = md.usage().inputTokens() > 0 ? md.usage().inputTokens() : usage.inputTokens();
                        long cacheRead = md.usage().cacheReadInputTokens() > 0
                            ? md.usage().cacheReadInputTokens() : usage.cacheReadInputTokens();
                        long cacheWrite = md.usage().cacheCreationInputTokens() > 0
                            ? md.usage().cacheCreationInputTokens() : usage.cacheCreationInputTokens();
                        usage = new Usage(inTok, md.usage().outputTokens(), cacheWrite, cacheRead);
                    }
                }
                default -> { }
            }
        }
        for (BlockAccum remaining : blocksByIdx.values()) {
            completedBlocks.add(remaining.build());
        }
        return new ApiMessage(id, "message", "assistant", List.copyOf(completedBlocks), model, stopReason, null, usage);
    }

    // ── Internal Parsing & Normalization Helpers ─────────────────────────────


    static ContentBlock dataOrRemoteUrlToBlock(String url, boolean defaultImage) {
        if (StringUtils.isBlank(url)) return null;
        ObjectNode src = JsonUtils.getMapper().createObjectNode();
        int semi = url.indexOf(";base64,");
        if (Strings.CS.startsWith(url, "data:") && semi > 0) {
            String mime = url.substring("data:".length(), semi);
            String data = url.substring(semi + ";base64,".length());
            src.put("type", "base64");
            src.put("media_type", mime);
            src.put("data", data);
            return Strings.CS.startsWith(mime, "image/") ? new ImageBlock(src) : new DocumentBlock(src);
        }
        src.put("type", "url");
        src.put("url", url);
        return defaultImage ? new ImageBlock(src) : new DocumentBlock(src);
    }


    static JsonNode parseOpenAiFormat(JsonNode fmt) {
        if (fmt == null || !fmt.isObject()) return null;
        String type = fmt.path("type").asText("");
        if (Strings.CS.equals("json_object", type)) {
            return JsonUtils.getMapper().createObjectNode().put("type", "json_object");
        }
        if (Strings.CS.equals("json_schema", type)) {
            ObjectNode out = JsonUtils.getMapper().createObjectNode().put("type", "json_schema");
            JsonNode inner = fmt.has("json_schema") && fmt.get("json_schema").isObject()
                ? fmt.get("json_schema") : fmt;
            out.put("name", inner.path("name").asText("response"));
            if (inner.has("strict") && inner.get("strict").isBoolean()) {
                out.put("strict", inner.get("strict").asBoolean());
            }
            if (inner.has("schema") && inner.get("schema").isObject()) {
                out.set("schema", inner.get("schema").deepCopy());
            }
            return out;
        }
        return null;
    }


    static JsonNode parseJsonObjectOrEmpty(String raw) {
        if (StringUtils.isBlank(raw)) return JsonUtils.getMapper().createObjectNode();
        try {
            JsonNode n = JsonUtils.getMapper().readTree(raw);
            return n != null && n.isObject() ? n : JsonUtils.getMapper().createObjectNode();
        } catch (Exception _) {
            return JsonUtils.getMapper().createObjectNode();
        }
    }


    static final class BlockAccum {
        private final ContentBlock initial;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder json = new StringBuilder();
        private String signature;

        BlockAccum(ContentBlock initial) {
            this.initial = initial;
            if (initial instanceof ThinkingBlock t) {
                if (t.thinking() != null) text.append(t.thinking());
                signature = t.signature();
            } else if (initial instanceof TextBlock(String s) && s != null) {
                text.append(s);
            }
        }

        void apply(Delta delta) {
            switch (delta) {
                case Delta.TextDelta(String t) -> text.append(t);
                case Delta.ThinkingDelta(String t) -> text.append(t);
                case Delta.SignatureDelta(String s) ->
                    signature = (signature == null ? "" : signature) + s;
                case Delta.InputJsonDelta(String p) -> json.append(p);
                default -> { }
            }
        }

        ContentBlock build() {
            return switch (initial) {
                case ThinkingBlock _ -> new ThinkingBlock(text.toString(), signature);
                case TextBlock _ -> new TextBlock(text.toString());
                case ToolUseBlock tu -> {
                    JsonNode input = json.isEmpty()
                        ? (tu.input() != null ? tu.input() : JsonUtils.getMapper().createObjectNode())
                        : parseJsonObjectOrEmpty(json.toString());
                    yield new ToolUseBlock(tu.id(), tu.name(), input);
                }
                default -> initial;
            };
        }
    }


    static Usage mergeStreamUsage(Usage current, Usage delta) {
        if (delta == null) return current;
        long inTok = delta.inputTokens() > 0 ? delta.inputTokens() : current.inputTokens();
        long cacheRead = delta.cacheReadInputTokens() > 0
            ? delta.cacheReadInputTokens() : current.cacheReadInputTokens();
        long cacheWrite = delta.cacheCreationInputTokens() > 0
            ? delta.cacheCreationInputTokens() : current.cacheCreationInputTokens();
        long outTok = delta.outputTokens() > 0 ? delta.outputTokens() : current.outputTokens();
        return new Usage(inTok, outTok, cacheWrite, cacheRead);
    }

}
