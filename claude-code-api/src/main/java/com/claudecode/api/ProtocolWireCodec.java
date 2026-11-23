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
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
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

/**
 * Stateless {@code 4 x 4} wire protocol translation engine across
 * {@link ModelApiProtocol#ANTHROPIC} (Anthropic Messages),
 * {@link ModelApiProtocol#OPENAI_CHAT} (OpenAI Chat Completions),
 * {@link ModelApiProtocol#OPENAI_RESPONSES} (OpenAI Responses), and
 * {@link ModelApiProtocol#GEMINI} (Google Gemini {@code /v1beta}).
 *
 * <p>Translates inbound wire requests into a canonical intermediate representation
 * ({@link CreateMessageRequest}), emits target-protocol wire requests with full
 * tool-call self-healing, {@code claudeseal} / {@code thoughtSignature} preservation,
 * and vendor schema normalization, and translates both unary JSON responses and SSE
 * streams across any pair of protocols.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code internal/gateway/ir.go},
 * {@code anthropic.go}, {@code chat.go}, {@code responses.go}, and {@code gemini.go}).
 */
@Explanation("Stateless 4x4 bidirectional protocol conversion engine across Anthropic, Chat, Responses, and Gemini")
public final class ProtocolWireCodec {

    private static final Set<String> GEMINI_CALLER_OWN_KEYS = Set.of(
        "args", "response", "parameters", "parametersJsonSchema",
        "responseSchema", "responseJsonSchema", "labels"
    );

    private ProtocolWireCodec() {}

    /**
     * Parses a wire request body of {@code sourceProtocol} into the canonical
     * {@link CreateMessageRequest} intermediate representation.
     */
    public static CreateMessageRequest parseRequest(ModelApiProtocol sourceProtocol, String jsonBody) {
        JsonNode root;
        try {
            root = JsonUtils.parseTree(jsonBody);
        } catch (RuntimeException e) {
            throw new ApiException("Invalid " + sourceProtocol + " request JSON: " + e.getMessage(), 400, e);
        }
        if (root == null || !root.isObject()) {
            throw new ApiException("Request body must be a JSON object", 400);
        }
        return switch (sourceProtocol) {
            case ANTHROPIC -> parseAnthropicRequest((ObjectNode) root);
            case OPENAI_CHAT -> parseChatRequest((ObjectNode) root);
            case OPENAI_RESPONSES -> parseResponsesRequest((ObjectNode) root);
            case GEMINI -> parseGeminiRequest((ObjectNode) camelCaseGeminiBody((ObjectNode) root));
        };
    }

