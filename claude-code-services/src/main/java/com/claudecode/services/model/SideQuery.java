package com.claudecode.services.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiMessage;
import com.claudecode.api.ApiMessageTiming;
import com.claudecode.api.ApiException;
import com.claudecode.api.ApiStreamException;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.Delta;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.message.*;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.AbortException;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.http.CancellationRegistrar;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single entry point for "side queries" — one-off API calls fired outside the main conversation
 * loop (title generation, permission explanations, session ranking, hook prompts, model validation,
 * MCP-in-Chrome side channels).
 */
public final class SideQuery {

    private static final Logger log = LoggerFactory.getLogger(SideQuery.class);
    private static final ScheduledExecutorService TIMEOUTS =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "side-query-timeout");
            thread.setDaemon(true);
            return thread;
        });

/**
     * Default when no env override is set.
     */
    public static final String DEFAULT_HAIKU_MODEL = "claude-haiku-4-5";

    private final LlmClient llmClient;

    public SideQuery(LlmClient llmClient) {
        this.llmClient = llmClient;
    }


    public static String resolveSmallFastModel() {
        return resolveSmallFastModel(null);
    }

    /**
     * Resolves the helper model with the active main model available.
     */
    public static String resolveSmallFastModel(String mainModel) {
        return resolveSmallFastModel(
            mainModel,
            SubprocessEnvironment.get("ANTHROPIC_SMALL_FAST_MODEL"),
            SubprocessEnvironment.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
    }

    static String resolveSmallFastModel(String mainModel, String override, String defaultHaiku) {
        if (StringUtils.isNotBlank(override)) return override;
        if (StringUtils.isNotBlank(defaultHaiku)) return defaultHaiku;
        if (isCustomModel(mainModel)) return mainModel;
        return DEFAULT_HAIKU_MODEL;
    }

    private static boolean isCustomModel(String model) {
        if (StringUtils.isBlank(model)) return false;
        String normalized = model.toLowerCase(Locale.ROOT);
        return !Strings.CS.contains(normalized, "claude-")
            && !Strings.CS.contains(normalized, "anthropic.claude")
            && !Strings.CS.startsWith(normalized, "anthropic/");
    }

    // ── Convenience presets ─────────────────────────────────────────────────

    /**
     * Haiku one-shot text query — used by title generation and session ranking.
     */
    public String queryHaiku(String systemPrompt, String userPrompt) {
        return firstText(queryHaikuMessage(systemPrompt, userPrompt));
    }


    public ApiMessage queryHaikuMessage(String systemPrompt, String userPrompt) {
        String model = resolveSmallFastModel();
        try {
            return queryStreamingMessageOrThrow(new Request()
                .model(model)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .maxTokens(Math.toIntExact(ModelOutputTokens.getMaxOutputTokensForModel(model)))
                .tools(List.of())
                .thinking(CreateMessageRequest.ThinkingConfig.disabled())
                .promptCachingEnabled(false)
                .streaming(true));
        } catch (Exception failure) {
            logFailure(new Request().model(model), failure);
            return null;
        }
    }

    /**
     * Arbitrary-model text query — used by AgentHook LLM classifiers and anywhere a caller has its own
     * model choice.
     */
    public String queryText(String model, String systemPrompt, String userPrompt, int maxTokens) {
        return queryText(model, systemPrompt, userPrompt, maxTokens, 0L);
    }

    /**
     * Arbitrary-model text query with a wall-clock bound.
     */
    public String queryText(String model, String systemPrompt, String userPrompt,
                            int maxTokens, long timeoutMillis) {
        return queryText(model, systemPrompt, userPrompt, maxTokens, timeoutMillis, "side_query");
    }

    public String queryText(String model, String systemPrompt, String userPrompt,
                            int maxTokens, long timeoutMillis, String querySource) {
        if (StringUtils.isBlank(model)) return null;
        ApiMessage response = query(new Request()
            .model(model)
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .maxTokens(maxTokens)
            .timeoutMillis(timeoutMillis)
            .querySource(querySource));
        return firstText(response);
    }

    /**
     * Executes a fully-shaped side query without swallowing API exceptions.
     */
    public String queryTextOrThrow(Request request) {
        if (request != null && request.streaming) {
            return firstText(queryStreamingMessageOrThrow(request));
        }
        return firstText(queryOrThrow(request));
    }

    /**
     * Tool-forced structured query — used by the permission explainer to get a guaranteed JSON payload
     * back via a required tool call.
     */
    public JsonNode queryToolForced(String model,
                                    String systemPrompt,
                                    String userPrompt,
                                    CreateMessageRequest.ToolDefinition tool,
                                    int maxTokens) {
        if (StringUtils.isBlank(model) || tool == null) return null;
        ApiMessage response = query(new Request()
            .model(model)
            .systemPrompt(systemPrompt)
            .userPrompt(userPrompt)
            .maxTokens(maxTokens)
            .tool(tool)
            .forcedToolName(tool.name()));
        if (response == null || response.content() == null) return null;
        for (ContentBlock block : response.content()) {
            if (block instanceof ToolUseBlock tub && tool.name().equals(tub.name())) {
                return tub.input();
            }
        }
        return null;
    }

    /**
     * Full-shape entry point. Any preset above ultimately funnels through
     * here so the request-building + error-swallowing policy stays in one
     * place. Returns the raw {@link ApiMessage} or {@code null} on failure.
     */
    public ApiMessage query(Request req) {
        try {
            return queryOrThrow(req);
        } catch (Exception e) {
            logFailure(req, e);
            return null;
        }
    }

    private static void logFailure(Request request, Exception error) {
        log.debug("SideQuery failed (model={}): {}",
            request != null ? request.model : null, error.getMessage());
    }

    /** Raw full-shape entry point that preserves provider exceptions. */
    public ApiMessage queryOrThrow(Request req) {
        if (llmClient == null || req == null) return null;
        boolean hasMessages = req.messages != null && !req.messages.isEmpty();
        if (!hasMessages && (StringUtils.isBlank(req.userPrompt))) return null;
        CreateMessageRequest.Builder b = CreateMessageRequest.builder()
            .model(req.model)
            .maxTokens(req.maxTokens)
            .messages(hasMessages ? req.messages
                : List.of(new CreateMessageRequest.RequestMessage("user", req.userPrompt)))
            .stream(false)
            .querySource(req.querySource);
        if (StringUtils.isNotBlank(req.systemPrompt)) {
            b.systemPrompt(req.systemPrompt + (req.systemPromptUncachedSuffix == null
                ? "" : CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY
                    + req.systemPromptUncachedSuffix));
        }
        if (req.tools != null) {
            b.tools(req.tools);
        }
        if (req.forcedToolName != null) {
            b.toolChoice(CreateMessageRequest.ToolChoice.forTool(req.forcedToolName));
        }
        if (req.thinking != null) b.thinking(req.thinking);
        if (req.outputConfig != null) b.outputConfig(req.outputConfig);
        if (req.metadata != null) b.metadata(req.metadata);
        if (req.stopSequences != null) b.stopSequences(req.stopSequences);
        if (req.temperature != null) b.temperature(req.temperature);
        if (!req.promptCachingEnabled) b.promptCachingEnabled(false);
        CreateMessageRequest built = b.build();
        long startedAt = System.currentTimeMillis();
        ApiMessage response = req.timeoutMillis > 0
            ? llmClient.createMessage(built, req.timeoutMillis)
            : llmClient.createMessage(built);
        long completedAt = System.currentTimeMillis();
        SessionCostState.get().recordApiRequest(
            response != null && response.model() != null ? response.model() : req.model,
            response != null ? response.usage() : null,
            completedAt - startedAt,
            completedAt - ApiMessageTiming.lastAttemptStartMs(response, startedAt));
        return response;
    }

    /**
     * Fully consumes a streaming helper request and reconstructs the final assistant message.
     */
    public ApiMessage queryStreamingMessageOrThrow(Request req) {
        if (llmClient == null || req == null) return null;
        boolean hasMessages = req.messages != null && !req.messages.isEmpty();
        if (!hasMessages && (StringUtils.isBlank(req.userPrompt))) return null;

        AtomicReference<Runnable> cancelAction = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        CancellationRegistrar cancellation = action -> {
            cancelAction.set(action);
            if (timedOut.get() && cancelAction.compareAndSet(action, null)) action.run();
            return () -> cancelAction.compareAndSet(action, null);
        };
        CreateMessageRequest.Builder builder = CreateMessageRequest.builder()
            .model(req.model)
            .maxTokens(req.maxTokens)
            .messages(hasMessages ? req.messages
                : List.of(new CreateMessageRequest.RequestMessage("user", req.userPrompt)))
            .stream(true)
            .querySource(req.querySource)
            .cancellationRegistrar(cancellation);
        if (StringUtils.isNotBlank(req.systemPrompt)) {
            builder.systemPrompt(req.systemPrompt + (req.systemPromptUncachedSuffix == null
                ? "" : CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY
                    + req.systemPromptUncachedSuffix));
        }
        if (req.tools != null) builder.tools(req.tools);
        if (req.forcedToolName != null) {
            builder.toolChoice(CreateMessageRequest.ToolChoice.forTool(req.forcedToolName));
        }
        if (req.thinking != null) builder.thinking(req.thinking);
        if (req.outputConfig != null) builder.outputConfig(req.outputConfig);
        if (req.metadata != null) builder.metadata(req.metadata);
        if (req.stopSequences != null) builder.stopSequences(req.stopSequences);
        if (req.temperature != null) builder.temperature(req.temperature);
        if (!req.promptCachingEnabled) builder.promptCachingEnabled(false);

        ScheduledFuture<?> timeout = req.timeoutMillis > 0
            ? TIMEOUTS.schedule(() -> {
                timedOut.set(true);
                Runnable action = cancelAction.getAndSet(null);
                if (action != null) action.run();
            }, req.timeoutMillis, TimeUnit.MILLISECONDS)
            : null;
        try {
            Map<Integer, StreamingBlock> blocks = new TreeMap<>();
            Usage usage = Usage.EMPTY;
            String servingModel = req.model;
            ApiMessage shell = null;
            String stopReason = null;
            String stopSequence = null;
            StopDetails stopDetails = null;
            long startedAt = System.currentTimeMillis();
            long finalAttemptStartMs = startedAt;
            Iterator<StreamEvent> events = llmClient.createMessageStream(builder.build());
            while (events.hasNext()) {
                StreamEvent event = events.next();
                if (event instanceof StreamEvent.RequestTiming(long lastAttemptStartMs)) {
                    finalAttemptStartMs = lastAttemptStartMs;
                } else if (event instanceof StreamEvent.MessageStart start) {
                    shell = start.message();
                    if (start.message() != null && start.message().model() != null) {
                        servingModel = start.message().model();
                    }
                    if (start.message() != null && start.message().usage() != null) {
                        usage = start.message().usage();
                    }
                } else if (event instanceof StreamEvent.MessageDelta delta
                        ) {
                    if (delta.usage() != null) usage = usage.updateCumulative(delta.usage());
                    if (delta.delta() != null) {
                        stopReason = delta.delta().stopReason();
                        stopSequence = delta.delta().stopSequence();
                        stopDetails = delta.delta().stopDetails();
                    }
                } else if (event instanceof StreamEvent.ContentBlockStart start) {
                    blocks.put(start.index(), new StreamingBlock(start.contentBlock()));
                } else if (event instanceof StreamEvent.ContentBlockDelta delta) {
                    blocks.computeIfAbsent(delta.index(), _ -> StreamingBlock.forDelta(delta.delta()))
                        .append(delta.delta());
                } else if (event instanceof StreamEvent.Error error) {
                    if (error.exception() instanceof ApiStreamException streamFailure
                            && streamFailure.reason() == ApiStreamException.Reason.ABORTED
                            && !timedOut.get()) {
                        throw new AbortException("Request aborted");
                    }
                    throw error.exception();
                }
            }
            if (timedOut.get()) {
                throw new ApiException("Side query timed out after " + req.timeoutMillis + "ms", 0);
            }
            long completedAt = System.currentTimeMillis();
            SessionCostState.get().recordApiRequest(
                servingModel, usage, completedAt - startedAt,
                completedAt - finalAttemptStartMs);
            if (shell == null) {
                throw new IllegalStateException("No assistant message found");
            }
            List<ContentBlock> content = blocks.values().stream()
                .map(StreamingBlock::finish)
                .toList();
            return new ApiMessage(
                shell.id(), shell.type(), shell.role(), content, servingModel,
                stopReason != null ? stopReason : shell.stopReason(),
                stopSequence != null ? stopSequence : shell.stopSequence(),
                usage, stopDetails != null ? stopDetails : shell.stopDetails());
        } finally {
            if (timeout != null) timeout.cancel(false);
            cancelAction.set(null);
        }
    }

    private static final class StreamingBlock {
        private final ContentBlock initial;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();
        private final StringBuilder inputJson = new StringBuilder();

        private StreamingBlock(ContentBlock initial) {
            this.initial = initial;
            if (initial instanceof TextBlock(String text1) && text1 != null) text.append(text1);
            if (initial instanceof ThinkingBlock(String thinking1, String signature1)) {
                if (thinking1 != null) thinking.append(thinking1);
                if (signature1 != null) signature.append(signature1);
            }
        }

        static StreamingBlock forDelta(Delta delta) {
            if (delta instanceof Delta.ThinkingDelta || delta instanceof Delta.SignatureDelta) {
                return new StreamingBlock(new ThinkingBlock("", null));
            }
            return new StreamingBlock(new TextBlock(""));
        }

        void append(Delta delta) {
            switch (delta) {
                case Delta.TextDelta value -> text.append(value.text());
                case Delta.ThinkingDelta value -> thinking.append(value.thinking());
                case Delta.SignatureDelta value -> signature.append(value.signature());
                case Delta.InputJsonDelta value -> inputJson.append(value.partialJson());
            }
        }

        ContentBlock finish() {
            return switch (initial) {
                case TextBlock _ -> new TextBlock(text.toString());
                case ThinkingBlock _ -> new ThinkingBlock(
                    thinking.toString(), signature.isEmpty() ? null : signature.toString());
                case ToolUseBlock tool -> new ToolUseBlock(
                    tool.id(), tool.name(), inputJson.isEmpty()
                        ? tool.input()
                        : parseToolInput(inputJson.toString()), tool.caller());
                default -> initial;
            };
        }

        private static JsonNode parseToolInput(String json) {
            try {
                return JsonUtils.getMapper().readTree(json);
            } catch (Exception failure) {
                throw new IllegalStateException("Malformed streamed tool input", failure);
            }
        }
    }

    private static String firstText(ApiMessage response) {
        if (response == null || response.content() == null) return null;
        for (ContentBlock block : response.content()) {
            if (block instanceof TextBlock(String text)) return text;
        }
        return null;
    }

    /**
     * Mutable request holder. Kept package-visible / builder-style so the
     * preset methods above stay compact and future call sites (thinking
     * budget, stop sequences, retry counts) can add fields without breaking
     * existing signatures.
     */
    public static final class Request {
        String model;
        String systemPrompt;
        String systemPromptUncachedSuffix;
        String userPrompt;
        int maxTokens = 1024;
        long timeoutMillis = 0L;
        List<CreateMessageRequest.ToolDefinition> tools;
        String forcedToolName;
        List<CreateMessageRequest.RequestMessage> messages;
        CreateMessageRequest.ThinkingConfig thinking;
        CreateMessageRequest.OutputConfig outputConfig;
        JsonNode metadata;
        List<String> stopSequences;
        Double temperature;
        boolean promptCachingEnabled = true;
        boolean streaming;
        String querySource = "side_query";

        public Request model(String model) { this.model = model; return this; }
        public Request systemPrompt(String s) { this.systemPrompt = s; return this; }
        public Request systemPromptUncachedSuffix(String s) {
            this.systemPromptUncachedSuffix = s; return this;
        }
        public Request userPrompt(String s) { this.userPrompt = s; return this; }
        public Request messages(List<CreateMessageRequest.RequestMessage> value) {
            this.messages = value == null ? List.of() : List.copyOf(value);
            return this;
        }
        public Request maxTokens(int n) { this.maxTokens = n; return this; }
        public Request timeoutMillis(long ms) { this.timeoutMillis = ms; return this; }
        public Request tool(CreateMessageRequest.ToolDefinition t) {
            this.tools = List.of(t); return this;
        }
        public Request tools(List<CreateMessageRequest.ToolDefinition> ts) {
            this.tools = ts; return this;
        }
        public Request forcedToolName(String name) { this.forcedToolName = name; return this; }
        public Request thinking(CreateMessageRequest.ThinkingConfig value) {
            this.thinking = value; return this;
        }
        public Request outputConfig(CreateMessageRequest.OutputConfig value) {
            this.outputConfig = value; return this;
        }
        public Request metadata(JsonNode value) {
            this.metadata = value; return this;
        }
        public Request stopSequences(List<String> value) {
            this.stopSequences = value == null ? null : List.copyOf(value); return this;
        }
        public Request temperature(Double value) {
            this.temperature = value; return this;
        }
        public Request streaming(boolean value) {
            this.streaming = value; return this;
        }
        public Request promptCachingEnabled(boolean value) {
            this.promptCachingEnabled = value; return this;
        }
        public Request querySource(String source) {
            this.querySource = source != null ? source : "side_query";
            return this;
        }
    }
}
