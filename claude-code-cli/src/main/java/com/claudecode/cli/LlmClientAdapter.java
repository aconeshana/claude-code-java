package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.AnthropicSdkClient;
import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.ApiMessageTiming;
import com.claudecode.api.ApiRetryContext;
import com.claudecode.api.ApiTimeouts;
import com.claudecode.api.ApiStreamException;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.Delta;
import com.claudecode.api.ExtraBodyParams;
import com.claudecode.api.ExtraMetadata;
import com.claudecode.api.LlmClient;
import com.claudecode.api.MessageDeltaData;
import com.claudecode.api.PromptCaching;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.engine.*;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ServerToolUseBlock;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.ServerToolResultBlock;
import com.claudecode.core.message.StopDetails;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.services.cache.PromptCacheBreakDetection;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.http.CancellationRegistrar;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that bridges the api module's {@link LlmClient} to the core module's {@link
 * StreamingClient} interface.
 */
public class LlmClientAdapter implements StreamingClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClientAdapter.class);


    // anything not a subagent land under this tracked prefix).
    private static final String PROMPT_CACHE_SOURCE = "repl_main_thread";

// Wall-clock time (ms) when the most recent assistant turn finished, keyed by cache-detection
// source.
    static final Map<String, Long> lastAssistantTurnMsBySource = new ConcurrentHashMap<>();

    /** Opt-out env var for first-party-only experimental beta headers (context management). */
    private static final String DISABLE_EXPERIMENTAL_BETAS_ENV = "CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS";
    private static final String DISABLE_THINKING_ENV = "CLAUDE_CODE_DISABLE_THINKING";
    private static final String DISABLE_ADAPTIVE_THINKING_ENV = "CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING";

    private final LlmClient llmClient;

    public LlmClientAdapter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    static long countTokens(
            LlmClient client,
            String model,
            List<StreamingClient.StreamRequest.RequestMessage> messages,
            List<StreamingClient.StreamRequest.ToolDef> tools) {
        List<CreateMessageRequest.RequestMessage> apiMessages = messages.stream()
            .map(message -> new CreateMessageRequest.RequestMessage(
                message.role(), message.content()))
            .toList();
        List<CreateMessageRequest.ToolDefinition> apiTools = tools.stream()
            .map(tool -> new CreateMessageRequest.ToolDefinition(
                tool.name(), tool.description(),
                tool.inputSchema() instanceof JsonNode node ? node : null,
                null, tool.type(), tool.maxUses(), tool.allowedDomains(),
                tool.blockedDomains(), null,
                CreateMessageRequest.strictToolEnabled(model, tool.strict()) ? Boolean.TRUE : null,
                tool.eagerInputStreaming() ? Boolean.TRUE : null))
            .toList();
        return client.countTokens(model, apiMessages, apiTools);
    }

    static long countTokensFallback(
            LlmClient client,
            String model,
            List<StreamingClient.StreamRequest.RequestMessage> messages,
            List<StreamingClient.StreamRequest.ToolDef> tools,
            String sessionId) {
        List<CreateMessageRequest.RequestMessage> apiMessages = messages.stream()
            .map(message -> new CreateMessageRequest.RequestMessage(
                message.role(), message.content()))
            .toList();
        List<CreateMessageRequest.ToolDefinition> apiTools = tools.stream()
            .map(tool -> new CreateMessageRequest.ToolDefinition(
                tool.name(), tool.description(),
                tool.inputSchema() instanceof JsonNode node ? node : null,
                null, tool.type(), tool.maxUses(), tool.allowedDomains(),
                tool.blockedDomains(), null,
                CreateMessageRequest.strictToolEnabled(model, tool.strict()) ? Boolean.TRUE : null,
                tool.eagerInputStreaming() ? Boolean.TRUE : null))
            .toList();
        JsonNode metadata = requestMetadata(sessionId);
        String userId = metadata == null ? "" : metadata.path("user_id").asText("");
        return client.countTokensFallback(model, apiMessages, apiTools, userId);
    }


    static boolean firstPartyExperimentalBetasEnabled() {
        return experimentalBetasEnabledFor(
            SubprocessEnvironment.get(DISABLE_EXPERIMENTAL_BETAS_ENV));
    }

    /** Pure gate logic, split out so tests can exercise it without mutating the process env
     *  (Java {@code System.getenv} is read-only). {@code disableEnvValue} is the raw
     *  {@code CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS} value; betas stay enabled unless it's truthy. */
    static boolean experimentalBetasEnabledFor(String disableEnvValue) {
        return !isEnvTruthy(disableEnvValue);
    }


    record ThinkingSelection(CreateMessageRequest.ThinkingConfig config, boolean hasThinking) {}


    static ThinkingSelection selectThinking(StreamingClient.StreamRequest request,
                                             String disableThinkingEnv,
                                             String disableAdaptiveThinkingEnv) {
        int effectiveMaxTokens = request.maxOutputTokensOverride() != null
            ? request.maxOutputTokensOverride() : request.maxTokens();
        return selectThinking(request, disableThinkingEnv, disableAdaptiveThinkingEnv,
            effectiveMaxTokens);
    }

    /** Selects thinking using the request's effective (possibly escalated) max_tokens. */
    private static ThinkingSelection selectThinking(StreamingClient.StreamRequest request,
                                                    String disableThinkingEnv,
                                                    String disableAdaptiveThinkingEnv,
                                                    int effectiveMaxTokens) {
        if (isEnvTruthy(disableThinkingEnv)) {
            return new ThinkingSelection(null, false);
        }
        if (!request.thinkingEnabled()) {
            return new ThinkingSelection(CreateMessageRequest.ThinkingConfig.disabled(), false);
        }
        if (!CreateMessageRequest.supportsThinking(request.model())) {

            // stays omitted even though no thinking body can be attached.
            return new ThinkingSelection(null, true);
        }
        if (!isEnvTruthy(disableAdaptiveThinkingEnv)
                && CreateMessageRequest.supportsAdaptiveThinking(request.model())) {
            return new ThinkingSelection(CreateMessageRequest.ThinkingConfig.adaptive(), true);
        }
        int maxBudget = Math.max(1, effectiveMaxTokens - 1);
        Integer requestedBudget = request.thinkingBudgetTokens();
        int budget = requestedBudget != null
            ? Math.min(maxBudget, requestedBudget)
            : maxBudget;
        return new ThinkingSelection(CreateMessageRequest.ThinkingConfig.enabled(budget), true);
    }

    @Override
    public Iterator<StreamingEvent> createStream(StreamRequest request) {
        // Convert core's StreamRequest to api's CreateMessageRequest
        List<CreateMessageRequest.RequestMessage> apiMessages = new ArrayList<>();
        for (StreamRequest.RequestMessage msg : request.messages()) {
            apiMessages.add(new CreateMessageRequest.RequestMessage(
                msg.role(),
                msg.content()
            ));
        }

        int effectiveMaxTokens = request.maxOutputTokensOverride() != null
            ? request.maxOutputTokensOverride() : request.maxTokens();
        ThinkingSelection thinkingSelection = selectThinking(
            request,
            SubprocessEnvironment.get(DISABLE_THINKING_ENV),
            SubprocessEnvironment.get(DISABLE_ADAPTIVE_THINKING_ENV),
            effectiveMaxTokens);
        boolean hasThinking = thinkingSelection.hasThinking();
        PromptCaching.CacheDecision promptCache = PromptCaching.resolve(request.model());

        CreateMessageRequest.Builder requestBuilder = CreateMessageRequest.builder()
            .model(request.model())
            .maxTokens(effectiveMaxTokens)
            .systemPrompt(request.systemPrompt())
            .messages(apiMessages)
            .stream(request.stream())
            .temperature(hasThinking ? null : 1.0)
            .skipCacheWrite(request.skipCacheWrite())
            .promptCachingEnabled(promptCache.enabled())
            .promptCacheTtl(promptCache.ttl())
            .querySource(request.querySource())
            .cancellationRegistrar(request.abortController() != null
                ? request.abortController()::registerOnAbort
                : CancellationRegistrar.NONE)
            .subagent(request.agentId() != null)
            .speed(request.fastMode() ? "fast" : null)
            .thinking(thinkingSelection.config());





// feature: shouldIncludeFirstPartyOnlyBetas  suppresses

        // context_management body field with it — when the user sets
        // CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS. Gating the attachment here (not
        // just the header push in AnthropicSdkClient) suppresses both body + header



        // a one-way latch (ThinkingClearLatch, core module — see its Javadoc for
        // why it isn't a field on this class) that flips to "keep only the last
        // thinking turn" once an agentic query happens more than
        // CACHE_TTL_1HOUR_MS after the last completed turn — past that gap the
        // prompt cache has already expired, so paying to resend every historical
        // thinking block buys no cache hit; keeping just the last turn trims real
        // input tokens off every request from then on. Reset on /clear
        // (SessionController.clearConversation) and after a completed compaction


// classifiers/compaction); every LlmClientAdapter.createStream call is
        // the main agentic loop in Java's architecture (compaction goes through
        // LlmCompactSummarizer directly, not this adapter), so that condition is
        // always true here too.
        if (hasThinking && CreateMessageRequest.supportsContextManagement(request.model())
                && firstPartyExperimentalBetasEnabled()) {
            if (!ThinkingClearLatch.isLatched()) {
                Long lastAssistantMs = lastAssistantTurnMsBySource.get(PROMPT_CACHE_SOURCE);
                if (lastAssistantMs != null
                        && System.currentTimeMillis() - lastAssistantMs > PromptCacheBreakDetection.CACHE_TTL_1HOUR_MS) {
                    ThinkingClearLatch.trip();
                }
            }
            requestBuilder.contextManagement(new CreateMessageRequest.ContextManagementConfig(
                List.of(ThinkingClearLatch.isLatched()
                    ? CreateMessageRequest.ContextEditStrategy.clearThinkingKeepLastTurn()
                    : CreateMessageRequest.ContextEditStrategy.clearThinkingKeepAll())));
        }




        if (StringUtils.isNotBlank(request.effort()) || request.taskBudget() != null) {
            requestBuilder.outputConfig(new CreateMessageRequest.OutputConfig(
                request.effort(), null, request.taskBudget()));
        }

        // Pass the main-loop tool array even when it is explicitly empty.

        // omitting the field, which is significant for lossless request replay.
        // Side queries bypass this adapter and retain their field-omission
        // contract. td.type() non-null marks an
        // Anthropic server-side tool (e.g. web_search_20250305) — no input_schema.
        // deferLoading threads straight through — see AnthropicSdkClient's
        // TOOL_SEARCH_BETA header, added whenever any tool carries it true.
        if (request.tools() != null) {
            List<CreateMessageRequest.ToolDefinition> apiTools = request.tools().stream()
                .map(td -> td.type() != null
                    ? CreateMessageRequest.ToolDefinition.serverTool(
                        td.type(), td.name(), td.maxUses(),
                        td.allowedDomains(), td.blockedDomains())
                    : new CreateMessageRequest.ToolDefinition(
                        td.name(),
                        td.description(),
                        td.inputSchema() instanceof JsonNode jn ? jn : null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        td.deferLoading() ? Boolean.TRUE : null,
                        CreateMessageRequest.strictToolEnabled(request.model(), td.strict())
                            ? Boolean.TRUE : null,
                        td.eagerInputStreaming() ? Boolean.TRUE : null
                    ))
                .toList();
            requestBuilder.tools(apiTools);
        }


        JsonNode metadata = requestMetadata(request.sessionId());
        if (metadata != null) requestBuilder.metadata(metadata);

        CreateMessageRequest apiRequest = requestBuilder.build();

        // Phase 1 of prompt-cache-break detection: snapshot the request state
        // (system/tools/model/betas/effort) BEFORE the call so phase 2 can
        // later compare the returned cache-read tokens against this baseline.

        PromptCacheBreakDetection.recordPromptState(buildCacheSnapshot(apiRequest, request.agentId()));

        // DUMP_PROMPTS=1: dump the exact wire body (same serialization the
        // Anthropic client sends, cache_control and all) keyed by session id.
// The isEnabled gate keeps the extra serialization off the normal path.
        if (ApiRequestDumper.instance().isEnabled()
                && request.sessionId() != null) {
            try {
                ApiRequestDumper.instance().dump(
                    request.sessionId(),
                    AnthropicSdkClient.serializeWithCacheControl(apiRequest));
            } catch (Exception e) {
                log.debug("dump-prompts serialization failed: {}", e.toString());
            }
        }

// Idle gap since the previous assistant turn finished — drives the cache-break detection
// TTL-expiry branches.
        Long timeSinceLastAssistantMs = lastAssistantTurnMsBySource.containsKey(PROMPT_CACHE_SOURCE)
            ? System.currentTimeMillis() - lastAssistantTurnMsBySource.get(PROMPT_CACHE_SOURCE)
            : null;

        // Get the api StreamEvent iterator and adapt to core StreamingEvent.
        // Overloaded primary + configured fallback model → FallbackTriggeredError

        Iterator<StreamEvent> apiEvents;
        try {
            apiEvents = llmClient.createMessageStream(apiRequest);
        } catch (ApiException e) {
            if (request.fastMode() && (e.statusCode() == 429 || e.statusCode() == 529)) {
                if (request.onFastModeFailure() != null) {
                    request.onFastModeFailure().accept(e.statusCode(), e.retryAfterSeconds());
                }
                apiRequest = withoutFastSpeed(apiRequest);
                PromptCacheBreakDetection.recordPromptState(
                    buildCacheSnapshot(apiRequest, request.agentId()));
                apiEvents = llmClient.createMessageStream(apiRequest);
                return new StreamingEventAdapter(
                    apiEvents, request, timeSinceLastAssistantMs, llmClient, apiRequest);
            }
            if (e.statusCode() == 404
                    && nonStreamingFallbackEnabledFor(
                        SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK"))) {
                StreamingEventAdapter recovery = new StreamingEventAdapter(
                    Collections.emptyIterator(), request, timeSinceLastAssistantMs,
                    llmClient, apiRequest);
                recovery.beginNonStreamingFallback();
                return recovery;
            }
            throw asFallbackIfOverloaded(e, request);
        }
        return new StreamingEventAdapter(
            apiEvents, request, timeSinceLastAssistantMs, llmClient, apiRequest);
    }


    static boolean nonStreamingFallbackEnabledFor(String disableEnvValue) {
        return !isEnvTruthy(disableEnvValue);
    }


    static final int MAX_NON_STREAMING_TOKENS = 64_000;


    static CreateMessageRequest nonStreamingRequest(CreateMessageRequest request) {
        int cappedMaxTokens = Math.min(request.maxTokens(), MAX_NON_STREAMING_TOKENS);
        CreateMessageRequest.ThinkingConfig thinking = request.thinking();
        if (thinking != null && Strings.CS.equals("enabled", thinking.type())
                && thinking.budgetTokens() != null && thinking.budgetTokens() > 0) {
            thinking = CreateMessageRequest.ThinkingConfig.enabled(
                Math.min(thinking.budgetTokens(), cappedMaxTokens - 1));
        }
        return new CreateMessageRequest(
            request.model(), cappedMaxTokens, request.systemPrompt(), request.messages(),
            request.tools(), request.metadata(), request.stopSequences(), false,
            request.temperature(), request.topP(), request.topK(), thinking,
            request.effort(), request.toolChoice(), request.outputConfig(), request.speed(),
            request.contextManagement(), request.skipCacheWrite(),
            request.promptCachingEnabled(), request.promptCacheTtl(), request.querySource(),
            request.cancellationRegistrar(), request.subagent());
    }

    /**
     * Persistent anonymous device id.
     */
    private static volatile String cachedDeviceId;

    /**
     * Builds the shared Messages API metadata shape for main and helper requests.
     * Package-private so CLI-owned side queries (notably session-title generation)
     * cannot drift from the main request's device/account/session identity.
     */
    static JsonNode requestMetadata(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return null;
        try {
            var mapper = JsonUtils.getMapper();
            var userId = mapper.createObjectNode();
// Spread user-supplied extra metadata first so the three identity keys below always
// override it.
            ObjectNode extra = ExtraMetadata.resolve();
            if (extra != null) userId.setAll(extra);
            userId.put("device_id", deviceId());
            userId.put("account_uuid", "");
            userId.put("session_id", sessionId);
            var metadata = mapper.createObjectNode();
            metadata.put("user_id", mapper.writeValueAsString(userId));
            return metadata;
        } catch (Exception e) {
            log.debug("metadata assembly failed: {}", e.toString());
            return null;
        }
    }

    private static String deviceId() {
        String id = cachedDeviceId;
        if (id != null) return id;
        synchronized (LlmClientAdapter.class) {
            if (cachedDeviceId != null) return cachedDeviceId;
            String existing = GlobalConfigStore.getString("userID", null);
            if (StringUtils.isBlank(existing)) {
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                existing = HexFormat.of().formatHex(bytes);
                try {
                    GlobalConfigStore.set("userID", existing);
                } catch (Exception _) {
                    // Unsaved id still works for this process; next run regenerates.
                }
            }
            cachedDeviceId = existing;
            return existing;
        }
    }

    /**
     * Maps an overloaded-API failure to {@link FallbackTriggeredError} when the request carries a
     * fallback model; otherwise rethrows the original exception.
     */
    private static RuntimeException asFallbackIfOverloaded(ApiException e, StreamRequest request) {
        boolean overloaded = e.statusCode() == 529
            || Strings.CS.equals("overloaded_error", e.errorType())
            || (e.getMessage() != null && Strings.CS.contains(e.getMessage(), "overloaded_error"));
        if (overloaded && request.fallbackModel() != null && !StringUtils.isBlank(request.fallbackModel())) {
            return new FallbackTriggeredError(request.model(), request.fallbackModel());
        }
        return e;
    }

    /**
     * Builds the cache-break-detection snapshot from a {@link CreateMessageRequest}.
     * <p>
     * Derives the beta list exactly as {@code AnthropicSdkClient.buildRequest} does
     * (effort-2025-11-24 / interleaved-thinking-2025-05-14 / context-management-2025-06-27)
     * so the {@code betasChanged} branch fires for the same changes the server sees.
     */
    private static CreateMessageRequest withoutFastSpeed(CreateMessageRequest request) {
        return CreateMessageRequest.builder()
            .model(request.model())
            .maxTokens(request.maxTokens())
            .systemPrompt(request.systemPrompt())
            .messages(request.messages())
            .tools(request.tools())
            .metadata(request.metadata())
            .stopSequences(request.stopSequences())
            .stream(request.stream())
            .temperature(request.temperature())
            .topP(request.topP())
            .topK(request.topK())
            .thinking(request.thinking())
            .effort(request.effort())
            .toolChoice(request.toolChoice())
            .outputConfig(request.outputConfig())
            .contextManagement(request.contextManagement())
            .skipCacheWrite(request.skipCacheWrite())
            .promptCachingEnabled(request.promptCachingEnabled())
            .promptCacheTtl(request.promptCacheTtl())
            .querySource(request.querySource())
            .cancellationRegistrar(request.cancellationRegistrar())
            .subagent(request.subagent())
            .build();
    }

    private static PromptCacheBreakDetection.PromptStateSnapshot buildCacheSnapshot(CreateMessageRequest req, String agentId) {
        String cacheControl = !req.promptCachingEnabled() ? null
            : req.promptCacheTtl() == CreateMessageRequest.PromptCacheTtl.ONE_HOUR
                ? "ephemeral;ttl=1h" : "ephemeral";
        List<PromptCacheBreakDetection.SystemBlock> system = req.systemPrompt() == null ? List.of()
            : List.of(new PromptCacheBreakDetection.SystemBlock(req.systemPrompt(), cacheControl));

        List<PromptCacheBreakDetection.ToolSchema> tools = req.tools() == null ? List.of()
            : req.tools().stream().map(t -> new PromptCacheBreakDetection.ToolSchema(
                  t.name(),
                  t.description(),
                  t.inputSchema() instanceof JsonNode jn ? jn.toString() : null))
                .toList();

        String effort = (StringUtils.isNotBlank(req.effort())) ? req.effort()
            : (req.outputConfig() != null && req.outputConfig().effort() != null
                && !StringUtils.isBlank(req.outputConfig().effort()) ? req.outputConfig().effort() : null);

        List<String> betas = new ArrayList<>();
        if (effort != null) {
            betas.add("effort-2025-11-24");
        }
        if (req.thinking() != null
                && (Strings.CS.equals("enabled", req.thinking().type()) || Strings.CS.equals("adaptive", req.thinking().type()))) {
            betas.add("interleaved-thinking-2025-05-14");
        }
        if (req.contextManagement() != null) {
            betas.add("context-management-2025-06-27");
        }
        if (Strings.CS.equals("fast", req.speed())) {
            betas.add("fast-mode-2026-02-01");
        }
        if (req.promptCachingEnabled()
                && req.promptCacheTtl() == CreateMessageRequest.PromptCacheTtl.ONE_HOUR) {
            betas.add("extended-cache-ttl-2025-04-11");
        }
        betas.sort(null);

        // Main REPL requests carry no agentId → tracked under PROMPT_CACHE_SOURCE
        // ("repl_main_thread"). Sub-agent requests carry the per-invocation
        // agentId (threaded from QuerySessionSpec via StreamRequest) → tracked

        // getTrackingKey(querySource, agentId) returning agentId for any tracked
        // agent prefix. This is what makes cleanupAgentTracking(agentId) remove
        // the right entry when the sub-agent finishes.
        // Main REPL requests carry no agentId → tracked under PROMPT_CACHE_SOURCE
        // ("repl_main_thread"). Sub-agent requests carry the per-invocation
        // agentId (threaded from QuerySessionSpec via StreamRequest) → tracked

        // getTrackingKey(querySource, agentId) returning agentId for any tracked
        // agent prefix. This is what makes cleanupAgentTracking(agentId) remove
        // the right entry when the sub-agent finishes.
        String querySource = (agentId != null) ? "agent:default" : PROMPT_CACHE_SOURCE;

        return new PromptCacheBreakDetection.PromptStateSnapshot(
            system,
            tools,
            querySource,
            req.model(),
            agentId,
            Strings.CS.equals("fast", req.speed()),
            null,      // globalCacheStrategy
            betas,
            null,      // autoModeActive
            null,      // isUsingOverage
            null,      // cachedMCEnabled
            effort,
            ExtraBodyParams.resolve()
        );
    }

    @Override
    public String getModel() {
        return llmClient.getModel();
    }

    @Override
    public String provider() {
        String type = llmClient.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(type, "vertex")) return "vertex";
        if (Strings.CS.contains(type, "foundry")) return "foundry";
        if (Strings.CS.contains(type, "bedrock")) return "bedrock";
        return Strings.CS.contains( type, "openai") ? "openai" : "firstParty";
    }

    /**
     * Adapts api module's StreamEvent iterator to core module's StreamingEvent iterator.
     */
    static class StreamingEventAdapter implements StreamingClient.TimedStreamingIterator {

        private Iterator<StreamEvent> apiEvents;
        private Supplier<Iterator<StreamEvent>> streamFactory;
        private final StreamRequest request;
        private final Long timeSinceLastAssistantMs;
        private final LlmClient llmClient;
        private CreateMessageRequest apiRequest;
        private Iterator<StreamEvent> fallbackEvents;
        private final Deque<StreamingEvent> pendingCoreEvents = new ArrayDeque<>();
        private final Map<Integer, String> blockTypes = new HashMap<>();
        private boolean fallbackAttempted;
        private Integer pendingFallbackInitial529;
        private boolean visibleOutputCompleted;
        private boolean completedToolUse;
        private RuntimeException pendingFailure;
        private int staleConnectionRetries;
        private int watchdogRetries;
        private StreamingEvent nextEvent = null;
        private boolean done = false;
        private long cacheReadTokens = 0;
        private long cacheCreationTokens = 0;
        private volatile long lastAttemptStartMs;

        StreamingEventAdapter(Iterator<StreamEvent> apiEvents) {
            this(apiEvents, null, null, null, null);
        }

        StreamingEventAdapter(Iterator<StreamEvent> apiEvents, StreamRequest request,
                Long timeSinceLastAssistantMs, LlmClient llmClient,
                CreateMessageRequest apiRequest) {
            this.apiEvents = apiEvents;
            this.request = request;
            this.timeSinceLastAssistantMs = timeSinceLastAssistantMs;
            this.llmClient = llmClient;
            this.apiRequest = apiRequest;
            this.streamFactory = llmClient != null && apiRequest != null
                ? () -> llmClient.createMessageStream(apiRequest) : null;
        }

        @Override
        public boolean hasNext() {
            if (nextEvent != null) return true;
            if (done) return false;

            while (true) {
                if (!pendingCoreEvents.isEmpty()) {
                    nextEvent = pendingCoreEvents.removeFirst();
                    return true;
                }
                if (pendingFallbackInitial529 != null) {
                    int initial529Errors = pendingFallbackInitial529;
                    pendingFallbackInitial529 = null;
                    executeNonStreamingFallback(initial529Errors);
                    continue;
                }
                if (pendingFailure != null) {
                    RuntimeException failure = pendingFailure;
                    pendingFailure = null;
                    done = true;
                    throw failure;
                }
                Iterator<StreamEvent> source = fallbackEvents != null ? fallbackEvents : apiEvents;
                if (!source.hasNext()) {
                    if (fallbackEvents != null) {
                        fallbackEvents = null;
                        continue;
                    }
                    done = true;
                    return false;
                }
                StreamEvent apiEvent = source.next();
                if (apiEvent instanceof StreamEvent.RequestTiming(long attemptStartMs)) {
                    lastAttemptStartMs = attemptStartMs;
                    continue;
                }

                if (request != null
                        && apiEvent instanceof StreamEvent.Error err
                        && err.exception() instanceof ApiException apiEx) {
                    if (request.fastMode() && (apiEx.statusCode() == 429 || apiEx.statusCode() == 529)
                            && request.onFastModeFailure() != null) {
                        request.onFastModeFailure().accept(
                            apiEx.statusCode(), apiEx.retryAfterSeconds());
                        switchToStandardSpeed();
                    }
                    if (apiEx instanceof ApiStreamException streamFailure
                            && streamFailure.reason() == ApiStreamException.Reason.ABORTED) {
                        done = true;
                        throw new AbortException("Request aborted");
                    }
                    if (shouldFinalizePartial(apiEx)) {
                        finalizePartialResponse(apiEx);
                        continue;
                    }
                    if (retryStreaming(apiEx)) continue;

                    if (apiEx.statusCode() == 529 && beginNonStreamingFallback(1)) {
                        continue;
                    }
                    RuntimeException overloaded = asFallbackIfOverloaded(apiEx, request);
                    if (overloaded instanceof FallbackTriggeredError) {
                        done = true;
                        throw overloaded;
                    }
                    if (beginNonStreamingFallback()) {
                        continue;
                    }
                }
                StreamingEvent converted = convert(apiEvent);
                if (converted != null) {
                    nextEvent = converted;
                    if (converted instanceof StreamingEvent.MessageStopEvent
                            && pendingCoreEvents.isEmpty()) {
                        done = true;
                    }
                    return true;
                }
            }
        }

        @Override
        public StreamingEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            StreamingEvent event = nextEvent;
            nextEvent = null;
            return event;
        }

        @Override
        public long lastAttemptStartMs() {
            return lastAttemptStartMs;
        }


        private boolean beginNonStreamingFallback() {
            return beginNonStreamingFallback(0);
        }

        private boolean beginNonStreamingFallback(int initial529Errors) {
            if (fallbackAttempted
                    || request == null
                    || !request.stream()
                    || llmClient == null
                    || apiRequest == null
                    || !nonStreamingFallbackEnabledFor(
                        SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_NONSTREAMING_FALLBACK"))) {
                return false;
            }
            fallbackAttempted = true;
            pendingCoreEvents.addLast(new StreamingEvent.FallbackBeganEvent());
            pendingFallbackInitial529 = initial529Errors;
            return true;
        }

        private void executeNonStreamingFallback(int initial529Errors) {
            // The bounded per-attempt timeout is what lets MAX_NON_STREAMING_TOKENS
            // sit above the SDK's own token cap — see ApiTimeouts.
            lastAttemptStartMs = System.currentTimeMillis();
            List<ApiRetryEvents.Event> retryEvents = new ArrayList<>();
            try {
                ApiMessage message = ApiRetryEvents.observe(retryEvents::add, () ->
                    ApiRetryContext.withInitial529Errors(initial529Errors, () ->
                        llmClient.createMessage(
                            nonStreamingRequest(apiRequest),
                            ApiTimeouts.nonStreamingFallbackTimeout().toMillis())));
                fallbackEvents = syntheticEvents(message).iterator();
                if (request.onStreamingFallback() != null) {
                    request.onStreamingFallback().run();
                }
            } catch (RuntimeException failure) {
                pendingFailure = failure instanceof ApiException apiFailure
                    ? asFallbackIfOverloaded(apiFailure, request) : failure;
            }
            for (ApiRetryEvents.Event retry : retryEvents) {
                pendingCoreEvents.addLast(new StreamingEvent.ApiRetryEvent(
                    retry.status(), retry.retryAttempt(), retry.maxRetries(), retry.retryInMs()));
            }
        }

        private boolean shouldFinalizePartial(ApiException failure) {
            if (!visibleOutputCompleted || !(failure instanceof ApiStreamException streamFailure)) {
                return false;
            }
            return streamFailure.reason() == ApiStreamException.Reason.WATCHDOG
                || streamFailure.reason() == ApiStreamException.Reason.STALE_CONNECTION;
        }

        private void switchToStandardSpeed() {
            if (apiRequest == null || !Strings.CS.equals("fast", apiRequest.speed())) return;
            apiRequest = withoutFastSpeed(apiRequest);
            streamFactory = llmClient != null
                ? () -> llmClient.createMessageStream(apiRequest) : null;
        }

        private boolean retryStreaming(ApiException failure) {
            if (visibleOutputCompleted || streamFactory == null
                    || !(failure instanceof ApiStreamException streamFailure)) {
                return false;
            }
            boolean retry = switch (streamFailure.reason()) {
                case STALE_CONNECTION -> staleConnectionRetries++ < 2;
                case WATCHDOG -> watchdogRetries++ < 1;
                case ABORTED, OTHER -> false;
            };
            if (!retry) return false;
            try {
                apiEvents = streamFactory.get();
            } catch (ApiException creationFailure) {
                apiEvents = List.<StreamEvent>of(new StreamEvent.Error(creationFailure)).iterator();
            }
            blockTypes.clear();
            return true;
        }

        private void finalizePartialResponse(ApiException failure) {
            String stopReason = completedToolUse ? "tool_use" : "end_turn";
            pendingCoreEvents.addLast(new StreamingEvent.MessageDeltaEvent(
                stopReason, null, Usage.EMPTY, null));
            pendingCoreEvents.addLast(new StreamingEvent.MessageStopEvent());
            String content = failure instanceof ApiStreamException streamFailure
                    && streamFailure.reason() == ApiStreamException.Reason.WATCHDOG
                ? "API Error: Response stalled mid-stream. The response above may be incomplete."
                : "API Error: Connection closed mid-response. The response above may be incomplete.";
            pendingCoreEvents.addLast(new StreamingEvent.SystemApiErrorEvent(
                content, null, "server_error"));
            fallbackAttempted = true;
            fallbackEvents = Collections.emptyIterator();
        }

        /** Convert one normal Messages response into the stream event contract consumed by QueryLoop. */
        private static List<StreamEvent> syntheticEvents(ApiMessage message) {
            if (message == null) {
                throw new ApiException("Non-streaming fallback returned no message", 0);
            }
            if (!Strings.CS.equals("assistant", message.role())
                    || message.content() == null || message.content().isEmpty()) {
                throw new ApiException(
                    "Non-streaming fallback returned a malformed assistant message", 0);
            }
            List<StreamEvent> events = new ArrayList<>();
            events.add(new StreamEvent.MessageStart(
                message, null, ApiMessageTiming.requestId(message)));
            List<ContentBlock> blocks = message.content();
            for (int i = 0; i < blocks.size(); i++) {
                ContentBlock block = blocks.get(i);
                switch (block) {
                    case TextBlock(String text1) -> {
                        events.add(new StreamEvent.ContentBlockStart(i, new TextBlock("")));
                        events.add(new StreamEvent.ContentBlockDelta(i, new Delta.TextDelta(
                            text1 == null ? "" : text1)));
                    }
                    case ToolUseBlock tool -> {
                        events.add(new StreamEvent.ContentBlockStart(i,
                            new ToolUseBlock(tool.id(), tool.name(), null)));
                        events.add(new StreamEvent.ContentBlockDelta(i, new Delta.InputJsonDelta(
                            tool.input() == null ? "{}" : tool.input().toString())));
                    }
                    case ThinkingBlock(String thinking1, String signature) -> {
                        events.add(new StreamEvent.ContentBlockStart(i, new ThinkingBlock("", "")));
                        events.add(new StreamEvent.ContentBlockDelta(i, new Delta.ThinkingDelta(
                            thinking1 == null ? "" : thinking1)));
                        if (signature != null) {
                            events.add(new StreamEvent.ContentBlockDelta(i,
                                new Delta.SignatureDelta(signature)));
                        }
                    }
                    case null, default ->
                        // Preserve uncommon complete blocks as a start event; the
                        // core query loop ignores the raw block payload but still
                        // receives the same block boundary and terminal event.
                        events.add(new StreamEvent.ContentBlockStart(i, block));
                }
                events.add(new StreamEvent.ContentBlockStop(i));
            }
            Usage usage = message.usage() == null ? Usage.EMPTY : message.usage();
            events.add(new StreamEvent.MessageDelta(
                new MessageDeltaData(message.stopReason(), message.stopSequence(),
                    message.stopDetails()), usage));
            events.add(new StreamEvent.MessageStop());
            return events;
        }

        private StreamingEvent convert(StreamEvent apiEvent) {
            return switch (apiEvent) {
                case StreamEvent.MessageStart ms -> {
                    var msg = ms.message();
                    List<ContentBlock> content = msg != null && msg.content() != null
                        ? msg.content() : List.of();
                    Usage usage = msg != null && msg.usage() != null
                        ? msg.usage() : Usage.EMPTY;
                    String messageId = msg != null ? msg.id() : null;
                    String model = msg != null ? msg.model() : null;
                    yield new StreamingEvent.MessageStartEvent(
                        messageId, model, content, usage, ms.rawEvent(), ms.requestId()
                    );
                }
                case StreamEvent.ContentBlockStart cbs -> {
                    // Forward content_block_start so the engine can detect tool_use blocks
                    ContentBlock block = cbs.contentBlock();
                    String type = "text";
                    String id = null;
                    String name = null;
                    ContentBlock completeBlock = null;
                    if (block instanceof ToolUseBlock tub) {
                        type = "tool_use";
                        id = tub.id();
                        name = tub.name();
                    } else if (block instanceof ThinkingBlock) {
                        type = "thinking";
                    } else if (block instanceof RedactedThinkingBlock) {
                        type = "redacted_thinking";
                        completeBlock = block;
                    } else if (block instanceof TextBlock) {
                        type = "text";
                    } else if (block instanceof ServerToolUseBlock stub) {
                        // Anthropic-executed tool (e.g. web_search) — input.query
                        // fills in via input_json_delta same as ToolUseBlock.
                        type = "server_tool_use";
                        id = stub.id();
                        name = stub.name();
                    } else if (block instanceof WebSearchToolResultBlock) {
                        // Arrives complete — no deltas follow for this block type.
                        type = "web_search_tool_result";
                        completeBlock = block;
                    } else if (block instanceof ServerToolResultBlock) {
                        type = "server_tool_result";
                        completeBlock = block;
                    }
                    blockTypes.put(cbs.index(), type);
                    yield new StreamingEvent.ContentBlockStartEvent(
                        cbs.index(), type, id, name, completeBlock, cbs.rawEvent()
                    );
                }
                case StreamEvent.ContentBlockDelta cbd -> {
                    String text = "";
                    String deltaType = "text_delta";
                    if (cbd.delta() instanceof Delta.TextDelta(String text1)) {
                        text = text1;
                        deltaType = "text_delta";
                    } else if (cbd.delta() instanceof Delta.ThinkingDelta(String thinking)) {
                        text = thinking;
                        deltaType = "thinking_delta";
                    } else if (cbd.delta() instanceof Delta.SignatureDelta(String signature)) {
                        text = signature;
                        deltaType = "signature_delta";
                    } else if (cbd.delta() instanceof Delta.InputJsonDelta(String partialJson)) {
                        text = partialJson;
                        deltaType = "input_json_delta";
                    }
                    String blockType = blockTypes.get(cbd.index());
                    if (blockType != null && !Strings.CS.equals("thinking", blockType)
                            && !Strings.CS.equals("redacted_thinking", blockType)) {
                        visibleOutputCompleted = true;
                        if (Strings.CS.equals("tool_use", blockType)) completedToolUse = true;
                    }
                    yield new StreamingEvent.ContentBlockDeltaEvent(
                        cbd.index(), deltaType, text, cbd.rawEvent()
                    );
                }
                case StreamEvent.ContentBlockStop cbs -> {
                    String type = blockTypes.remove(cbs.index());
                    if (type != null && !Strings.CS.equals("thinking", type)
                            && !Strings.CS.equals("redacted_thinking", type)) {
                        visibleOutputCompleted = true;
                        if (Strings.CS.equals("tool_use", type)) completedToolUse = true;
                    }
                    yield new StreamingEvent.ContentBlockStopEvent(cbs.index(), cbs.rawEvent());
                }
                case StreamEvent.MessageDelta md -> {
                    String stopReason = md.delta() != null ? md.delta().stopReason() : null;
                    String stopSequence = md.delta() != null ? md.delta().stopSequence() : null;
                    StopDetails stopDetails = md.delta() != null ? md.delta().stopDetails() : null;
                    Usage usage = md.usage() != null ? md.usage() : Usage.EMPTY;
                    // Cache tokens arrive only in the final delta; take the max
                    // to absorb partial deltas that report 0.
                    cacheReadTokens = Math.max(cacheReadTokens, usage.cacheReadInputTokens());
                    cacheCreationTokens = Math.max(cacheCreationTokens, usage.cacheCreationInputTokens());
                    yield new StreamingEvent.MessageDeltaEvent(
                        stopReason, stopSequence, usage, md.rawEvent(), stopDetails);
                }
                case StreamEvent.MessageStop ignored -> {
                    // Record the end of this assistant turn so the next request
                    // can measure the idle gap for TTL-expiry detection.
                    lastAssistantTurnMsBySource.put(PROMPT_CACHE_SOURCE, System.currentTimeMillis());
                    // Phase 2 of prompt-cache-break detection: compare the
                    // returned cache-read tokens to phase 1's baseline.
                    PromptCacheBreakDetection.checkResponseForCacheBreak(
                        PROMPT_CACHE_SOURCE, cacheReadTokens, cacheCreationTokens,
                        timeSinceLastAssistantMs, null);
                    yield new StreamingEvent.MessageStopEvent(ignored.rawEvent());
                }
                case StreamEvent.Error err ->
                    new StreamingEvent.ErrorEvent(err.exception(), err.rawEvent());
                case StreamEvent.Ping _, StreamEvent.RequestTiming _ -> null;
            };
        }
    }
}
