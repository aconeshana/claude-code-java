package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Sealed interface for all SDK output message types that QueryEngine yields.
 */
public sealed interface SDKMessage permits
    SDKMessage.Assistant, SDKMessage.User, SDKMessage.System, SDKMessage.Notification,
    SDKMessage.Progress, SDKMessage.StreamEvent, SDKMessage.RawStreamEvent,
    SDKMessage.Attachment, SDKMessage.Tombstone, SDKMessage.CompactBoundary,
    SDKMessage.ToolUseSummary, SDKMessage.ApiRetry, SDKMessage.StreamRequestStart,
    SDKMessage.Status, SDKMessage.TaskStarted, SDKMessage.TaskProgress,
    SDKMessage.TaskUpdated, SDKMessage.TaskNotification,
    SDKMessage.Result, SDKMessage.Error, SDKMessage.Sentinel {

    /** Sentinel constant for iterator termination. */
    SDKMessage SENTINEL = new Sentinel();

    /** Internal UI signal emitted after final message-delta usage is in conversation state. */
    String ASSISTANT_USAGE_FINALIZED_EVENT = "assistant_usage_finalized";

    /**
     * An assistant message yielded from the query engine.
     */
    record Assistant(
        AssistantMessage message,
        Usage usage,
        String model,
        String parentToolUseId,
        String subagentType,
        String taskDescription
    ) implements SDKMessage {
        /** Backward-compatible constructor for synthetic/internal assistant messages. */
        public Assistant(AssistantMessage message, Usage usage) {
            this(message, usage, null, null, null, null);
        }

        /** Backward-compatible constructor for ordinary main-thread assistant messages. */
        public Assistant(AssistantMessage message, Usage usage, String model) {
            this(message, usage, model, null, null, null);
        }
    }











    record User(
        UserMessage message,
        boolean isReplay,
        String parentToolUseId,
        String subagentType,
        String taskDescription,
        boolean isSynthetic,
        boolean manualCompactSummary
    ) implements SDKMessage {
        public User(UserMessage message) {
            this(message, false, null, null, null, false, false);
        }

        public User(UserMessage message, boolean isReplay) {
            this(message, isReplay, null, null, null, false, false);
        }

        public User(UserMessage message, boolean isReplay, String parentToolUseId,
                    String subagentType, String taskDescription) {
            this(message, isReplay, parentToolUseId, subagentType, taskDescription, false, false);
        }

        public User(UserMessage message, boolean isReplay, String parentToolUseId,
                    String subagentType, String taskDescription, boolean isSynthetic) {
            this(message, isReplay, parentToolUseId, subagentType, taskDescription,
                isSynthetic, false);
        }
    }

    /**
     * A system message yielded from the query engine.
     */
    record System(SystemMessage message) implements SDKMessage {}

    /** Immediate UI notification surfaced by SDK/stream-json consumers. */
    record Notification(String key, String text, String priority) implements SDKMessage {}

    /**
     * A progress message yielded from the query engine.
     */
    record Progress(ProgressMessage message) implements SDKMessage {}

    /** Internal UI/progress event; never serialized as an SDK raw event. */
    record StreamEvent(String eventType, Object data) implements SDKMessage {}

    /**
     * Lossless Anthropic SSE event exposed by SDK {@code --include-partial-messages}.
     */
    record RawStreamEvent(JsonNode event, Long ttftMs) implements SDKMessage {}

    /**
     * An attachment message (structured_output, max_turns_reached, queued_command).
     */
    record Attachment(String attachmentType, String content, String parentUuid) implements SDKMessage {}

    /**
     * A tombstone message (deleted/replaced message marker).
     */
    record Tombstone(String replacedUuid) implements SDKMessage {}

    /**
     * A compact boundary message marking the boundary of a compaction operation.
     */
    record CompactBoundary(
        List<String> compactedMessageUuids,
        Usage preCompactUsage,
        SystemMessage boundaryMessage
    ) implements SDKMessage {
        public CompactBoundary(List<String> compactedMessageUuids, Usage preCompactUsage) {
            this(compactedMessageUuids, preCompactUsage, null);
        }
    }

    /**
     * A Haiku-generated one-line summary of a just-completed tool batch.
     */
    record ToolUseSummary(String summary, List<String> precedingToolUseIds) implements SDKMessage {}

    /**
     * An API retry system message.
     */
    record ApiRetry(
        int attempt,
        int maxRetries,
        double retryDelayMs,
        Integer errorStatus,
        String error
    ) implements SDKMessage {
        /** Backward-compatible constructor for older internal callers. */
        public ApiRetry(String reason, int retryCount) {
            this(retryCount, 10, 0, null, reason);
        }
    }

    /**
     * A stream request start marker.
     */
    record StreamRequestStart(String model, int messageCount) implements SDKMessage {}


    record Status(String status, String compactResult, String compactError) implements SDKMessage {}

    /** A background task was registered and is now visible to SDK clients. */
    record TaskStarted(
        String taskId,
        String toolUseId,
        String description,
        String taskType,
        String workflowName,
        String prompt,
        String subagentType
    ) implements SDKMessage {
        /** Backward-compatible shape used by non-Agent tasks. */
        public TaskStarted(String taskId, String toolUseId, String description,
                           String taskType, String workflowName, String prompt) {
            this(taskId, toolUseId, description, taskType, workflowName, prompt, null);
        }
    }

    /** SDK-only progress snapshot emitted when a background task performs work. */
    record TaskProgress(
        String taskId,
        String toolUseId,
        String description,
        String subagentType,
        Map<String, Object> usage,
        String lastToolName
    ) implements SDKMessage {
        public TaskProgress {
            usage = usage == null ? Map.of() : Map.copyOf(usage);
        }
    }

/**
     * A sparse background-task state patch, matching.
     */
    record TaskUpdated(String taskId, Map<String, Object> patch) implements SDKMessage {}

    /** SDK-only terminal task bookend; it is not injected into model history. */
    record TaskNotification(
        String taskId,
        String toolUseId,
        String status,
        String outputFile,
        String summary,
        Map<String, Object> usage
    ) implements SDKMessage {
        public TaskNotification(String taskId, String toolUseId, String status,
                                String outputFile, String summary) {
            this(taskId, toolUseId, status, outputFile, summary, Map.of());
        }

        public TaskNotification {
            usage = usage == null ? Map.of() : Map.copyOf(usage);
        }
    }

    /**
     * The final result of a query engine run.
     */
    record Result(
        String resultType,
        List<Message> messages,
        /** Usage accumulated inside this submitted query (all agentic API turns). */
        Usage totalUsage,
        /** Session/process-cumulative per-model usage used by SDK {@code modelUsage}. */
        Map<String, Usage> modelUsage,
        /** Session/process-cumulative per-model cost accumulated per API request. */
        Map<String, Double> modelCosts,
        String sessionId,
        double totalCost,
        List<PermissionDenial> permissionDenials,
        String fastModeState,
        /**
         * The validated payload from a successful {@code StructuredOutput} tool call, or {@code null}.
         */
        JsonNode structuredOutput,
        long durationMs,
        long durationApiMs,
        long ttftMs,
        long ttftStreamMs,
        long timeToRequestMs,
        int numTurns,
        String stopReason,
        String uuid,
        String resultText,
        boolean isError,
        List<String> errors
    ) implements SDKMessage {

        /** Result types */
        public static final String SUCCESS = "success";
        public static final String ERROR_DURING_EXECUTION = "error_during_execution";
        public static final String ERROR_MAX_TURNS = "error_max_turns";
        public static final String ERROR_MAX_BUDGET = "error_max_budget_usd";
        public static final String ERROR_MAX_STRUCTURED_OUTPUT_RETRIES = "error_max_structured_output_retries";

        /** Convenience constructor for backward compatibility. */
        public Result(String resultType, List<Message> messages, Usage totalUsage, String sessionId) {
            this(resultType, messages, totalUsage, Map.of(), Map.of(), sessionId, 0.0, List.of(), null, null,
                0L, 0L, 0L, 0L, 0L, 0, null, null, "", !SUCCESS.equals(resultType), List.of());
        }

        /** Full pre-modelCosts constructor retained for source compatibility. */
        public Result(String resultType, List<Message> messages, Usage totalUsage,
                      Map<String, Usage> modelUsage, String sessionId, double totalCost,
                      List<PermissionDenial> permissionDenials, String fastModeState,
                      JsonNode structuredOutput, long durationMs, long durationApiMs,
                      long ttftMs, long ttftStreamMs, long timeToRequestMs, int numTurns,
                      String stopReason, String uuid, String resultText, boolean isError,
                      List<String> errors) {
            this(resultType, messages, totalUsage, modelUsage, Map.of(), sessionId, totalCost,
                permissionDenials, fastModeState, structuredOutput, durationMs, durationApiMs,
                ttftMs, ttftStreamMs, timeToRequestMs, numTurns, stopReason, uuid, resultText,
                isError, errors);
        }
    }

    /**
     * Structured permission denial record (replaces List<String>).
     */
    record PermissionDenial(
        @JsonProperty("tool_name") String toolName,
        @JsonProperty("tool_use_id") String toolUseId,
        @JsonProperty("tool_input") Map<String, Object> toolInput
    ) {}

    /**
     * An error that occurred during the query engine run.
     */
    record Error(Exception exception) implements SDKMessage {}

    /**
     * Sentinel record for iterator termination — not a real message.
     */
    record Sentinel() implements SDKMessage {}

    /**
     * Factory method for creating an error SDKMessage.
     */
    static SDKMessage error(Exception e) {
        return new Error(e);
    }
}
