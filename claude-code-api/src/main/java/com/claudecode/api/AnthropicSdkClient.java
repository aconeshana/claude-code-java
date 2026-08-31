package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


import com.claudecode.core.message.ApiErrorFriendlyText;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.http.CancellationRegistrar;
import com.claudecode.http.HttpCalls;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.config.VersionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic API client backed by OkHttp (+ okhttp-sse for streaming).
 * <p>
 * Talks to the Anthropic Messages API and adapts responses to our unified
 * {@link StreamEvent}/{@link ApiMessage} types. Deliberately does not use the
 * official {@code anthropic-java} SDK — this client's primary real-world use
 * is pointing {@code baseUrl} at non-Anthropic (proxy/gateway) endpoints via
 * env vars, where the official SDK's Anthropic-specific request/response
 * modeling would be a poor fit; OkHttp is a generic transport that doesn't
 * assume anything about the endpoint shape.
 *
 * <ul>
 *   <li>request serialization,
 *       {@code addCacheBreakpoints}, beta headers, the {@code ...extraBodyParams}
 *       merge over the finished body (see {@link ExtraBodyParams}), lossless
 *       SSE response mapping used by SDK partial-message output, and propagation
 *       of the response {@code request-id} header into refusal diagnostics.</li>
 *   <li>beta Messages
 *       {@code countTokens} request used by context-usage analysis.</li>
 * </ul>
 */