    /**
     * Encodes a canonical {@link CreateMessageRequest} into a wire JSON body for
     * {@code targetProtocol}.
     *
     * @param forClientRelay when {@code true} and targeting {@link ModelApiProtocol#OPENAI_RESPONSES},
     *        Anthropic signed {@code thinking} and {@code redacted_thinking} blocks are sealed via
     *        {@link ClaudeSeal}; when {@code false} (sending to a native OpenAI upstream), any
     *        {@code magpie-claude1:} payload is stripped so OpenAI does not reject it.
     */
    public static String encodeRequest(
            ModelApiProtocol targetProtocol,
            CreateMessageRequest request,
            String targetBaseUrl,
            boolean forClientRelay) {
        try {
            ObjectNode encoded = switch (targetProtocol) {
                case ANTHROPIC -> encodeAnthropicRequest(request);
                case OPENAI_CHAT -> OpenAiCompatClient.buildChatRequestNode(
                    request, request.model(), request.stream(), targetBaseUrl);
                case OPENAI_RESPONSES -> OpenAiResponsesClient.buildResponsesRequestNode(
                    request, request.model(), request.stream(), targetBaseUrl, forClientRelay);
                case GEMINI -> GeminiClient.buildGeminiRequestBody(
                    request, GeminiClient.cleanModelPathId(request.model()));
            };
            return JsonUtils.getMapper().writeValueAsString(encoded);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Failed to encode " + targetProtocol + " request: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Translates a wire request JSON string from {@code from} protocol to {@code to} protocol.
     */
    public static String translateRequest(
            ModelApiProtocol from, ModelApiProtocol to, String jsonBody, String targetBaseUrl) {
        CreateMessageRequest ir = parseRequest(from, jsonBody);
        return encodeRequest(to, ir, targetBaseUrl, false);
    }

    /**
     * Adapts cross-protocol {@code thinking} / {@code effort} fields for Anthropic Messages
     * upstreams: upgrades budget-based {@code thinking.type="enabled"} to {@code "adaptive"}
     * on models requiring adaptive thinking (e.g. Claude 4.6+ / 5.x such as
     * {@code anthropic.claude-haiku-5-5}), and nests {@code effort} inside {@code output_config}
     * with OpenAI's {@code "xhigh"} mapped to Anthropic's {@code "max"}.
     */
    public static CreateMessageRequest adaptForAnthropicUpstream(CreateMessageRequest request) {
        if (request == null) return null;
        CreateMessageRequest.ThinkingConfig thinking = request.thinking();
        boolean adaptiveModel = CreateMessageRequest.supportsAdaptiveThinking(request.model());
        String rawEffort = ReasoningNormalizer.resolveExplicitOrBudgetEffort(request);
        String anthropicEffort = Strings.CS.equals("xhigh", rawEffort) ? "max" : rawEffort;

        boolean upgradeToAdaptive = adaptiveModel
            && thinking != null
            && Strings.CS.equals("enabled", thinking.type());
        if (upgradeToAdaptive) {
            thinking = CreateMessageRequest.ThinkingConfig.adaptive();
        }

        CreateMessageRequest.OutputConfig outCfg = request.outputConfig();
        if (StringUtils.isNotBlank(anthropicEffort)) {
            JsonNode fmt = outCfg != null ? outCfg.format() : null;
            Object taskBudget = outCfg != null ? outCfg.taskBudget() : null;
            outCfg = new CreateMessageRequest.OutputConfig(anthropicEffort, fmt, taskBudget);
        } else if (outCfg != null && Strings.CS.equals("xhigh", outCfg.effort())) {
            outCfg = new CreateMessageRequest.OutputConfig("max", outCfg.format(), outCfg.taskBudget());
        }

        if (!upgradeToAdaptive && request.effort() == null && outCfg == request.outputConfig()) {
            return request;
        }
        return new CreateMessageRequest(
            request.model(),
            request.maxTokens(),
            request.systemPrompt(),
            request.messages(),
            request.tools(),
            request.metadata(),
            request.stopSequences(),
            request.stream(),
            request.temperature(),
            request.topP(),
            request.topK(),
            thinking,
            null,
            request.toolChoice(),
            outCfg,
            request.speed(),
            request.contextManagement(),
            request.skipCacheWrite(),
            request.promptCachingEnabled(),
            request.promptCacheTtl(),
            request.querySource(),
            request.cancellationRegistrar(),
            request.subagent(),
            request.streamConnectTimeout());
    }

    /**
     * Parses a non-streaming wire response JSON string from {@code sourceProtocol} into the
     * canonical {@link ApiMessage}, rescuing text-written tool calls when {@code offeredTools}
     * is non-empty.
     */
    public static ApiMessage parseResponse(
            ModelApiProtocol sourceProtocol, String jsonBody, Set<String> offeredTools) {
        try {
            return switch (sourceProtocol) {
                case ANTHROPIC -> parseAnthropicResponse(jsonBody, offeredTools);
                case OPENAI_CHAT -> OpenAiCompatClient.parseChatResponseStatic(jsonBody, "", offeredTools);
                case OPENAI_RESPONSES -> OpenAiResponsesClient.parseResponsesBodyStatic(jsonBody, "", offeredTools);
                case GEMINI -> GeminiClient.parseUnaryResponse(jsonBody, "", offeredTools);
            };
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Invalid " + sourceProtocol + " response JSON: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Encodes a canonical {@link ApiMessage} into a non-streaming wire JSON response for
     * {@code targetProtocol}. When targeting {@link ModelApiProtocol#OPENAI_RESPONSES},
     * signed Anthropic thinking and {@code redacted_thinking} blocks are sealed via
     * {@link ClaudeSeal} so stateless Responses clients (e.g. Codex with {@code store:false})
     * carry them back intact on the next tool turn.
     */
    public static String encodeResponse(ModelApiProtocol targetProtocol, ApiMessage message) {
        try {
            ObjectNode out = switch (targetProtocol) {
                case ANTHROPIC -> encodeAnthropicResponse(message);
                case OPENAI_CHAT -> encodeChatResponse(message);
                case OPENAI_RESPONSES -> encodeResponsesResponse(message);
                case GEMINI -> encodeGeminiResponse(message);
            };
            return JsonUtils.getMapper().writeValueAsString(out);
        } catch (Exception e) {
            throw new ApiException("Failed to encode " + targetProtocol + " response: " + e.getMessage(), 500, e);
        }
    }

    /**
     * Translates a non-streaming response body from {@code from} protocol to {@code to} protocol.
     */
    public static String translateResponse(
            ModelApiProtocol from, ModelApiProtocol to, String jsonBody, Set<String> offeredTools) {
        ApiMessage ir = parseResponse(from, jsonBody, offeredTools);
        return encodeResponse(to, ir);
    }

    /**
     * Translates an entire upstream SSE stream from {@code from} protocol into a client-shaped
     * SSE stream of {@code to} protocol (including {@code sequence_number}/{@code created_at}
     * on Responses, {@code data: [DONE]} on Chat, {@code event: ...} on Anthropic, and data-only
     * candidate chunks on Gemini).
     */
    public static String translateSseStream(
            ModelApiProtocol from,
            ModelApiProtocol to,
            String upstreamSse,
            String fallbackModel,
            Set<String> offeredTools) {
        List<StreamEvent> events = decodeSseToStreamEvents(from, upstreamSse, fallbackModel, offeredTools);
        return encodeStreamEventsToSse(to, events, fallbackModel);
    }

    /**
     * Stateful incremental SSE frame projector from canonical {@link StreamEvent}s to a target
     * protocol's wire SSE chunks, used by live streaming gateways for zero-buffering forwarding.
     */
    public interface StreamProjector {
        /** Projects one incremental {@link StreamEvent} into zero or more SSE frames (ending in {@code \n\n}). */
        List<String> onEvent(StreamEvent event);

        /** Emits any closing SSE frames when the stream completes. */
        List<String> finish();

        /** Accumulated token usage observed across the stream. */
        Usage usage();

        /** True once at least one text, thinking, or tool-call delta has been projected. */
        boolean emittedContent();
    }

    /**
     * Creates a live incremental {@link StreamProjector} for {@code targetProtocol}.
     */
    public static StreamProjector newStreamProjector(ModelApiProtocol targetProtocol, String fallbackModel) {
        return switch (targetProtocol) {
            case ANTHROPIC -> new AnthropicLiveProjector();
            case OPENAI_CHAT -> new ChatLiveProjector(fallbackModel);
            case OPENAI_RESPONSES -> new ResponsesLiveProjector(fallbackModel);
            case GEMINI -> new GeminiLiveProjector(fallbackModel);
        };
    }

    /**
     * Encodes an input token count into the wire JSON shape expected by {@code targetProtocol}
     * ({@code {"input_tokens": N}} for Anthropic, {@code {"totalTokens": N}} for Gemini).
     */
    public static String encodeCountTokensResponse(ModelApiProtocol targetProtocol, long inputTokens) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        if (targetProtocol == ModelApiProtocol.GEMINI) {
            out.put("totalTokens", inputTokens);
        } else {
            out.put("input_tokens", inputTokens);
        }
        return out.toString();
    }

    // ── Inbound Request Parsers (Wire -> IR) ──────────────────────────────────

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

        JsonNode fmt = parseOpenAiFormat(root.get("response_format"));
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
                            JsonNode args = parseJsonObjectOrEmpty(fn.path("arguments").asText("{}"));
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

        JsonNode fmt = parseOpenAiFormat(root.path("text").get("format"));
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
                    JsonNode args = parseJsonObjectOrEmpty(item.path("arguments").asText("{}"));
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

    private static JsonNode camelGeminiKeys(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode arr = JsonUtils.getMapper().createArrayNode();
            for (JsonNode item : node) {
                arr.add(camelGeminiKeys(item));
            }
            return arr;
        }
        if (!node.isObject()) return node;
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        node.properties().forEach(entry -> {
            String camel = snakeToCamel(entry.getKey());
            JsonNode val = GEMINI_USER_OWNED_FIELDS.contains(camel)
                ? entry.getValue().deepCopy()
                : camelGeminiKeys(entry.getValue());
            out.set(camel, val);
        });
        return out;
    }

    private static CreateMessageRequest parseGeminiRequest(ObjectNode rawRoot) {
        ObjectNode root = (ObjectNode) camelGeminiKeys(rawRoot);
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

    private static List<StreamEvent> decodeSseToStreamEvents(
            ModelApiProtocol from, String rawSse, String fallbackModel, Set<String> offeredTools) {
        List<StreamEvent> events = new ArrayList<>();
        EventSourceStreamBridge.EventTranslator translator = switch (from) {
            case ANTHROPIC -> new AnthropicSseEventTranslator(offeredTools);
            case OPENAI_CHAT -> new OpenAiCompatClient.ChunkStreamTranslator(offeredTools);
            case OPENAI_RESPONSES -> new OpenAiResponsesClient.ResponsesEventTranslator(offeredTools);
            case GEMINI -> new GeminiSseAdapter(fallbackModel, offeredTools);
        };

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

    private static String encodeStreamEventsToSse(
            ModelApiProtocol to, List<StreamEvent> events, String fallbackModel) {
        return switch (to) {
            case ANTHROPIC -> {
                StringBuilder sb = new StringBuilder();
                for (StreamEvent ev : events) {
                    StreamEventSseCodec.EncodedEvent enc = StreamEventSseCodec.encode(ev);
                    if (enc != null) {
                        sb.append("event: ").append(enc.type()).append("\ndata: ").append(enc.data()).append("\n\n");
                    }
                }
                yield sb.toString();
            }
            case OPENAI_CHAT -> encodeChatSse(events, fallbackModel);
            case OPENAI_RESPONSES -> encodeResponsesSse(events, fallbackModel);
            case GEMINI -> encodeGeminiSse(events, fallbackModel);
        };
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
        }
        sb.append("data: ").append(chunk).append("\n\n");
    }

    private static String encodeResponsesSse(List<StreamEvent> events, String fallbackModel) {
        ApiMessage aggregated = aggregateEventsToMessage(events, fallbackModel);
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

    private static String encodeGeminiSse(List<StreamEvent> events, String fallbackModel) {
        ApiMessage aggregated = aggregateEventsToMessage(events, fallbackModel);
        ObjectNode unary = encodeGeminiResponse(aggregated);
        return "data: " + unary + "\n\n";
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

    static JsonNode camelCaseGeminiBody(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isArray()) {
            ArrayNode arr = JsonUtils.getMapper().createArrayNode();
            for (JsonNode child : node) arr.add(camelCaseGeminiBody(child));
            return arr;
        }
        if (!node.isObject()) return node;
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
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

    static String snakeToCamel(String s) {
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
                ContentBlock img = dataOrRemoteUrlToBlock(url, true);
                if (img != null) out.add(img);
            } else if (Strings.CS.equals("file", type)) {
                JsonNode f = part.path("file");
                String data = f.path("file_data").asText("");
                ContentBlock doc = dataOrRemoteUrlToBlock(data, false);
                if (doc != null) out.add(doc);
            }
        }
        return List.copyOf(out);
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
                    ContentBlock img = dataOrRemoteUrlToBlock(url, true);
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
                return dataOrRemoteUrlToBlock(fileData, false);
            }
            ObjectNode src = JsonUtils.getMapper().createObjectNode();
            src.put("type", "base64");
            src.put("media_type", "application/pdf");
            src.put("data", fileData);
            return new DocumentBlock(src);
        }
        String fileUrl = item.path("file_url").asText("");
        if (StringUtils.isNotEmpty(fileUrl)) {
            return dataOrRemoteUrlToBlock(fileUrl, false);
        }
        return null;
    }

    private static ContentBlock dataOrRemoteUrlToBlock(String url, boolean defaultImage) {
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

    private static JsonNode parseOpenAiFormat(JsonNode fmt) {
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

    private static JsonNode parseJsonObjectOrEmpty(String raw) {
        if (StringUtils.isBlank(raw)) return JsonUtils.getMapper().createObjectNode();
        try {
            JsonNode n = JsonUtils.getMapper().readTree(raw);
            return n != null && n.isObject() ? n : JsonUtils.getMapper().createObjectNode();
        } catch (Exception _) {
            return JsonUtils.getMapper().createObjectNode();
        }
    }

    private static final class BlockAccum {
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

    private static Usage mergeStreamUsage(Usage current, Usage delta) {
        if (delta == null) return current;
        long inTok = delta.inputTokens() > 0 ? delta.inputTokens() : current.inputTokens();
        long cacheRead = delta.cacheReadInputTokens() > 0
            ? delta.cacheReadInputTokens() : current.cacheReadInputTokens();
        long cacheWrite = delta.cacheCreationInputTokens() > 0
            ? delta.cacheCreationInputTokens() : current.cacheCreationInputTokens();
        long outTok = delta.outputTokens() > 0 ? delta.outputTokens() : current.outputTokens();
        return new Usage(inTok, outTok, cacheWrite, cacheRead);
    }

    private static final class AnthropicLiveProjector implements StreamProjector {
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;

        @Override
        public List<String> onEvent(StreamEvent event) {
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (ms.message().usage() != null) usage = mergeStreamUsage(usage, ms.message().usage());
                }
                case StreamEvent.ContentBlockDelta _ -> contentEmitted = true;
                case StreamEvent.MessageDelta md -> usage = mergeStreamUsage(usage, md.usage());
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

    private static final class ChatLiveProjector implements StreamProjector {
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
                    if (ms.message().usage() != null) usage = mergeStreamUsage(usage, ms.message().usage());
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
                    usage = mergeStreamUsage(usage, md.usage());
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

    private static final class ResponsesLiveProjector implements StreamProjector {
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
        private final Map<Integer, BlockAccum> activeBlocks = new LinkedHashMap<>();
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
                    if (ms.message().usage() != null) usage = mergeStreamUsage(usage, ms.message().usage());
                    ensureCreated(sb);
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    ensureCreated(sb);
                    int outIdx = nextOutputIdx++;
                    activeBlocks.put(cbs.index(), new BlockAccum(cbs.contentBlock()));
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
                    BlockAccum acc = activeBlocks.get(cbd.index());
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
                    BlockAccum acc = activeBlocks.remove(cbStop.index());
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
                    usage = mergeStreamUsage(usage, md.usage());
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

    private static final class GeminiLiveProjector implements StreamProjector {
        private String responseId = "gem_" + UUID.randomUUID().toString().substring(0, 12);
        private String model;
        private String stopReason = "end_turn";
        private Usage usage = Usage.EMPTY;
        private boolean contentEmitted;
        private boolean finished;
        private final Map<Integer, BlockAccum> toolBlocks = new LinkedHashMap<>();

        GeminiLiveProjector(String fallbackModel) {
            this.model = StringUtils.defaultIfBlank(fallbackModel, "");
        }

        @Override
        public List<String> onEvent(StreamEvent event) {
            switch (event) {
                case StreamEvent.MessageStart ms -> {
                    if (StringUtils.isNotEmpty(ms.message().id())) responseId = ms.message().id();
                    if (StringUtils.isNotEmpty(ms.message().model())) model = ms.message().model();
                    if (ms.message().usage() != null) usage = mergeStreamUsage(usage, ms.message().usage());
                    return List.of();
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    if (cbs.contentBlock() instanceof ToolUseBlock) {
                        contentEmitted = true;
                        toolBlocks.put(cbs.index(), new BlockAccum(cbs.contentBlock()));
                    }
                    return List.of();
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    contentEmitted = true;
                    BlockAccum toolAcc = toolBlocks.get(cbd.index());
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
                    BlockAccum acc = toolBlocks.remove(cbStop.index());
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
                    usage = mergeStreamUsage(usage, md.usage());
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
