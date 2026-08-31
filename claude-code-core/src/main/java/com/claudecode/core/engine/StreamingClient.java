package com.claudecode.core.engine;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.StopDetails;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Abstraction for the LLM streaming client, defined in core to avoid
 * circular dependency between core and api modules.
 * <p>
 * The {@code LlmClient} in claude-code-api implements this interface
 * (or an adapter bridges the two).
 *
 * <ul>
 *   <li>request
 *       options carried to the wire layer, including {@code skipCacheWrite},
 *       query-source retry classification, adaptive/budgeted thinking, and
 *       AbortSignal propagation; response events retain the HTTP request id
 *       separately from the provider message id.</li>
 * </ul>
 */
public interface StreamingClient {

    /** Iterator that exposes the final HTTP transport attempt's wall-clock anchor. */
    interface TimedStreamingIterator extends Iterator<StreamingEvent> {
        long lastAttemptStartMs();
    }

    /**
     * Creates a streaming message request and returns an iterator of stream events.
     *
     * @param request the message request parameters
     * @return iterator of stream events
     */
    Iterator<StreamingEvent> createStream(StreamRequest request);

    /**
     * Returns the current model name.
     */
    String getModel();

    /**
     * API provider family used by provider-gated server tools. Existing
     * adapters default to first-party Anthropic; composition roots that wrap a
     * non-Anthropic backend override this without coupling core to api classes.
     */
    default String provider() {
        return "firstParty";
    }