public class AnthropicSdkClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicSdkClient.class);
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String COUNT_TOKENS_PATH = "/v1/messages/count_tokens?beta=true";
    private static final String TASK_BUDGETS_BETA = "task-budgets-2026-03-13";
    private static final String TOKEN_COUNTING_BETA = "token-counting-2024-11-01";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String authToken;
    private final String model;
    private final String configuredBaseUrl;
    private final Map<String, String> customHeaders;
    private final OkHttpClient streamingHttpClient;
    private final OkHttpClient nonStreamingHttpClient;
    private final ObjectMapper mapper;

    public AnthropicSdkClient(ApiConfig.AnthropicConfig config) {
        this.apiKey = config.apiKey();
        this.authToken = config.authToken();
        this.model = config.model();
        this.configuredBaseUrl = config.baseUrl() == null
            ? null : normalizeBaseUrl(config.baseUrl());
        this.customHeaders = config.headers();
        this.streamingHttpClient = HttpClientFactory.anthropicStreaming();
        this.nonStreamingHttpClient = HttpClientFactory.anthropicNonStreaming();
        this.mapper = JsonUtils.getMapper();
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request,
            Runnable onRequestSubmitted) {
        try {
            String requestBody = serializeWithCacheControl(request, model);
            Request httpRequest = buildRequest(requestBody, request);
            ApiTimeouts.StreamWatchdog streamWatchdog = ApiTimeouts.watchdog();
            httpRequest = ApiStreamDiagnostics.attach(
                httpRequest, request, requestBody,
                StringUtils.isNotBlank(httpRequest.header("Authorization")),
                StringUtils.isNotBlank(httpRequest.header("x-api-key")),
                streamWatchdog);
            EventSourceStreamBridge.EventTranslator translator =
                new EventSourceStreamBridge.EventTranslator() {
                    private String requestId;

                    @Override
                    public void onOpen(Response response,
                                       Consumer<StreamEvent> sink) {
                        requestId = responseRequestId(response);
                    }

                    @Override
                    public void translate(String type, String data,
                                          Consumer<StreamEvent> sink) {
                        StreamEvent event = parseEvent(type, data);
                        if (event instanceof StreamEvent.MessageStart start) {
                            event = new StreamEvent.MessageStart(
                                start.message(), start.rawEvent(), requestId);
                        }
                        if (event != null) sink.accept(event);
                    }
                };
            return EventSourceStreamBridge.connect(
                streamingHttpClient, httpRequest, translator,
                ApiTimeouts.apiTimeout(), streamWatchdog,
                request.cancellationRegistrar(), onRequestSubmitted);
        } catch (IOException e) {
            throw new ApiException("Failed to send request: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        return doCreateMessage(request, 0L);
    }

    /**
     * Non-streaming request with a per-call timeout override.
     */
    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        return doCreateMessage(request, timeoutMillis);
    }

    @Override
    public long countTokens(
            String requestedModel,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools) {
        List<CreateMessageRequest.RequestMessage> effectiveMessages =
            messages == null ? List.of() : messages;
        List<CreateMessageRequest.ToolDefinition> effectiveTools =
            tools == null ? List.of() : tools;

        // when count_tokens receives no conversation messages, including the
        // empty-MCP-tools case used by /context analysis.
        if (effectiveMessages.isEmpty()) {
            effectiveMessages = List.of(
                new CreateMessageRequest.RequestMessage("user", "foo"));
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("model", requestedModel != null ? requestedModel : model);
        root.set("messages", mapper.valueToTree(effectiveMessages));
        root.set("tools", mapper.valueToTree(effectiveTools));
        normalizeToolSchemas(root);
        normalizeServerToolResultBlocks(root);
        LlmWireBodyFinalizer.finalizeForApi(root);

        Request.Builder request = new Request.Builder()
            .url(endpoint(effectiveBaseUrl(), COUNT_TOKENS_PATH))
            .header("Content-Type", "application/json")
            .header("anthropic-version", API_VERSION)
            .header("anthropic-beta", TOKEN_COUNTING_BETA);
        if (StringUtils.isNotBlank(apiKey)) {
            request.header("x-api-key", apiKey);
        }
        if (StringUtils.isNotBlank(authToken)) {
            request.header("Authorization", "Bearer " + authToken);
        }
        customHeaders.forEach(request::header);
        Request httpRequest = request
            .tag(RetryRequestPolicy.class, RetryRequestPolicy.forQuerySource("count_tokens"))
            .post(RequestBody.create(root.toString(), JSON))
            .build();


        // maxRetries=1 instead of the main-loop retry budget. Preserve the
        // shared transport but replace only that interceptor for this call.
        OkHttpClient.Builder countClientBuilder = nonStreamingHttpClient.newBuilder();
        countClientBuilder.interceptors().removeIf(RetryInterceptor.class::isInstance);
        countClientBuilder.addInterceptor(new RetryInterceptor(1));
        try (Response response = HttpCalls.execute(
                countClientBuilder.build(), httpRequest, ApiTimeouts.apiTimeout(),
                CancellationRegistrar.NONE)) {
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                throw apiExceptionFor(response.code(), responseBody, response.headers());
            }
            JsonNode parsed = mapper.readTree(responseBody);
            if (!parsed.path("input_tokens").canConvertToLong()) {
                throw new ApiException("count_tokens response omitted input_tokens", response.code());
            }
            return parsed.path("input_tokens").asLong();
        } catch (IOException e) {
            throw new ApiException("Failed to count tokens: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Token count request interrupted", 0, e);
        }
    }

    @Override
    public long countTokensFallback(
            String requestedModel,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools,
            String sessionId) {
        var effectiveMessages = messages == null || messages.isEmpty()
            ? List.of(new CreateMessageRequest.RequestMessage("user", "count"))
            : messages;
        ApiMessage response = createMessage(CreateMessageRequest.builder()
            .model(requestedModel != null ? requestedModel : model)
            .maxTokens(1)
            .messages(effectiveMessages)
            .tools(tools == null || tools.isEmpty() ? null : tools)
            .metadata(fallbackMetadata(sessionId))
            .promptCachingEnabled(false)
            .stream(false)
            .build());
        Usage usage = response.usage();
        return usage == null ? 0 : usage.inputTokens()
            + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
    }

    private static JsonNode fallbackMetadata(String userId) {
        if (StringUtils.isBlank(userId)) return null;
        return JsonUtils.getMapper().createObjectNode().put("user_id", userId);
    }

    private ApiMessage doCreateMessage(CreateMessageRequest request, long timeoutMillis) {
        // For non-streaming, build a request with stream=false — every other
        // field is carried over from the caller's request as-is. This used
        // to hand-copy a fixed field list that predated thinking/effort/
        // outputConfig/contextManagement, silently dropping all four for
        // every non-streaming call (side queries, hook evaluators) even when
        // the caller explicitly set them.
        CreateMessageRequest nonStreamRequest = CreateMessageRequest.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .systemPrompt(request.systemPrompt())
                .messages(request.messages())
                .tools(request.tools())
                .metadata(request.metadata())
                .stopSequences(request.stopSequences())
                .stream(false)
                .temperature(request.temperature())
                .topP(request.topP())
                .topK(request.topK())
                .toolChoice(request.toolChoice())
                .thinking(request.thinking())
                .effort(request.effort())
                .outputConfig(request.outputConfig())
                .speed(request.speed())
                .contextManagement(request.contextManagement())
                .skipCacheWrite(request.skipCacheWrite())
                .promptCachingEnabled(request.promptCachingEnabled())
                .promptCacheTtl(request.promptCacheTtl())
                .querySource(request.querySource())
                .cancellationRegistrar(request.cancellationRegistrar())
                // serializeWithCacheControl derives the attribution header from
                // this flag; dropping it made a sub-agent's non-streaming call
                // claim a different identity than its own streaming request.
                .subagent(request.subagent())
                .build();

        try {
            String requestBody = serializeWithCacheControl(nonStreamRequest, model);
            Request httpRequest = buildRequest(requestBody, nonStreamRequest);
            debugLogRequest(httpRequest, requestBody);

            Duration timeout = timeoutMillis > 0
                ? Duration.ofMillis(timeoutMillis) : ApiTimeouts.apiTimeout();
            try (Response response = HttpCalls.execute(
                    nonStreamingHttpClient, httpRequest, timeout,
                    nonStreamRequest.cancellationRegistrar())) {
                debugLogResponse(response);
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw apiExceptionFor(response.code(), responseBody, response.headers());
                }
                ApiMessage message = mapper.readValue(responseBody, ApiMessage.class);
                ApiRequestTiming timing = response.request().tag(ApiRequestTiming.class);
                return ApiMessageTiming.attach(message,
                    timing != null ? timing.lastAttemptStartMs() : 0L,
                    responseRequestId(response));
            }
        } catch (IOException e) {
            throw new ApiException("Failed to send request: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Request interrupted", 0, e);
        }
    }

    /**
     * Builds an {@link ApiException} for a non-successful response, threading through the {@code
     * Retry-After} header (if present and parseable as integer seconds) so {@link
     * RetryInterceptor#calculateDelay} can honor the server-specified wait instead of the exponential
     * formula.
     */
    static ApiException apiExceptionFor(int statusCode, String body, Headers headers) {
        Long retryAfterSeconds = parseRetryAfterSeconds(headers.get("Retry-After"));
        String promptTooLongMessage = PromptTooLongException.extractFromResponseBody(body);
        if (promptTooLongMessage != null) {
            return new PromptTooLongException(
                promptTooLongMessage, statusCode, null, retryAfterSeconds);
        }
        String friendlyMessage = ApiErrorFriendlyText.classify(statusCode, body);
        return new ApiException(
            "API request failed: " + body, statusCode, null, retryAfterSeconds, friendlyMessage);
    }

    private static Long parseRetryAfterSeconds(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return null;
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    @Override
    public String getModel() {
        return model;
    }


    static final String EFFORT_BETA = "effort-2025-11-24";

    /**
     * Interleaved-thinking beta header.
     */
    static final String INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14";

/**
     * Context-management beta header.
     */
    static final String CONTEXT_MANAGEMENT_BETA = "context-management-2025-06-27";


    static final String TOOL_SEARCH_BETA = "advanced-tool-use-2025-11-20";

    /** Structured-output beta required whenever {@code output_config.format} is present. */
    static final String STRUCTURED_OUTPUTS_BETA = "structured-outputs-2025-12-15";


    static final String EXTENDED_CACHE_TTL_BETA = "extended-cache-ttl-2025-04-11";


    static final String FAST_MODE_BETA = "fast-mode-2026-02-01";

    /**
     * HTTP request/response debug toggle. Off by default. Debug output is kept
     * PII-free: request bodies, credential values, and custom header values are
     * never logged. Enable by setting either:
     * <ul>
     *   <li>env {@code ANTHROPIC_DEBUG=true} (or {@code 1})</li>
     *   <li>JVM flag {@code -Danthropic.debug=true}</li>
     * </ul>
     * Output goes through SLF4J → logback → {@code /tmp/claude-code-java.log}
     * (configured in {@code claude-code-app/src/main/resources/logback.xml}).
     * Streaming requests use {@link ApiStreamDiagnostics}; this legacy helper
     * covers non-streaming and token-counting calls.
     */
    private void debugLogRequest(Request req, String body) {
        if (!ApiStreamDiagnostics.debugEnabled()) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n──── Anthropic request ────\n");
        sb.append("POST ").append(ApiStreamDiagnostics.safeEndpoint(req)).append('\n');
        sb.append("body_bytes=")
            .append(body.getBytes(StandardCharsets.UTF_8).length).append('\n');
        boolean bearer = StringUtils.isNotBlank(req.header("Authorization"));
        boolean apiKeyHeader = StringUtils.isNotBlank(req.header("x-api-key"));
        String authMode = bearer && apiKeyHeader ? "bearer+api-key"
            : bearer ? "bearer" : apiKeyHeader ? "api-key" : "none";
        sb.append("auth=").append(authMode).append('\n');
        sb.append("anthropic-beta=")
            .append(String.join(",", req.headers("anthropic-beta"))).append('\n');
        sb.append("header_names=").append(String.join(",", req.headers().names())).append('\n');
        sb.append("body=omitted\n");
        sb.append("───────────────────────────");
        log.info(sb.toString());
    }

    private static String responseRequestId(Response response) {
        String requestId = response.header("request-id");
        return StringUtils.isNotBlank(requestId)
            ? requestId : response.header("x-request-id");
    }

    private void debugLogResponse(Response resp) {
        if (!ApiStreamDiagnostics.debugEnabled()) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n──── Anthropic response ────\n");
        sb.append("HTTP ").append(resp.code()).append('\n');
        // Print interesting headers only — full headers would dwarf the body.
        for (String k : resp.headers().names()) {
            String lk = k.toLowerCase(Locale.ROOT);
            if (Strings.CS.startsWith(lk, "anthropic-")
                    || Strings.CS.equals(lk, "request-id")
                    || Strings.CS.equals(lk, "x-request-id")) {
                sb.append(k).append(": ").append(resp.header(k)).append('\n');
            }
        }
        sb.append("────────────────────────────");
        log.info(sb.toString());
    }

    private Request buildRequest(String body, CreateMessageRequest request) {
        Request.Builder b = new Request.Builder()
                .url(endpoint(effectiveBaseUrl(), MESSAGES_PATH))
                .header("Content-Type", "application/json")
                .header("anthropic-version", API_VERSION);

        // configureApiKeyHeaders). A gateway/proxy deployment may only set one.
        if (StringUtils.isNotBlank(apiKey)) {
            b.header("x-api-key", apiKey);
        }
        if (StringUtils.isNotBlank(authToken)) {
            b.header("Authorization", "Bearer " + authToken);
        }

        boolean hasEffort = request != null
            && ((StringUtils.isNotBlank(request.effort()))
                || (request.outputConfig() != null && request.outputConfig().effort() != null
                    && !StringUtils.isBlank(request.outputConfig().effort())));
        // NOTE: OkHttp's Request.Builder#header(name, value) REPLACES any
        // existing header with that name — unlike java.net.http's
        // HttpRequest.Builder#header (additive). anthropic-beta can carry
        // several independent values in the same request (effort +
        // interleaved-thinking + context-management all at once), so every
        // branch below must use addHeader, or later branches silently wipe
        // out earlier ones (regression caught by a live capture where only
        // the last-set beta header actually made it onto the wire).
        if (hasEffort) {
            b.addHeader("anthropic-beta", EFFORT_BETA);
        }
        if (request != null && request.outputConfig() != null
                && request.outputConfig().format() != null) {
            b.addHeader("anthropic-beta", STRUCTURED_OUTPUTS_BETA);
        }

        // `strict: true` marker, even without output_config.format.
        if (request != null && request.outputConfig() == null
                && request.tools() != null
                && request.tools().stream().anyMatch(t -> Boolean.TRUE.equals(t.strict()))) {
            b.addHeader("anthropic-beta", STRUCTURED_OUTPUTS_BETA);
        }

        if (request != null && request.thinking() != null
                && (Strings.CS.equals("enabled", request.thinking().type())
                    || Strings.CS.equals("adaptive", request.thinking().type()))) {
            b.addHeader("anthropic-beta", INTERLEAVED_THINKING_BETA);
        }

        if (request != null && request.contextManagement() != null) {
            b.addHeader("anthropic-beta", CONTEXT_MANAGEMENT_BETA);
        }

        if (request != null && request.tools() != null
                && request.tools().stream().anyMatch(t -> Boolean.TRUE.equals(t.deferLoading()))) {
            b.addHeader("anthropic-beta", TOOL_SEARCH_BETA);
        }
        if (request != null && request.promptCachingEnabled()
                && request.promptCacheTtl() == CreateMessageRequest.PromptCacheTtl.ONE_HOUR) {
            b.addHeader("anthropic-beta", EXTENDED_CACHE_TTL_BETA);
        }
        if (request != null && request.outputConfig() != null
                && request.outputConfig().taskBudget() != null) {
            b.addHeader("anthropic-beta", TASK_BUDGETS_BETA);
        }
        if (request != null && Strings.CS.equals("fast", request.speed())) {
            b.addHeader("anthropic-beta", FAST_MODE_BETA);
        }
        customHeaders.forEach((name, value) -> {
            if (Strings.CI.equals("anthropic-beta", name)) {
                for (String beta : value.split(",")) {
                    if (StringUtils.isNotBlank(beta)) b.addHeader(name, beta.trim());
                }
            } else {
                b.header(name, value);
            }
        });
        // Per-request timeouts aren't a Request concept in OkHttp (timeouts
        // are client-level) — the bounded-wall-clock side-query case is
        // handled by doCreateMessage deriving a client via
        // httpClient.newBuilder().callTimeout(...) instead.
        return b.tag(RetryRequestPolicy.class,
                RetryRequestPolicy.forQuerySource(request != null ? request.querySource() : null))
            .post(RequestBody.create(body, JSON)).build();
    }

    private String effectiveBaseUrl() {
        if (configuredBaseUrl != null) return configuredBaseUrl;
        String fromEnvironment = SubprocessEnvironment.get("ANTHROPIC_BASE_URL");
        return StringUtils.isBlank(fromEnvironment)
            ? DEFAULT_BASE_URL : normalizeBaseUrl(fromEnvironment);
    }

    private static String normalizeBaseUrl(String raw) {
        return Strings.CS.endsWith(raw, "/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static String endpoint(String baseUrl, String path) {
        String base = normalizeBaseUrl(baseUrl);
        if (Strings.CS.endsWith(base, "/v1") && Strings.CS.startsWith(path, "/v1/")) {
            return base + path.substring(3);
        }
        return base + path;
    }

    /**
     * Serializes a {@link CreateMessageRequest} into the Anthropic wire body with prompt-cache markers
     * applied — the single source of truth for what goes on the wire ({@code DUMP_PROMPTS} dumps call
     * this same method).
     */
    public static String serializeWithCacheControl(CreateMessageRequest request) throws IOException {
        return serializeWithCacheControl(request, null);
    }

    private static String serializeWithCacheControl(
            CreateMessageRequest request,
            String defaultModel) throws IOException {
        ObjectMapper mapper = JsonUtils.getMapper();
        ObjectNode root = mapper.valueToTree(request);
        String effectiveModel = request.model() != null ? request.model() : defaultModel;
        if (effectiveModel != null) {
            root.put("model", effectiveModel);
        }


        // normal (including streaming-recovery) request instead of emitting
        // an explicit false. Keep the internal boolean for transport routing,
        // but do not leak the false-valued control member onto the wire.
        if (!request.stream()) {
            root.remove("stream");
        }

        // 1) Rewrite system string → cacheable block array (boundary stripped,
        //    identity prefix split out — see javadoc).
        String systemPrompt = request.systemPrompt();
        if (StringUtils.isNotEmpty(systemPrompt)) {
            String cleaned = systemPrompt
                .replace("\n\n" + SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY + "\n\n", "\n\n")
                .replace(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, "");
            ArrayNode arr = mapper.createArrayNode();
            // Longest match first: CLI_SYSPROMPT_PREFIX is a leading substring of
            // AGENT_SDK_CLI_PRESET_SYSPROMPT_PREFIX, so a preset prompt must not be
            // misattributed (and mis-split) as the plain CLI identity.
            String prefix = SystemPromptConstants.cliSyspromptPrefixes().stream()
                .filter(cleaned::startsWith)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
            if (attributionHeaderEnabled()) {
                ObjectNode attribution = mapper.createObjectNode();
                attribution.put("type", "text");
                attribution.put("text", attributionHeader(root, prefix, request.subagent()));
                arr.add(attribution);
            }
            String rest = cleaned;
            String uncachedSuffix = null;
            int uncachedBoundary = rest.indexOf(
                CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY);
            if (uncachedBoundary >= 0) {
                uncachedSuffix = rest.substring(
                    uncachedBoundary + CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY.length());
                rest = rest.substring(0, uncachedBoundary);
            }
            if (prefix != null) {
                arr.add(request.promptCachingEnabled()
                    ? cachedTextBlock(mapper, prefix, request.promptCacheTtl())
                    : textBlock(mapper, prefix));
                rest = rest.substring(prefix.length());
                if (Strings.CS.startsWith(rest, "\n\n")) rest = rest.substring(2);
            }
            if (!rest.isEmpty()) {
                arr.add(request.promptCachingEnabled()
                    ? cachedTextBlock(mapper, rest, request.promptCacheTtl())
                    : textBlock(mapper, rest));
            }
            if (StringUtils.isNotEmpty(uncachedSuffix)) {
                arr.add(textBlock(mapper, uncachedSuffix));
            }
            root.set("system", arr);
        }











        //    still walked for nested objects.
        normalizeToolSchemas(root);
        normalizeServerToolResultBlocks(root);


// assistantMessageToMessageParam's exclusion).
        JsonNode messagesNode = root.get("messages");
        if (request.promptCachingEnabled()
                && messagesNode != null && messagesNode.isArray() && !messagesNode.isEmpty()) {
            ObjectNode last = null;
            int nonSystemMessagesToSkip = request.skipCacheWrite() ? 1 : 0;
            for (int i = messagesNode.size() - 1; i >= 0; i--) {
                ObjectNode candidate = (ObjectNode) messagesNode.get(i);
                if (!Strings.CS.equals("system", candidate.path("role").asText())) {
                    if (nonSystemMessagesToSkip > 0) {
                        nonSystemMessagesToSkip--;
                        continue;
                    }
                    last = candidate;
                    break;
                }
            }
            JsonNode content = last != null ? last.get("content") : null;
            if (content != null && content.isTextual()) {
                ArrayNode blocks = mapper.createArrayNode();
                ObjectNode block = mapper.createObjectNode();
                block.put("type", "text");
                block.put("text", content.asText());
                block.set("cache_control", ephemeral(mapper, request.promptCacheTtl()));
                blocks.add(block);
                last.set("content", blocks);
            } else if (content != null && content.isArray() && !content.isEmpty()
                    && !hasExplicitCacheControl(content)
                    && content.get(content.size() - 1).isObject()) {
                ObjectNode lastBlock = (ObjectNode) content.get(content.size() - 1);
                String type = lastBlock.path("type").asText("");
                if (!Strings.CS.equals("thinking", type) && !Strings.CS.equals("redacted_thinking", type)
                        && (lastBlock.get("cache_control") == null
                            || lastBlock.get("cache_control").isNull())) {
                    lastBlock.set("cache_control", ephemeral(mapper, request.promptCacheTtl()));
                }
            }
        }


        //    ...extraBodyParams into the request literal: after every computed
        //    field, so the escape hatch wins over anything we built above.
        applyExtraBodyParams(root);
        LlmWireBodyFinalizer.finalizeForApi(root);

        return mapper.writeValueAsString(root);
    }

    /**
     * Merges the {@code CLAUDE_CODE_EXTRA_BODY} escape hatch over the computed body.
     */
    private static void applyExtraBodyParams(ObjectNode root) {
        applyExtraBodyParams(root, ExtraBodyParams.resolve());
    }

    /** Pure merge half, split out so tests need not mutate the process environment. */
    static void applyExtraBodyParams(ObjectNode root, ObjectNode extra) {
        if (extra == null || extra.isEmpty()) return;


        // SDK appends it afterwards based on the call style.
        extra.remove("stream");

        if (extra.get("output_config") instanceof ObjectNode userOutputConfig
                && root.get("output_config") instanceof ObjectNode computedOutputConfig) {
            ObjectNode merged = computedOutputConfig.deepCopy();
            merged.setAll(userOutputConfig);
            extra.set("output_config", merged);
        }
        root.setAll(extra);
    }

    private static boolean hasExplicitCacheControl(JsonNode content) {
        if (content == null || !content.isArray()) return false;
        for (JsonNode block : content) {
            if (block != null && block.isObject()
                    && block.hasNonNull("cache_control")) return true;
        }
        return false;
    }

    private static void normalizeToolSchemas(ObjectNode root) {
        ObjectMapper mapper = JsonUtils.getMapper();
        JsonNode toolsForSchema = root.get("tools");
        if (toolsForSchema != null && toolsForSchema.isArray()) {
            for (JsonNode toolNode : toolsForSchema) {
                if (!(toolNode instanceof ObjectNode toolObj)) continue;
                String toolName = toolObj.path("name").asText("");
                if (Strings.CS.startsWith(toolName, "mcp__") || Strings.CS.equals("StructuredOutput", toolName)) continue;
                JsonNode schemaNode = toolObj.get("input_schema");
                if (!(schemaNode instanceof ObjectNode schemaObj)) continue;
                if (!schemaObj.has("$schema")) {
                    ObjectNode reordered = mapper.createObjectNode();
                    reordered.put("$schema", "https://json-schema.org/draft/2020-12/schema");
                    reordered.setAll(schemaObj);
                    toolObj.set("input_schema", reordered);
                    schemaObj = reordered;
                }
                normalizeAdditionalProperties(schemaObj);
            }
        }
    }

    private static void normalizeServerToolResultBlocks(ObjectNode root) {
        JsonNode messages = root.get("messages");
        if (messages == null || !messages.isArray()) return;
        for (JsonNode message : messages) {
            JsonNode content = message.get("content");
            if (content == null || !content.isArray()) continue;
            Set<String> serverToolIds = new HashSet<>();
            for (JsonNode block : content) {
                if (!block.isObject()) continue;
                if (block.hasNonNull("provider_type") || Strings.CS.equals(
                        "server_tool_result", block.path("type").asText())) {
                    serverToolIds.add(block.path("tool_use_id").asText(""));
                }
            }
            for (JsonNode block : content) {
                if (!(block instanceof ObjectNode object)) continue;
                String type = object.path("type").asText("");
                if (Strings.CS.equals("server_tool_result", type) || object.hasNonNull("provider_type")) {
                    String providerType = object.path("provider_type").asText("");
                    if (StringUtils.isBlank(providerType)) {
                        providerType = switch (object.path("name").asText("")) {
                            case "web_search" -> "web_search_tool_result";
                            case "code_execution" -> "code_execution_tool_result";
                            case "web_fetch" -> "web_fetch_tool_result";
                            default -> throw new IllegalArgumentException(
                                "Unknown Anthropic server tool result: " + object.path("name").asText(""));
                        };
                    }
                    object.put("type", providerType);
                    object.remove(List.of("name", "is_error", "provider_type"));
                } else if (StringUtils.isBlank(type) && object.hasNonNull("id")
                        && serverToolIds.contains(object.path("id").asText())) {
                    object.put("type", "server_tool_use");
                } else if (Strings.CS.equals("web_search_tool_result", type)
                        && object.hasNonNull("error_code")) {
                    ObjectNode error = JsonUtils.getMapper().createObjectNode();
                    error.put("type", "web_search_tool_result_error");
                    error.set("error_code", object.get("error_code"));
                    object.set("content", error);
                    object.remove("error_code");
                }
            }
        }
    }


    private static String attributionHeader(ObjectNode root, String identityPrefix, boolean subagent) {
        String version = SubprocessEnvironment.get("CLAUDE_CODE_COMPAT_VERSION");
        if (StringUtils.isBlank(version)) version = VersionInfo.version();
        String fingerprint = fingerprint(firstUserText(root.path("messages")), version);
        String entrypoint = resolveEntrypoint(
            SubprocessEnvironment.get("CLAUDE_CODE_ENTRYPOINT"),
            identityPrefix, subagent);
        return "x-anthropic-billing-header: cc_version=" + version + "." + fingerprint
            + "; cc_entrypoint=" + entrypoint + ";"
            + (subagent ? " cc_is_subagent=true;" : "");
    }


    static String resolveEntrypoint(String envEntrypoint, String identityPrefix, boolean subagent) {
        if (StringUtils.isNotBlank(envEntrypoint)) return envEntrypoint;
        return SystemPromptConstants.CLI_SYSPROMPT_PREFIX.equals(identityPrefix)
            ? "cli" : "sdk-cli";
    }

    private static boolean attributionHeaderEnabled() {
        String value = SubprocessEnvironment.get("CLAUDE_CODE_ATTRIBUTION_HEADER");
        if (value == null) return true;
        return !switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off" -> true;
            default -> false;
        };
    }

    private static String firstUserText(JsonNode messages) {
        if (messages == null || !messages.isArray()) return "";
        for (JsonNode message : messages) {
            if (!Strings.CS.equals("user", message.path("role").asText())) continue;
            JsonNode content = message.get("content");
            if (content == null) return "";
            if (content.isTextual()) return content.asText();
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if (Strings.CS.equals("text", block.path("type").asText())) {
                        return block.path("text").asText("");
                    }
                }
            }
            return "";
        }
        return "";
    }

    private static String fingerprint(String messageText, String version) {
        int[] indices = {4, 7, 20};
        StringBuilder chars = new StringBuilder(3);
        for (int index : indices) {
            chars.append(index < messageText.length() ? messageText.charAt(index) : '0');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("59cf53e54c78" + chars + version).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }






    private static void normalizeAdditionalProperties(ObjectNode node) {
        boolean looksLikeObjectSchema = node.has("properties")
            || Strings.CS.equals("object", node.path("type").asText(null));
        if (looksLikeObjectSchema && !node.has("additionalProperties")) {
            node.put("additionalProperties", false);
        }
        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            props.forEach(propSchema -> {
                if (propSchema instanceof ObjectNode propObj) normalizeAdditionalProperties(propObj);
            });
        }
        if (node.get("items") instanceof ObjectNode itemsObj) {
            normalizeAdditionalProperties(itemsObj);
        }
        if (node.get("additionalProperties") instanceof ObjectNode apObj) {
            normalizeAdditionalProperties(apObj);
        }
    }

    private static ObjectNode cachedTextBlock(ObjectMapper mapper, String text,
                                              CreateMessageRequest.PromptCacheTtl ttl) {
        ObjectNode block = textBlock(mapper, text);
        block.set("cache_control", ephemeral(mapper, ttl));
        return block;
    }

    private static ObjectNode textBlock(ObjectMapper mapper, String text) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static ObjectNode ephemeral(ObjectMapper mapper,
                                        CreateMessageRequest.PromptCacheTtl ttl) {
        ObjectNode cc = mapper.createObjectNode();
        cc.put("type", "ephemeral");
        if (ttl == CreateMessageRequest.PromptCacheTtl.ONE_HOUR) cc.put("ttl", "1h");
        return cc;
    }

    /**
     * Translates one SSE {@code (type, data)} pair to a {@link StreamEvent} —
     * the {@code translator} function {@link EventSourceStreamBridge} calls
     * per {@code EventSourceListener#onEvent}. Unknown event types return
     * {@code null} (bridge skips them, same as the old
     * {@code default -> null} branch). Package-private (not private) so
     * {@code StreamEventParsingTest} can exercise the JSON→StreamEvent
     * translation directly without round-tripping through a raw SSE-text
     * parser — okhttp-sse's own line parsing is a well-tested third-party
     * concern this class doesn't need to re-verify.
     */
    StreamEvent parseEvent(String type, String data) {
        try {
            return switch (type) {
                case "message_start" -> parseMessageStart(data);
                case "content_block_start" -> parseContentBlockStart(data);
                case "content_block_delta" -> parseContentBlockDelta(data);
                case "content_block_stop" -> parseContentBlockStop(data);
                case "message_delta" -> parseMessageDelta(data);
                case "message_stop" -> new StreamEvent.MessageStop(mapper.readTree(data));
                case "ping" -> new StreamEvent.Ping();
                case "error" -> new StreamEvent.Error(
                    new ApiException(data, 0), mapper.readTree(data));
                default -> null; // Ignore unknown events
            };
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {} - {}", type, e.getMessage());
            return new StreamEvent.Error(new ApiException(
                    "Failed to parse event: " + e.getMessage(), 0, e));
        }
    }

    private StreamEvent.MessageStart parseMessageStart(String data) throws IOException {
        JsonNode root = mapper.readTree(data);
        JsonNode messageNode = root.get("message");
        ApiMessage message = mapper.treeToValue(messageNode, ApiMessage.class);
        return new StreamEvent.MessageStart(message, root);
    }

    private StreamEvent.ContentBlockStart parseContentBlockStart(String data) throws IOException {
        JsonNode root = mapper.readTree(data);
        int index = root.get("index").asInt();
        JsonNode blockNode = root.get("content_block");
        ContentBlock block = parseContentBlock(blockNode);
        return new StreamEvent.ContentBlockStart(index, block, root);
    }

    private StreamEvent.ContentBlockDelta parseContentBlockDelta(String data) throws IOException {
        JsonNode root = mapper.readTree(data);
        int index = root.get("index").asInt();
        JsonNode deltaNode = root.get("delta");
        Delta delta = parseDelta(deltaNode);
        return new StreamEvent.ContentBlockDelta(index, delta, root);
    }

    private StreamEvent.ContentBlockStop parseContentBlockStop(String data) throws IOException {
        JsonNode root = mapper.readTree(data);
        int index = root.get("index").asInt();
        return new StreamEvent.ContentBlockStop(index, root);
    }

    private StreamEvent.MessageDelta parseMessageDelta(String data) throws IOException {
        JsonNode root = mapper.readTree(data);
        JsonNode deltaNode = root.get("delta");
        JsonNode usageNode = root.get("usage");

        MessageDeltaData delta = mapper.treeToValue(deltaNode, MessageDeltaData.class);
        Usage usage = usageNode != null
                ? mapper.treeToValue(usageNode, Usage.class)
                : Usage.EMPTY;

        return new StreamEvent.MessageDelta(delta, usage, root);
    }

    private ContentBlock parseContentBlock(JsonNode node) {
        String type = node.has("type") ? node.get("type").asText() : "text";
        return switch (type) {
            case "text" -> new TextBlock(node.has("text") ? node.get("text").asText() : "");
            case "tool_use" -> new ToolUseBlock(
                    node.get("id").asText(),
                    node.get("name").asText(),
                    node.get("input"));
            case "thinking" -> new ThinkingBlock(
                    node.has("thinking") ? node.get("thinking").asText() : "",
                    node.has("signature") ? node.get("signature").asText(null) : null);
            case "redacted_thinking" -> new RedactedThinkingBlock(
                    node.has("data") ? node.get("data").asText() : "");
            // Server-side tools (Anthropic executes these itself, e.g. web_search) —
            // see ServerToolUseBlock/WebSearchToolResultBlock for wire-shape notes.
            case "server_tool_use" -> new ServerToolUseBlock(
                    node.get("id").asText(),
                    node.get("name").asText(),
                    node.get("input"));
            case "web_search_tool_result" -> WebSearchToolResultBlock.fromJson(
                    node.has("tool_use_id") ? node.get("tool_use_id").asText() : null,
                    node.get("content"));
            case "code_execution_tool_result", "web_fetch_tool_result" ->
                new ServerToolResultBlock(
                    node.path("tool_use_id").asText(null),
                    Strings.CS.equals("code_execution_tool_result", type)
                        ? "code_execution" : "web_fetch",
                    node.get("content"),
                    Strings.CS.endsWith(
                        node.path("content").path("type").asText(""), "_tool_result_error"),
                    type);
            default -> new TextBlock("");
        };
    }

    private Delta parseDelta(JsonNode node) {
        String type = node.has("type") ? node.get("type").asText() : "text_delta";
        return switch (type) {
            case "text_delta" -> new Delta.TextDelta(
                    node.has("text") ? node.get("text").asText() : "");
            case "input_json_delta" -> new Delta.InputJsonDelta(
                    node.has("partial_json") ? node.get("partial_json").asText() : "");
            case "thinking_delta" -> new Delta.ThinkingDelta(
                    node.has("thinking") ? node.get("thinking").asText() : "");
            case "signature_delta" -> new Delta.SignatureDelta(
                    node.has("signature") ? node.get("signature").asText() : "");
            default -> new Delta.TextDelta("");
        };
    }
}
