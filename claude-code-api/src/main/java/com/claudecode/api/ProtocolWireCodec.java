package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Stateless {@code 4 x 4} wire protocol translation facade across
 * {@link ModelApiProtocol#ANTHROPIC} (Anthropic Messages),
 * {@link ModelApiProtocol#OPENAI_CHAT} (OpenAI Chat Completions),
 * {@link ModelApiProtocol#OPENAI_RESPONSES} (OpenAI Responses), and
 * {@link ModelApiProtocol#GEMINI} (Google Gemini {@code /v1beta}).
 *
 * <p>Delegates per-protocol parsing, encoding, and live SSE projection to
 * dedicated {@link ProtocolCodec} implementations so new protocols can be added
 * without modifying existing codecs.
 */
@Explanation("Stateless 4x4 bidirectional protocol conversion engine across Anthropic, Chat, Responses, and Gemini")
public final class ProtocolWireCodec {

    private static final Map<ModelApiProtocol, ProtocolCodec> CODECS = buildCodecs();

    private ProtocolWireCodec() {}

    private static Map<ModelApiProtocol, ProtocolCodec> buildCodecs() {
        EnumMap<ModelApiProtocol, ProtocolCodec> map = new EnumMap<>(ModelApiProtocol.class);
        map.put(ModelApiProtocol.ANTHROPIC, new AnthropicWireCodec());
        map.put(ModelApiProtocol.OPENAI_CHAT, new OpenAiChatWireCodec());
        map.put(ModelApiProtocol.OPENAI_RESPONSES, new OpenAiResponsesWireCodec());
        map.put(ModelApiProtocol.GEMINI, new GeminiWireCodec());
        return Map.copyOf(map);
    }

    private static ProtocolCodec codec(ModelApiProtocol protocol) {
        ProtocolCodec resolved = CODECS.get(protocol);
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported wire protocol: " + protocol);
        }
        return resolved;
    }

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
        return codec(sourceProtocol).parseRequest((ObjectNode) root);
    }

    /**
     * Encodes a canonical {@link CreateMessageRequest} into a wire JSON body for
     * {@code targetProtocol}.
     */
    public static String encodeRequest(
            ModelApiProtocol targetProtocol,
            CreateMessageRequest request,
            String targetBaseUrl,
            boolean forClientRelay) {
        try {
            ObjectNode encoded = codec(targetProtocol).encodeRequest(request, targetBaseUrl, forClientRelay);
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
            return codec(sourceProtocol).parseResponse(jsonBody, offeredTools);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Invalid " + sourceProtocol + " response JSON: " + e.getMessage(), 502, e);
        }
    }

    /**
     * Encodes a canonical {@link ApiMessage} into a non-streaming wire JSON response for
     * {@code targetProtocol}.
     */
    public static String encodeResponse(ModelApiProtocol targetProtocol, ApiMessage message) {
        try {
            ObjectNode out = codec(targetProtocol).encodeResponse(message);
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
     * SSE stream of {@code to} protocol.
     */
    public static String translateSseStream(
            ModelApiProtocol from,
            ModelApiProtocol to,
            String upstreamSse,
            String fallbackModel,
            Set<String> offeredTools) {
        EventSourceStreamBridge.EventTranslator translator =
            codec(from).newSseTranslator(fallbackModel, offeredTools);
        List<StreamEvent> events = WireCodecSupport.decodeSseToStreamEvents(translator, upstreamSse);
        return codec(to).encodeSse(events, fallbackModel);
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

    /** Creates a live incremental {@link StreamProjector} for {@code targetProtocol}. */
    public static StreamProjector newStreamProjector(ModelApiProtocol targetProtocol, String fallbackModel) {
        return codec(targetProtocol).newStreamProjector(fallbackModel);
    }

    /** Encodes an input token count into the wire JSON shape expected by {@code targetProtocol}. */
    public static String encodeCountTokensResponse(ModelApiProtocol targetProtocol, long inputTokens) {
        return codec(targetProtocol).encodeCountTokensResponse(inputTokens);
    }

    static boolean hasContentBlockMessages(List<CreateMessageRequest.RequestMessage> requestMessages) {
        return AnthropicWireCodec.hasContentBlockMessages(requestMessages);
    }

    static ArrayNode encodeAnthropicMessagesNode(List<CreateMessageRequest.RequestMessage> requestMessages) {
        return AnthropicWireCodec.encodeAnthropicMessagesNode(requestMessages);
    }

    static ApiMessage aggregateEventsToMessage(List<StreamEvent> events, String fallbackModel) {
        return WireCodecSupport.aggregateEventsToMessage(events, fallbackModel);
    }

    static JsonNode camelCaseGeminiBody(JsonNode node) {
        return GeminiWireCodec.camelCaseGeminiBody(node);
    }

    static String snakeToCamel(String s) {
        return GeminiWireCodec.snakeToCamel(s);
    }
}