    /**
     * A simplified request record for the streaming client.
     * <p>
     * Phase 1 additions: {@code fallbackModel}, {@code maxOutputTokensOverride},
     * {@code taskBudget}, {@code toolChoice}, {@code onStreamingFallback}.
     * All Phase 1 fields default to {@code null} via the existing convenience constructors.
     */
    record StreamRequest(
        String model,
        int maxTokens,
        String systemPrompt,
        List<RequestMessage> messages,
        boolean stream,
        List<ToolDef> tools,
        JsonNode jsonSchema,
        /**
         * Effort level — one of {@code "low" | "medium" | "high" | "max"} or {@code null} for unset.
         */
        String effort,
        /**
         * Phase 1: optional fallback model name.
         */
        String fallbackModel,
        /**
         * Phase 1: override the API's {@code max_tokens} for this request only.
         */
        Integer maxOutputTokensOverride,
        /**
         * Phase 1: API task budget ({@code output_config.task_budget}).
         */
        Object taskBudget,
        /**
         * Phase 1: tool-choice directive ({@code "auto"}, {@code "any"}, {@code "none"}, or a specific tool
         * name).
         */
        String toolChoice,
        /**
         * Phase 1: callback invoked by the adapter when a streaming fallback is triggered.
         */
        Runnable onStreamingFallback,
        /**
         * Whether extended thinking is enabled for this request.
         */
        boolean thinkingEnabled,

        String sessionId,
        /**
         * Sub-agent id (or {@code null} for the main REPL thread).
         */
        String agentId,
        /**
         * Moves the prompt-cache message breakpoint one non-system message backward for a cache-sharing
         * fork.
         */
        boolean skipCacheWrite,
        /** Query classification used by API retry policy; not serialized. */
        String querySource,
        /** Owning query cancellation signal; not serialized. */
        AbortController abortController,
        /**
         * Optional legacy thinking budget from {@code --max-thinking-tokens}
         * or SDK {@code set_max_thinking_tokens}. Adaptive-thinking models
         * ignore it unless adaptive thinking is explicitly disabled.
         */
        Integer thinkingBudgetTokens,



        boolean fastMode,
        /** Notifies the session that a Fast Mode request must enter cooldown. */
        BiConsumer<Integer, Long> onFastModeFailure
    ) {
        /** Convenience constructor without tools. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream) {
            this(model, maxTokens, systemPrompt, messages, stream, List.of(), null, null,
                null, null, null, null, null, true, null, null, false);
        }

        /** Convenience constructor with tools but no jsonSchema. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, null, null,
                null, null, null, null, null, true, null, null, false);
        }

        /** Convenience constructor without effort (pre-effort callers). */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, null,
                null, null, null, null, null, true, null, null, false);
        }

        /** Full pre-Phase-1 constructor with effort (pre-fallback callers). */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                null, null, null, null, null, true, null, null, false);
        }

        /** Pre-agentId canonical shape — callers that don't track sub-agents. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, null, null, false);
        }

        /** Canonical shape with sessionId but no agentId — kept explicit now that
         *  the record carries a 16th component (agentId); previously this was the
         *  implicit canonical, so callers passing 15 args (tests, side queries)
         *  continue to resolve here with agentId defaulting to null. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled,
                             String sessionId) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, sessionId, null, false);
        }

        /** Pre-skipCacheWrite canonical shape retained for existing callers. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled,
                             String sessionId, String agentId) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, sessionId, agentId, false);
        }

        /** Pre-query-context canonical shape retained for existing callers. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled,
                             String sessionId, String agentId, boolean skipCacheWrite) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, sessionId, agentId, skipCacheWrite,
                null, null, null);
        }

        /** Pre-thinking-budget canonical shape retained for existing callers. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled,
                             String sessionId, String agentId, boolean skipCacheWrite,
                             String querySource, AbortController abortController) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, sessionId, agentId, skipCacheWrite,
                querySource, abortController, null);
        }

        /** Pre-Fast-Mode canonical shape retained for existing callers. */
        public StreamRequest(String model, int maxTokens, String systemPrompt,
                             List<RequestMessage> messages, boolean stream,
                             List<ToolDef> tools, JsonNode jsonSchema, String effort,
                             String fallbackModel, Integer maxOutputTokensOverride,
                             Object taskBudget, String toolChoice,
                             Runnable onStreamingFallback, boolean thinkingEnabled,
                             String sessionId, String agentId, boolean skipCacheWrite,
                             String querySource, AbortController abortController,
                             Integer thinkingBudgetTokens) {
            this(model, maxTokens, systemPrompt, messages, stream, tools, jsonSchema, effort,
                fallbackModel, maxOutputTokensOverride, taskBudget, toolChoice,
                onStreamingFallback, thinkingEnabled, sessionId, agentId, skipCacheWrite,
                querySource, abortController, thinkingBudgetTokens, false, null);
        }

        public record RequestMessage(String role, Object content) {}

        /**
         * Tool definition for the API request.
         */
        public record ToolDef(String name, String description, Object inputSchema,
                               String type, Integer maxUses,
                               List<String> allowedDomains, List<String> blockedDomains,
                               boolean deferLoading, boolean strict,
                               boolean eagerInputStreaming) {
            /** Source-compatible constructor before eager input streaming metadata. */
            public ToolDef(String name, String description, Object inputSchema,
                           String type, Integer maxUses,
                           List<String> allowedDomains, List<String> blockedDomains,
                           boolean deferLoading, boolean strict) {
                this(name, description, inputSchema, type, maxUses, allowedDomains,
                    blockedDomains, deferLoading, strict, false);
            }

            /** Convenience constructor for client-executed tools (no server-tool fields, not deferred). */
            public ToolDef(String name, String description, Object inputSchema) {
                this(name, description, inputSchema, null, null, null, null, false, false, false);
            }

            /** Convenience constructor for client-executed tools with an explicit defer-loading flag. */
            public ToolDef(String name, String description, Object inputSchema, boolean deferLoading) {
                this(name, description, inputSchema, null, null, null, null, deferLoading, false, false);
            }

            /** Source-compatible constructor before the strict marker was added. */
            public ToolDef(String name, String description, Object inputSchema,
                           String type, Integer maxUses,
                           List<String> allowedDomains, List<String> blockedDomains,
                           boolean deferLoading) {
                this(name, description, inputSchema, type, maxUses, allowedDomains,
                    blockedDomains, deferLoading, false, false);
            }

            /** Anthropic server-side tool — executed on Anthropic's infrastructure, not the client. */
            public static ToolDef serverTool(String type, String name, Integer maxUses,
                                              List<String> allowedDomains, List<String> blockedDomains) {
                return new ToolDef(name, null, null, type, maxUses, allowedDomains,
                    blockedDomains, false, false, false);
            }
        }
    }

    /**
     * Simplified stream event types used by the engine.
     */
    sealed interface StreamingEvent permits
        StreamingEvent.MessageStartEvent,
        StreamingEvent.ContentBlockStartEvent,
        StreamingEvent.ContentBlockDeltaEvent,
        StreamingEvent.ContentBlockStopEvent,
        StreamingEvent.MessageDeltaEvent,
        StreamingEvent.MessageStopEvent,
        StreamingEvent.FallbackBeganEvent,
        StreamingEvent.ApiRetryEvent,
        StreamingEvent.SystemApiErrorEvent,
        StreamingEvent.ErrorEvent {

        /** Complete decoded SSE JSON object, or {@code null} for synthetic/test events. */
        JsonNode rawEvent();

        record MessageStartEvent(
            String messageId,
            String model,
            List<ContentBlock> content,
            Usage usage,
            JsonNode rawEvent,
            String requestId
        ) implements StreamingEvent {
            public MessageStartEvent(String messageId, String model,
                                     List<ContentBlock> content, Usage usage) {
                this(messageId, model, content, usage, null, null);
            }

            public MessageStartEvent(String messageId, String model,
                                     List<ContentBlock> content, Usage usage,
                                     JsonNode rawEvent) {
                this(messageId, model, content, usage, rawEvent, null);
            }
        }

        /**
         * Fired when a new content block begins (e.g. text, tool_use, thinking,
         * server_tool_use, web_search_tool_result). {@code block} carries the
         * fully-parsed block for types that arrive complete at block-start
         * (currently just {@code web_search_tool_result} — server-executed
         * tools have no client tool_result round-trip, so there's nothing to
         * accumulate via deltas); {@code null} for the delta-accumulated types
         * (text/tool_use/thinking/server_tool_use), where {@code id}/{@code name}
         * plus subsequent {@link ContentBlockDeltaEvent}s carry the payload.
         */
        record ContentBlockStartEvent(
            int index,
            String type,
            String id,
            String name,
            ContentBlock block,
            JsonNode rawEvent
        ) implements StreamingEvent {
            /** Compat constructor for the delta-accumulated types (no complete block yet). */
            public ContentBlockStartEvent(int index, String type, String id, String name) {
                this(index, type, id, name, null, null);
            }

            /** Compat constructor for a complete block without a retained raw event. */
            public ContentBlockStartEvent(int index, String type, String id, String name,
                                          ContentBlock block) {
                this(index, type, id, name, block, null);
            }
        }

        record ContentBlockDeltaEvent(
            int index,
            String deltaType,
            String deltaText,
            JsonNode rawEvent
        ) implements StreamingEvent {
            public ContentBlockDeltaEvent(int index, String deltaType, String deltaText) {
                this(index, deltaType, deltaText, null);
            }
        }

        /** Fired when a content block is complete. */
        record ContentBlockStopEvent(
            int index,
            JsonNode rawEvent
        ) implements StreamingEvent {
            public ContentBlockStopEvent(int index) {
                this(index, null);
            }
        }

        record MessageDeltaEvent(
            String stopReason,
            String stopSequence,
            Usage usage,
            JsonNode rawEvent,
            StopDetails stopDetails
        ) implements StreamingEvent {
            public MessageDeltaEvent(String stopReason, Usage usage) {
                this(stopReason, null, usage, null, null);
            }

            /** Backward-compatible raw-event constructor. */
            public MessageDeltaEvent(String stopReason, Usage usage, JsonNode rawEvent) {
                this(stopReason, null, usage, rawEvent, null);
            }

            /** Delta for a turn that carries no refusal detail. */
            public MessageDeltaEvent(String stopReason, String stopSequence, Usage usage,
                                     JsonNode rawEvent) {
                this(stopReason, stopSequence, usage, rawEvent, null);
            }
        }

        record MessageStopEvent(JsonNode rawEvent) implements StreamingEvent {
            public MessageStopEvent() {
                this(null);
            }
        }


        record FallbackBeganEvent() implements StreamingEvent {
            @Override public JsonNode rawEvent() { return null; }
        }

        record ApiRetryEvent(int status, int attempt, int maxRetries, long retryDelayMs)
                implements StreamingEvent {
            @Override public JsonNode rawEvent() { return null; }
        }

        /** Synthetic assistant API warning produced while preserving partial output. */
        record SystemApiErrorEvent(String content, String apiError, String error)
                implements StreamingEvent {
            @Override public JsonNode rawEvent() { return null; }
        }

        record ErrorEvent(Exception exception, JsonNode rawEvent) implements StreamingEvent {
            public ErrorEvent(Exception exception) {
                this(exception, null);
            }
        }
    }
}
