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
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.http.HttpCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Google Gemini ({@code /v1beta}) wire adapter implementing {@link LlmClient}.
 *
 * <p>Supports {@code :generateContent}, {@code :streamGenerateContent?alt=sse}, and
 * {@code :countTokens}, including {@code thoughtSignature} round-trip preservation,
 * function-turn pairing and history hardening, tool schema normalization, and
 * malformed/text-written function-call rescue.
 *
 * <p>Replicated from {@code yetone/magpie} ({@code internal/gateway/gemini.go} and
 * {@code internal/gateway/codeassist.go}).
 */
@Explanation("Google Gemini /v1beta wire adapter with thoughtSignature and schema compatibility")
public final class GeminiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String NAME_LEAD = "_x_";
    private static final String GEMINI_NAMESPACE = "default_api";

    private final ApiConfig.OpenAiConfig config;
    private final String basePrefix;
    private final OkHttpClient streamingHttpClient;
    private final OkHttpClient nonStreamingHttpClient;

    public GeminiClient(ApiConfig.OpenAiConfig config) {
        this(config, HttpClientFactory.openAiStreaming(), HttpClientFactory.openAiNonStreaming());
    }

    public GeminiClient(ApiConfig.OpenAiConfig config, OkHttpClient httpClient) {
        this(config, httpClient, httpClient);
    }

    GeminiClient(
            ApiConfig.OpenAiConfig config,
            OkHttpClient streamingHttpClient,
            OkHttpClient nonStreamingHttpClient) {
        this.config = config;
        this.streamingHttpClient = streamingHttpClient;
        this.nonStreamingHttpClient = nonStreamingHttpClient;
        this.basePrefix = normalizeBasePrefix(config.baseUrl());
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request, Runnable onRequestSubmitted) {
        String model = resolveModelId(request);
        String url = actionUrl(basePrefix, model, "streamGenerateContent") + "?alt=sse";
        log.info("Gemini streaming request to {} model {}", url, model);
        try {
            Request httpRequest = buildHttpRequest(url, buildGeminiRequestBody(request, model), request, true);
            Set<String> offeredTools = TextCallRescue.offeredToolNames(request.tools());
            return EventSourceStreamBridge.connect(
                streamingHttpClient,
                httpRequest,
                new GeminiStreamTranslator(model, offeredTools),
                ApiTimeouts.streamConnectTimeout(request),
                ApiTimeouts.watchdog(),
                request.cancellationRegistrar(),
                onRequestSubmitted,
                ApiTimeouts.byteWatchdog(ApiConfig.ApiProvider.OPENAI_COMPAT, config.baseUrl()));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Gemini streaming request failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        return createMessage(request, 0);
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        String model = resolveModelId(request);
        String url = actionUrl(basePrefix, model, "generateContent");
        log.info("Gemini request to {} model {}", url, model);
        try {
            Request httpRequest = buildHttpRequest(url, buildGeminiRequestBody(request, model), request, false);
            Duration timeout = timeoutMillis > 0
                ? Duration.ofMillis(timeoutMillis) : ApiTimeouts.apiTimeout();
            try (Response response = request.cancellationRegistrar() != null
                    ? HttpCalls.execute(nonStreamingHttpClient, httpRequest, timeout, request.cancellationRegistrar())
                    : HttpCalls.execute(nonStreamingHttpClient, httpRequest, timeout)) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw apiError(response.code(), responseBody);
                }
                Set<String> offeredTools = TextCallRescue.offeredToolNames(request.tools());
                ApiMessage message = parseUnaryResponse(responseBody, model, offeredTools);
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
            throw new ApiException("Gemini request failed: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public long countTokens(
            String model,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools) {
        String effectiveModel = StringUtils.isNotBlank(model) ? cleanModelPathId(model) : cleanModelPathId(config.model());
        String url = actionUrl(basePrefix, effectiveModel, "countTokens");
        try {
            CreateMessageRequest.Builder reqBuilder = CreateMessageRequest.builder()
                .model(effectiveModel)
                .messages(messages != null ? messages : List.of());
            if (tools != null) {
                reqBuilder.tools(tools);
            }
            CreateMessageRequest req = reqBuilder.build();
            ObjectNode body = buildGeminiRequestBody(req, effectiveModel);
            body.remove("generationConfig");
            body.remove("toolConfig");
            body.remove("tools");
            body.remove("systemInstruction");
            Request httpRequest = buildHttpRequest(url, body, req, false);
            try (Response response = HttpCalls.execute(
                    nonStreamingHttpClient, httpRequest, ApiTimeouts.apiTimeout())) {
                if (!response.isSuccessful()) return -1;
                JsonNode root = JsonUtils.getMapper().readTree(response.body().string());
                return root.path("totalTokens").asLong(-1);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception _) {
            return -1;
        }
    }

    @Override
    public String getModel() {
        return config.model();
    }

    private String resolveModelId(CreateMessageRequest request) {
        String raw = request != null && StringUtils.isNotBlank(request.model())
            ? request.model() : config.model();
        return cleanModelPathId(ModelNames.normalizeModelStringForApi(raw));
    }

    static String cleanModelPathId(String model) {
        if (StringUtils.isBlank(model)) return "gemini-2.5-pro";
        String trimmed = model.trim();
        if (Strings.CS.startsWith(trimmed, "models/")) {
            trimmed = trimmed.substring("models/".length());
        }
        return trimmed;
    }

    static String normalizeBasePrefix(String baseUrl) {
        String base = StringUtils.isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl.trim();
        if (!Strings.CS.contains(base, "://")) {
            base = "https://" + base;
        }
        int qIdx = base.indexOf('?');
        if (qIdx >= 0) base = base.substring(0, qIdx);
        int hIdx = base.indexOf('#');
        if (hIdx >= 0) base = base.substring(0, hIdx);
        while (Strings.CS.endsWith(base, "/")) {
            base = base.substring(0, base.length() - 1);
        }
        int modelsIdx = base.indexOf("/models/");
        if (modelsIdx > 0) {
            base = base.substring(0, modelsIdx);
        } else if (Strings.CS.endsWith(base, "/models")) {
            base = base.substring(0, base.length() - "/models".length());
        }
        try {
            URI uri = URI.create(base);
            String path = uri.getPath();
            if (StringUtils.isEmpty(path) || Strings.CS.equals("/", path)) {
                return base + "/v1beta";
            }
            return base;
        } catch (IllegalArgumentException _) {
            if (Strings.CS.endsWith(base, "/v1beta")
                    || Strings.CS.endsWith(base, "/v1")
                    || Strings.CS.endsWith(base, "/v1alpha")) {
                return base;
            }
            return base + "/v1beta";
        }
    }

    static String actionUrl(String basePrefix, String model, String action) {
        if (Strings.CS.endsWith(basePrefix, "/models")) {
            return basePrefix + "/" + model + ":" + action;
        }
        return basePrefix + "/models/" + model + ":" + action;
    }

    private Request buildHttpRequest(
            String url, ObjectNode body, CreateMessageRequest request, boolean streaming) throws Exception {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("Accept", streaming ? "text/event-stream" : "application/json")
            .tag(RetryRequestPolicy.class,
                RetryRequestPolicy.forQuerySource(request != null ? request.querySource() : null));
        if (StringUtils.isNotBlank(config.apiKey())) {
            if (Strings.CS.startsWith(config.apiKey(), "ya29.")) {
                builder.header("Authorization", "Bearer " + config.apiKey());
            } else {
                builder.header("x-goog-api-key", config.apiKey());
            }
        }
        config.headers().forEach(builder::header);
        return builder.post(RequestBody.create(JsonUtils.getMapper().writeValueAsBytes(body), JSON)).build();
    }

    static ObjectNode buildGeminiRequestBody(CreateMessageRequest request, String model) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();

        String systemPrompt = stripInternalBoundary(request.systemPrompt());
        if (StringUtils.isNotBlank(systemPrompt)) {
            ObjectNode sys = root.putObject("systemInstruction");
            sys.putArray("parts").addObject().put("text", systemPrompt);
        }

        List<ToolPairingHealer.HealedTurn> turns = ToolPairingHealer.healRequestMessages(request.messages());
        Map<String, String> callNamesById = new LinkedHashMap<>();
        ArrayNode contents = JsonUtils.getMapper().createArrayNode();

        for (ToolPairingHealer.HealedTurn turn : turns) {
            if (Strings.CS.equals("assistant", turn.role())) {
                ArrayNode parts = buildModelParts(turn.blocks(), callNamesById);
                if (!parts.isEmpty()) {
                    appendOrMergeContent(contents, "model", parts, false);
                }
            } else if (Strings.CS.equals("tool_results", turn.role())) {
                ArrayNode responseParts = buildFunctionResponseParts(turn.blocks(), callNamesById);
                if (!responseParts.isEmpty()) {
                    appendOrMergeContent(contents, "user", responseParts, true);
                }
            } else {
                ArrayNode userParts = buildUserParts(turn.blocks());
                if (!userParts.isEmpty()) {
                    appendOrMergeContent(contents, "user", userParts, false);
                }
            }
        }

        // Harden history: Gemini rejects conversations beginning or ending with a model turn.
        if (contents.isEmpty()) {
            ObjectNode emptyUser = contents.addObject();
            emptyUser.put("role", "user");
            emptyUser.putArray("parts").addObject().put("text", "");
        } else {
            if (Strings.CS.equals("model", contents.get(0).path("role").asText())) {
                ObjectNode leadUser = JsonUtils.getMapper().createObjectNode();
                leadUser.put("role", "user");
                leadUser.putArray("parts").addObject().put("text", "[Continuing from previous AI thoughts...]");
                contents.insert(0, leadUser);
            }
            if (Strings.CS.equals("model", contents.get(contents.size() - 1).path("role").asText())) {
                ObjectNode tailUser = contents.addObject();
                tailUser.put("role", "user");
                tailUser.putArray("parts").addObject().put("text", "Continue.");
            }
        }
        root.set("contents", contents);

        boolean toolChoiceNone = request.toolChoice() != null
            && Strings.CS.equals("none", request.toolChoice().type());
        if (request.tools() != null && !request.tools().isEmpty() && !toolChoiceNone) {
            ArrayNode decls = JsonUtils.getMapper().createArrayNode();
            for (CreateMessageRequest.ToolDefinition tool : request.tools()) {
                ObjectNode decl = decls.addObject();
                decl.put("name", toGeminiFuncName(tool.name()));
                if (StringUtils.isNotBlank(tool.description())) {
                    decl.put("description", tool.description());
                }
                decl.set("parametersJsonSchema", VendorSchemaSanitizer.normalizeForGemini(tool.inputSchema()));
            }
            ArrayNode toolsArr = root.putArray("tools");
            toolsArr.addObject().set("functionDeclarations", decls);

            ObjectNode fcConfig = root.putObject("toolConfig").putObject("functionCallingConfig");
            if (request.toolChoice() != null) {
                String tcType = request.toolChoice().type();
                if (Strings.CS.equals("any", tcType) || Strings.CS.equals("required", tcType)) {
                    fcConfig.put("mode", "ANY");
                } else if (Strings.CS.equals("tool", tcType)
                        && StringUtils.isNotBlank(request.toolChoice().name())) {
                    fcConfig.put("mode", "ANY");
                    fcConfig.putArray("allowedFunctionNames").add(toGeminiFuncName(request.toolChoice().name()));
                } else {
                    fcConfig.put("mode", "AUTO");
                }
            } else {
                fcConfig.put("mode", "AUTO");
            }
        } else if (toolChoiceNone && request.tools() != null && !request.tools().isEmpty()) {
            root.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "NONE");
        }

        ObjectNode gen = buildGenerationConfig(request, model);
        if (!gen.isEmpty()) {
            root.set("generationConfig", gen);
        }
        return root;
    }

    private static void appendOrMergeContent(
            ArrayNode contents, String role, ArrayNode newParts, boolean isFunctionResponse) {
        if (!contents.isEmpty()) {
            ObjectNode prev = (ObjectNode) contents.get(contents.size() - 1);
            if (Strings.CS.equals(role, prev.path("role").asText())) {
                ArrayNode prevParts = (ArrayNode) prev.path("parts");
                boolean prevHasFuncResp = hasFunctionResponse(prevParts);
                if (!Strings.CS.equals("user", role) || prevHasFuncResp == isFunctionResponse) {
                    prevParts.addAll(newParts);
                    return;
                }
            }
        }
        ObjectNode node = contents.addObject();
        node.put("role", role);
        node.set("parts", newParts);
    }

    private static boolean hasFunctionResponse(ArrayNode parts) {
        for (JsonNode p : parts) {
            if (p.has("functionResponse")) return true;
        }
        return false;
    }

    private static ArrayNode buildModelParts(List<ContentBlock> blocks, Map<String, String> callNamesById) {
        ArrayNode parts = JsonUtils.getMapper().createArrayNode();
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
                case ThinkingBlock thinking -> {
                    if (StringUtils.isNotEmpty(thinking.thinking())) {
                        ObjectNode p = parts.addObject();
                        p.put("text", thinking.thinking());
                        p.put("thought", true);
                    }
                }
                case RedactedThinkingBlock _ -> {
                    // Omit Claude-specific redacted thinking blocks when addressing Gemini.
                }
                case TextBlock(String text) -> {
                    if (StringUtils.isNotEmpty(text)) {
                        parts.addObject().put("text", text);
                    }
                }
                case ImageBlock(JsonNode source) -> appendMediaPart(parts, source);
                case DocumentBlock(JsonNode source) -> appendMediaPart(parts, source);
                case ToolUseBlock toolUse -> {
                    GeminiThoughtSignature.UnsignedToolId unsigned =
                        GeminiThoughtSignature.unsignToolId(toolUse.id());
                    callNamesById.put(toolUse.id(), toolUse.name());
                    callNamesById.put(unsigned.id(), toolUse.name());

                    ObjectNode part = parts.addObject();
                    ObjectNode fc = part.putObject("functionCall");
                    if (StringUtils.isNotEmpty(unsigned.id())) {
                        fc.put("id", unsigned.id());
                    }
                    fc.put("name", toGeminiFuncName(toolUse.name()));
                    fc.set("args", toolUse.input() != null && toolUse.input().isObject()
                        ? toolUse.input().deepCopy()
                        : JsonUtils.getMapper().createObjectNode());

                    if (StringUtils.isNotEmpty(unsigned.signature())) {
                        part.put("thoughtSignature", unsigned.signature());
                    } else if (!stepSigned) {
                        part.put("thoughtSignature", GeminiThoughtSignature.SKIP_SIGNATURE);
                    }
                }
                default -> { }
            }
        }
        return parts;
    }

    private static ArrayNode buildFunctionResponseParts(
            List<ContentBlock> blocks, Map<String, String> callNamesById) {
        ArrayNode parts = JsonUtils.getMapper().createArrayNode();
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolResultBlock tr)) continue;
            GeminiThoughtSignature.UnsignedToolId unsigned =
                GeminiThoughtSignature.unsignToolId(tr.toolUseId());
            String rawName = callNamesById.getOrDefault(tr.toolUseId(),
                callNamesById.getOrDefault(unsigned.id(), "tool"));
            ObjectNode fr = parts.addObject().putObject("functionResponse");
            if (StringUtils.isNotEmpty(unsigned.id())) {
                fr.put("id", unsigned.id());
            }
            fr.put("name", toGeminiFuncName(rawName));
            String key = tr.isError() ? "error" : "output";
            fr.putObject("response").put(key, OpenAiWireSupport.textContent(tr.content()));
            if (tr.content() != null) {
                for (ContentBlock child : tr.content()) {
                    if (child instanceof ImageBlock(JsonNode src)) {
                        appendMediaPart(parts, src);
                    } else if (child instanceof DocumentBlock(JsonNode src)) {
                        appendMediaPart(parts, src);
                    }
                }
            }
        }
        return parts;
    }

    private static ArrayNode buildUserParts(List<ContentBlock> blocks) {
        ArrayNode parts = JsonUtils.getMapper().createArrayNode();
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock(String text) -> parts.addObject().put("text", text);
                case ImageBlock(JsonNode source) -> appendMediaPart(parts, source);
                case DocumentBlock(JsonNode source) -> appendMediaPart(parts, source);
                default -> { }
            }
        }
        return parts;
    }

    static void appendMediaPart(ArrayNode parts, JsonNode source) {
        if (source == null || source.isNull()) return;
        String srcType = source.path("type").asText("");
        if (Strings.CS.equals("base64", srcType)) {
            String mime = source.path("media_type").asText("application/octet-stream");
            String data = source.path("data").asText("");
            int comma = data.indexOf(";base64,");
            if (Strings.CS.startsWith(data, "data:") && comma > 0) {
                mime = data.substring("data:".length(), comma);
                data = data.substring(comma + ";base64,".length());
            }
            ObjectNode inline = parts.addObject().putObject("inlineData");
            inline.put("mimeType", mime);
            inline.put("data", data);
        } else if (Strings.CS.equals("url", srcType)) {
            String url = source.path("url").asText("");
            if (StringUtils.isNotEmpty(url)) {
                ObjectNode fd = parts.addObject().putObject("fileData");
                fd.put("mimeType", source.path("media_type").asText("application/octet-stream"));
                fd.put("fileUri", url);
            }
        }
    }

    private static ObjectNode buildGenerationConfig(CreateMessageRequest request, String model) {
        ObjectNode gen = JsonUtils.getMapper().createObjectNode();
        if (request.maxTokens() > 0) {
            gen.put("maxOutputTokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            gen.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            gen.put("topP", request.topP());
        }
        if (request.topK() != null) {
            gen.put("topK", request.topK());
        }
        if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
            gen.set("stopSequences", JsonUtils.getMapper().valueToTree(request.stopSequences()));
        }

        if (request.thinking() != null && Strings.CS.equals("disabled", request.thinking().type())) {
            gen.putObject("thinkingConfig").put("thinkingBudget", 0);
        } else {
            String effort = ReasoningNormalizer.resolveEffectiveEffort(request);
            if (StringUtils.isNotBlank(effort)) {
                ObjectNode tc = gen.putObject("thinkingConfig");
                tc.put("includeThoughts", true);
                if (isGeminiGenerationThreeOrNewer(model)) {
                    tc.put("thinkingLevel", toGeminiThinkingLevel(effort, model));
                } else {
                    Integer rawBudget = request.thinking() != null ? request.thinking().budgetTokens() : null;
                    int budget = rawBudget != null && rawBudget > 0
                        ? rawBudget
                        : ReasoningNormalizer.budgetOfEffort(effort);
                    tc.put("thinkingBudget", budget);
                }
            }
        }

        if (request.outputConfig() != null && request.outputConfig().format() != null) {
            JsonNode fmt = request.outputConfig().format();
            String fmtType = fmt.path("type").asText("");
            if (Strings.CS.equals("json_schema", fmtType) || Strings.CS.equals("json_object", fmtType)) {
                gen.put("responseMimeType", "application/json");
                JsonNode schema = fmt.path("schema");
                if (schema.isObject()) {
                    gen.set("responseJsonSchema", schema.deepCopy());
                }
            }
        }
        return gen;
    }

    static int geminiGeneration(String model) {
        if (StringUtils.isBlank(model)) return 0;
        String lower = model.trim().toLowerCase(Locale.ROOT);
        int slash = lower.lastIndexOf('/');
        String leaf = slash >= 0 ? lower.substring(slash + 1) : lower;
        if (!Strings.CS.startsWith(leaf, "gemini-")) return 0;
        int pos = "gemini-".length();
        int end = pos;
        while (end < leaf.length() && Character.isDigit(leaf.charAt(end))) {
            end++;
        }
        if (end == pos) return 0;
        try {
            return Integer.parseInt(leaf.substring(pos, end));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static boolean isGeminiGenerationThreeOrNewer(String model) {
        return geminiGeneration(model) >= 3;
    }

    private static String toGeminiThinkingLevel(String effort, String model) {
        String norm = ReasoningNormalizer.normalizeEffort(effort);
        int gen = geminiGeneration(model);
        return switch (norm) {
            case "low" -> "low";
            case "medium" -> (gen >= 4 || Strings.CI.contains(model, "flash")) ? "medium" : "high";
            default -> "high";
        };
    }

    static String toGeminiFuncName(String name) {
        if (StringUtils.isEmpty(name)) return "tool";
        char first = name.charAt(0);
        if ((first >= '0' && first <= '9') || first == '-' || first == '.' || first == ':') {
            return NAME_LEAD + name;
        }
        return name;
    }

    static String fromGeminiFuncName(String name) {
        if (StringUtils.isEmpty(name)) return "tool";
        String current = name;
        for (String sep : new String[] {":", "."}) {
            String prefix = GEMINI_NAMESPACE + sep;
            if (Strings.CS.startsWith(current, prefix) && current.length() > prefix.length()) {
                current = current.substring(prefix.length());
                break;
            }
        }
        if (Strings.CS.startsWith(current, NAME_LEAD)) {
            current = current.substring(NAME_LEAD.length());
        }
        return current;
    }

    static ApiMessage parseUnaryResponse(String responseBody, String fallbackModel, Set<String> offeredTools)
            throws Exception {
        JsonNode root = JsonUtils.getMapper().readTree(responseBody);
        if (root.has("response") && root.get("response").isObject()) {
            root = root.get("response");
        }
        String responseId = root.path("responseId").asText("gem_" + UUID.randomUUID());
        String model = root.path("modelVersion").asText(fallbackModel);
        Usage usage = parseGeminiUsage(root.get("usageMetadata"));

        List<ContentBlock> blocks = new ArrayList<>();
        String stopReason = "end_turn";
        boolean sawToolCall = false;

        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode cand = candidates.get(0);
            JsonNode parts = cand.path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    if (appendCandidatePart(blocks, part)) {
                        sawToolCall = true;
                    }
                }
            }
            String finishReason = cand.path("finishReason").asText("");
            if (!sawToolCall && (Strings.CS.equals("MALFORMED_FUNCTION_CALL", finishReason)
                    || Strings.CS.equals("UNEXPECTED_TOOL_CALL", finishReason))) {
                String finishMsg = cand.path("finishMessage").asText("");
                int marker = finishMsg.indexOf("call:default_api:");
                if (marker >= 0) {
                    TextCallRescue.CallRead rescued =
                        TextCallRescue.parseGeminiTextCall(finishMsg.substring(marker), offeredTools);
                    if (rescued.good()) {
                        blocks.add(new ToolUseBlock(TextCallRescue.nextCallId(),
                            rescued.call().name(), rescued.call().args()));
                        sawToolCall = true;
                    }
                }
            }
            stopReason = mapFinishReason(finishReason, sawToolCall);
        }

        if (!sawToolCall) {
            TextCallRescue.RescueOutcome rescued = TextCallRescue.rescueBlocks(blocks, offeredTools);
            if (rescued.rescuedTools()) {
                blocks = rescued.blocks();
                stopReason = "tool_use";
            }
        }

        return new ApiMessage(
            responseId, "message", "assistant", List.copyOf(blocks), model, stopReason, null, usage);
    }

    static boolean appendCandidatePart(List<ContentBlock> blocks, JsonNode part) {
        if (part == null || !part.isObject()) return false;
        JsonNode fc = firstObject(part, "functionCall", "function_call");
        String sig = firstText(part, "thoughtSignature", "thought_signature");
        if (fc != null) {
            String rawId = fc.path("id").asText("");
            if (StringUtils.isEmpty(rawId)) {
                rawId = "call_" + UUID.randomUUID().toString().substring(0, 8);
            }
            String signedId = GeminiThoughtSignature.signToolId(rawId, sig);
            String name = fromGeminiFuncName(fc.path("name").asText(""));
            JsonNode args = fc.get("args");
            if (args == null || args.isNull() || !args.isObject()) {
                args = JsonUtils.getMapper().createObjectNode();
            }
            blocks.add(new ToolUseBlock(signedId, name, args.deepCopy()));
            return true;
        }

        if (part.path("thought").asBoolean(false)) {
            String text = part.path("text").asText("");
            if (StringUtils.isNotEmpty(text) || StringUtils.isNotEmpty(sig)) {
                blocks.add(new ThinkingBlock(text, StringUtils.isEmpty(sig) ? null : sig));
            }
            return false;
        }

        JsonNode inline = firstObject(part, "inlineData", "inline_data");
        if (inline != null && StringUtils.isNotEmpty(inline.path("data").asText(""))) {
            ObjectNode src = JsonUtils.getMapper().createObjectNode();
            src.put("type", "base64");
            src.put("media_type", firstText(inline, "mimeType", "mime_type", "image/png"));
            src.put("data", inline.path("data").asText(""));
            blocks.add(new ImageBlock(src));
            return false;
        }

        String ranCode = formatExecutableCodePart(part);
        if (StringUtils.isNotEmpty(ranCode)) {
            blocks.add(new TextBlock(ranCode));
            return false;
        }

        String text = part.path("text").asText("");
        if (StringUtils.isNotEmpty(text)) {
            blocks.add(new TextBlock(text));
        }
        if (StringUtils.isNotEmpty(sig)) {
            blocks.add(new ThinkingBlock("", sig));
        }
        return false;
    }

    static String formatExecutableCodePart(JsonNode part) {
        JsonNode exec = firstObject(part, "executableCode", "executable_code");
        if (exec != null) {
            String lang = exec.path("language").asText("").toLowerCase(Locale.ROOT);
            String code = exec.path("code").asText("").stripTrailing();
            return "\n```" + lang + "\n" + code + "\n```\n";
        }
        JsonNode res = firstObject(part, "codeExecutionResult", "code_execution_result");
        if (res != null) {
            StringBuilder sb = new StringBuilder();
            String outcome = res.path("outcome").asText("");
            if (StringUtils.isNotEmpty(outcome) && !Strings.CS.equals("OUTCOME_OK", outcome)) {
                sb.append('\n').append(outcome).append(':');
            }
            String out = res.path("output").asText("").stripTrailing();
            if (StringUtils.isNotEmpty(out)) {
                sb.append("\n```\n").append(out).append("\n```\n");
            }
            return sb.toString();
        }
        return "";
    }

    static Usage parseGeminiUsage(JsonNode meta) {
        if (meta == null || meta.isNull()) return Usage.EMPTY;
        long prompt = firstLong(meta, "promptTokenCount", "prompt_token_count");
        long cached = firstLong(meta, "cachedContentTokenCount", "cached_content_token_count");
        long candidates = firstLong(meta, "candidatesTokenCount", "candidates_token_count");
        long thoughts = firstLong(meta, "thoughtsTokenCount", "thoughts_token_count");
        long total = firstLong(meta, "totalTokenCount", "total_token_count");
        long input = Math.max(0L, prompt - cached);
        long output = candidates + thoughts;
        return new Usage(input, output, 0L, cached, total > 0 ? total : null);
    }

    static String mapFinishReason(String finishReason, boolean sawToolCall) {
        if (sawToolCall) return "tool_use";
        if (StringUtils.isEmpty(finishReason)) return "end_turn";
        return switch (finishReason.toUpperCase(Locale.ROOT)) {
            case "MAX_TOKENS" -> "max_tokens";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "refusal";
            default -> "end_turn";
        };
    }

    private static JsonNode firstObject(JsonNode parent, String camel, String snake) {
        if (parent.has(camel) && parent.get(camel).isObject()) return parent.get(camel);
        if (parent.has(snake) && parent.get(snake).isObject()) return parent.get(snake);
        return null;
    }

    private static String firstText(JsonNode parent, String camel, String snake) {
        return firstText(parent, camel, snake, "");
    }

    private static String firstText(JsonNode parent, String camel, String snake, String fallback) {
        if (parent.hasNonNull(camel) && parent.get(camel).isTextual()) return parent.get(camel).asText();
        if (parent.hasNonNull(snake) && parent.get(snake).isTextual()) return parent.get(snake).asText();
        return fallback;
    }

    private static long firstLong(JsonNode parent, String camel, String snake) {
        if (parent.hasNonNull(camel)) return parent.get(camel).asLong(0);
        if (parent.hasNonNull(snake)) return parent.get(snake).asLong(0);
        return 0L;
    }

    private static ApiException apiError(int status, String body) {
        String promptTooLong = PromptTooLongException.extractFromResponseBody(body);
        if (promptTooLong != null) {
            return new PromptTooLongException(promptTooLong, status, null, null);
        }
        String message = body;
        try {
            JsonNode root = JsonUtils.getMapper().readTree(body);
            if (root.path("error").has("message")) {
                message = root.path("error").path("message").asText();
            }
        } catch (Exception _) { }
        return new ApiException("Gemini API error: " + status + " - " + message, status);
    }

    private static String stripInternalBoundary(String value) {
        if (value == null) return null;
        return value
            .replace("\n\n" + SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n\n", "\n\n")
            .replace(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, "");
    }

    private static final class GeminiStreamTranslator implements EventSourceStreamBridge.EventTranslator {
        private final String fallbackModel;
        private final Set<String> offeredTools;
        private final TextCallRescue.StreamBuffer textRescue;
        private boolean started;
        private boolean finished;
        private boolean sawToolCall;
        private Integer thinkingBlockIndex;
        private Integer textBlockIndex;
        private int nextBlockIndex;
        private String finalStopReason;
        private Usage latestUsage = Usage.EMPTY;

        GeminiStreamTranslator(String fallbackModel, Set<String> offeredTools) {
            this.fallbackModel = fallbackModel;
            this.offeredTools = offeredTools;
            this.textRescue = new TextCallRescue.StreamBuffer(offeredTools);
        }

        @Override
        public void translate(String type, String data, Consumer<StreamEvent> sink) {
            if (StringUtils.isBlank(data) || Strings.CS.equals("[DONE]", data.trim())) {
                finish(sink);
                return;
            }
            JsonNode chunk;
            try {
                chunk = JsonUtils.getMapper().readTree(data);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Gemini SSE event", e);
            }
            if (chunk.has("error") && chunk.get("error").isObject()) {
                JsonNode err = chunk.get("error");
                throw new ApiException(
                    err.path("message").asText("Gemini stream error"),
                    err.path("code").asInt(502),
                    err.path("status").asText(null));
            }
            if (chunk.has("response") && chunk.get("response").isObject()) {
                chunk = chunk.get("response");
            }

            if (chunk.has("usageMetadata")) {
                latestUsage = parseGeminiUsage(chunk.get("usageMetadata"));
            }
            ensureStarted(chunk, sink);

            JsonNode candidates = chunk.get("candidates");
            if (candidates == null || !candidates.isArray()) return;
            for (JsonNode cand : candidates) {
                JsonNode parts = cand.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        processPart(part, chunk, sink);
                    }
                }
                String finishReason = cand.path("finishReason").asText("");
                if (StringUtils.isNotEmpty(finishReason)) {
                    if (!sawToolCall && (Strings.CS.equals("MALFORMED_FUNCTION_CALL", finishReason)
                            || Strings.CS.equals("UNEXPECTED_TOOL_CALL", finishReason))) {
                        String finishMsg = cand.path("finishMessage").asText("");
                        int marker = finishMsg.indexOf("call:default_api:");
                        if (marker >= 0) {
                            TextCallRescue.CallRead rescued =
                                TextCallRescue.parseGeminiTextCall(finishMsg.substring(marker), offeredTools);
                            if (rescued.good()) {
                                emitToolCall(TextCallRescue.nextCallId(),
                                    rescued.call().name(), rescued.call().args().toString(), chunk, sink);
                            }
                        }
                    }
                    finalStopReason = mapFinishReason(finishReason, sawToolCall);
                }
            }
        }

        private void processPart(JsonNode part, JsonNode rawChunk, Consumer<StreamEvent> sink) {
            JsonNode fc = firstObject(part, "functionCall", "function_call");
            String sig = firstText(part, "thoughtSignature", "thought_signature");
            if (fc != null) {
                String rawId = fc.path("id").asText("");
                if (StringUtils.isEmpty(rawId)) {
                    rawId = "call_" + UUID.randomUUID().toString().substring(0, 8);
                }
                String signedId = GeminiThoughtSignature.signToolId(rawId, sig);
                String name = fromGeminiFuncName(fc.path("name").asText(""));
                JsonNode args = fc.get("args");
                String argsJson = (args == null || args.isNull()) ? "{}" : args.toString();
                emitToolCall(signedId, name, argsJson, rawChunk, sink);
                return;
            }

            if (part.path("thought").asBoolean(false)) {
                String text = part.path("text").asText("");
                if (StringUtils.isEmpty(text) && StringUtils.isEmpty(sig)) {
                    return;
                }
                closeText(rawChunk, sink);
                if (thinkingBlockIndex == null) {
                    thinkingBlockIndex = nextBlockIndex++;
                    sink.accept(new StreamEvent.ContentBlockStart(
                        thinkingBlockIndex, new ThinkingBlock("", null), rawChunk));
                }
                if (StringUtils.isNotEmpty(text)) {
                    sink.accept(new StreamEvent.ContentBlockDelta(
                        thinkingBlockIndex, new Delta.ThinkingDelta(text), rawChunk));
                }
                if (StringUtils.isNotEmpty(sig)) {
                    sink.accept(new StreamEvent.ContentBlockDelta(
                        thinkingBlockIndex, new Delta.SignatureDelta(sig), rawChunk));
                }
                return;
            }

            String ranCode = formatExecutableCodePart(part);
            String text = StringUtils.isNotEmpty(ranCode) ? ranCode : part.path("text").asText("");
            if (StringUtils.isNotEmpty(text)) {
                closeThinking(rawChunk, sink);
                String immediate = textRescue.onTextDelta(text);
                emitTextDelta(immediate, rawChunk, sink);
            }
            if (StringUtils.isNotEmpty(sig)) {
                closeText(rawChunk, sink);
                if (thinkingBlockIndex == null) {
                    thinkingBlockIndex = nextBlockIndex++;
                    sink.accept(new StreamEvent.ContentBlockStart(
                        thinkingBlockIndex, new ThinkingBlock("", null), rawChunk));
                }
                sink.accept(new StreamEvent.ContentBlockDelta(
                    thinkingBlockIndex, new Delta.SignatureDelta(sig), rawChunk));
            }
        }

        private void emitTextDelta(String text, JsonNode rawChunk, Consumer<StreamEvent> sink) {
            if (StringUtils.isEmpty(text)) return;
            if (textBlockIndex == null) {
                textBlockIndex = nextBlockIndex++;
                sink.accept(new StreamEvent.ContentBlockStart(textBlockIndex, new TextBlock(""), rawChunk));
            }
            sink.accept(new StreamEvent.ContentBlockDelta(
                textBlockIndex, new Delta.TextDelta(text), rawChunk));
        }

        private void emitToolCall(
                String id, String name, String argsJson, JsonNode rawChunk, Consumer<StreamEvent> sink) {
            sawToolCall = true;
            textRescue.markNativeToolCall();
            closeThinking(rawChunk, sink);
            closeText(rawChunk, sink);
            int idx = nextBlockIndex++;
            sink.accept(new StreamEvent.ContentBlockStart(
                idx, new ToolUseBlock(id, name, JsonUtils.getMapper().createObjectNode()), rawChunk));
            sink.accept(new StreamEvent.ContentBlockDelta(idx, new Delta.InputJsonDelta(argsJson), rawChunk));
            sink.accept(new StreamEvent.ContentBlockStop(idx, rawChunk));
        }

        private void ensureStarted(JsonNode chunk, Consumer<StreamEvent> sink) {
            if (started) return;
            started = true;
            String id = chunk != null ? chunk.path("responseId").asText("gem_" + UUID.randomUUID())
                : "gem_" + UUID.randomUUID();
            String model = chunk != null ? chunk.path("modelVersion").asText(fallbackModel) : fallbackModel;
            Usage initial = new Usage(latestUsage.inputTokens(), 0L,
                latestUsage.cacheCreationInputTokens(), latestUsage.cacheReadInputTokens());
            sink.accept(new StreamEvent.MessageStart(new ApiMessage(
                id, "message", "assistant", List.of(), model, null, null, initial), chunk));
        }

        private void closeThinking(JsonNode raw, Consumer<StreamEvent> sink) {
            if (thinkingBlockIndex == null) return;
            sink.accept(new StreamEvent.ContentBlockStop(thinkingBlockIndex, raw));
            thinkingBlockIndex = null;
        }

        private void closeText(JsonNode raw, Consumer<StreamEvent> sink) {
            if (textBlockIndex == null) return;
            sink.accept(new StreamEvent.ContentBlockStop(textBlockIndex, raw));
            textBlockIndex = null;
        }

        @Override
        public void onClosed(Consumer<StreamEvent> sink) {
            finish(sink);
        }

        private void finish(Consumer<StreamEvent> sink) {
            if (finished) return;
            finished = true;
            ensureStarted(null, sink);
            closeThinking(null, sink);

            TextCallRescue.StreamFlushResult flushed = textRescue.finish();
            if (StringUtils.isNotEmpty(flushed.trailingText())) {
                emitTextDelta(flushed.trailingText(), null, sink);
            }
            closeText(null, sink);
            if (!flushed.calls().isEmpty()) {
                for (TextCallRescue.WrittenCall wc : flushed.calls()) {
                    emitToolCall(TextCallRescue.nextCallId(), wc.name(), wc.args().toString(), null, sink);
                }
                finalStopReason = "tool_use";
            }

            String stop = sawToolCall ? "tool_use"
                : (finalStopReason != null ? finalStopReason : "end_turn");
            sink.accept(new StreamEvent.MessageDelta(new MessageDeltaData(stop, null), latestUsage));
            sink.accept(new StreamEvent.MessageStop());
        }
    }
}
