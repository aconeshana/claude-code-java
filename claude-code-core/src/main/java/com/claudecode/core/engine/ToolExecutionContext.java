package com.claudecode.core.engine;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.queue.MessageQueueManager;

/**
 * Context provided to tool executors during execution.
 *
 * <p>{@code fileStateCache} is session-scoped — the same instance must be
 * passed to every tool call within a session (see
 * {@link QueryEngine#getFileStateCache}), not a fresh one per call, or the
 * read-before-write check FileWrite/FileEdit rely on never sees a file as
 * "read". Callers that don't care (most tests) get a fresh, empty cache via
 * the legacy 5-arg constructor/{@code of(...)} overloads below.
 *
 * <ul>
 *   <li>records every
 *       non-allow permission decision for the SDK result; nested agents route
 *       the same decision through the parent's wrapped callback. The denial
 *       sink below is the Java execution-context port for that ownership.</li>
 *   <li>
 *       and
 *        carry
 *       {@code parentMessage.uuid} into {@code fileHistoryTrackEdit}; the
 *       historically named {@code currentUserMessageId} slot is used for that
 *       parent assistant wrapper UUID in production tool execution.</li>
 *   <li>carries the
 *       owning engine's current permission mode into {@code AgentTool}, so a
 *       child inherits the parent mode and cannot override parent
 *       {@code bypassPermissions}/{@code acceptEdits}/{@code auto}.</li>
 * </ul>
 */
public record ToolExecutionContext(
    AbortController abortController,
    String sessionId,
    String workingDirectory,
    ProgressSink progressSink,
    PermissionAskCallback permissionAskCallback,
    FileStateCache fileStateCache,
    FileHistoryManager fileHistoryManager,
    String currentUserMessageId,
    /**
     * The owning engine's command queue.
     */
    MessageQueueManager messageQueueManager,
    /**
     * The agent id of the engine executing this tool, or null for the main thread.
     */
    String agentId,

    int agentDepth,
    /**
     * Immutable maximum-depth snapshot for the ordinary sub-agent tree that
     * owns this context. Null on main/teammate roots until they launch a new
     * ordinary tree and snapshot the live user setting.
     */
    Integer subagentMaxDepthSnapshot,
    /**
     * This turn's pending nested-memory trigger paths.
     */
    Set<String> nestedMemoryAttachmentTriggers,
    /**
     * Session-scoped de-dup set of memory file paths already injected as {@code nested_memory}
     * attachments.
     */
    Set<String> loadedNestedMemoryPaths,
    /**
     * Whether the local team-memory secret-write guard is active for this tool call.
     */
    boolean teamMemoryEnabled,
    /**
     * The model ID the owning engine is currently querying with (e.g.
     */
    String currentModel,
    /**
     * The resolved {@code settings.sandbox} snapshot for this engine, or null
     * when sandboxing is not configured (the default — commands run
     * unsandboxed, matching pre-sandbox behavior). {@code BashTool} consults it
     * (via {@code SandboxManager}) to decide whether to wrap a command in a
     * native sandbox. Supplied by {@code ToolExecution} from
     * {@code QueryEngineConfig#sandboxConfigSupplier}.
     */
    SandboxConfig sandboxConfig,
    /**
     * Resolved file-read deny-rule glob patterns to exclude from GlobTool results.
     */
    List<FileReadIgnorePattern> readDenyIgnorePatterns,
    /** The current model-emitted tool_use id, used to correlate background tasks. */
    String toolUseId,
/** Coordinates the established tool.call-only duration boundary with executor wrappers. */
    ToolDurationTiming toolDurationTiming,
    /** Shared output-token target/counter for this top-level user turn. */
    TurnTokenBudget turnTokenBudget,
    /** Session cwd mutation port; disabled for legacy callers/background/sub-agent contexts. */
    WorkingDirectoryController workingDirectoryController,
    /**
     * Tool names exposed to the owning model request.
     */
    List<String> enabledTools,
    /** Records non-allow decisions into the owning query's SDK result. */
    Consumer<SDKMessage.PermissionDenial> permissionDenialSink,
    /** Snapshot of the owning engine's effective permission mode for this tool call. */
    PermissionModeKind currentPermissionMode,
    /**
     * Snapshot of the parent conversation visible at tool execution time.
     */
    List<Message> conversationMessages,
    /**
     * The parent request's rendered system prompt, captured at the tool call
     * boundary. Forked agents inherit this prompt byte-for-byte when available;
     * ordinary agents continue to build their own prompt.
     */
    String renderedSystemPrompt
) {

    public ToolExecutionContext {
        if (turnTokenBudget == null) turnTokenBudget = TurnTokenBudget.unlimited();
        if (workingDirectoryController == null) {
            workingDirectoryController = WorkingDirectoryController.NOOP;
        }
        enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
        conversationMessages = conversationMessages == null ? List.of() : List.copyOf(conversationMessages);
    }

    public static Builder builder(AbortController abortController, String sessionId) {
        return new Builder(abortController, sessionId);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static ToolExecutionContext of(AbortController abortController, String sessionId) {
        return builder(abortController, sessionId).build();
    }

    public static ToolExecutionContext of(AbortController abortController, String sessionId, ProgressSink progressSink) {
        return builder(abortController, sessionId).progressSink(progressSink).build();
    }

    public static ToolExecutionContext of(AbortController abortController, String sessionId,
                                          ProgressSink progressSink, PermissionAskCallback permissionAskCallback) {
        return builder(abortController, sessionId)
            .progressSink(progressSink)
            .permissionAskCallback(permissionAskCallback)
            .build();
    }

    /** Production-shape overload: threads the session's shared {@link FileStateCache} through. */
    public static ToolExecutionContext of(AbortController abortController, String sessionId,
                                          ProgressSink progressSink, PermissionAskCallback permissionAskCallback,
                                          FileStateCache fileStateCache) {
        return builder(abortController, sessionId)
            .progressSink(progressSink)
            .permissionAskCallback(permissionAskCallback)
            .fileStateCache(fileStateCache)
            .build();
    }

    /**
     * Full production-shape overload: additionally threads the session's
     * {@link FileHistoryManager} (may be {@code null} when {@code /rewind}
     * "Restore code" is disabled) and the transcript message id used for a
     * retroactive snapshot update (needed by
     * {@link FileHistoryManager#trackEdit(String, String)}).
     */
    public static ToolExecutionContext of(AbortController abortController, String sessionId,
                                          ProgressSink progressSink, PermissionAskCallback permissionAskCallback,
                                          FileStateCache fileStateCache, FileHistoryManager fileHistoryManager,
                                          String currentUserMessageId) {
        return builder(abortController, sessionId)
            .progressSink(progressSink)
            .permissionAskCallback(permissionAskCallback)
            .fileStateCache(fileStateCache)
            .fileHistoryManager(fileHistoryManager)
            .currentUserMessageId(currentUserMessageId)
            .build();
    }

    /**
     * Returns a copy with a different {@link PermissionAskCallback}.
     */
    public ToolExecutionContext withPermissionAskCallback(PermissionAskCallback cb) {
        return toBuilder().permissionAskCallback(cb).build();
    }

    /** Returns a copy of this context with the read/deny ignore patterns replaced. */
    public ToolExecutionContext withReadDenyIgnorePatterns(List<FileReadIgnorePattern> patterns) {
        return toBuilder().readDenyIgnorePatterns(patterns).build();
    }

    /** Returns a copy carrying a dedicated cancellation controller. */
    public ToolExecutionContext withAbortController(AbortController controller) {
        return toBuilder().abortController(controller).build();
    }

    /** Returns a copy stamped with the current model-emitted tool_use id. */
    public ToolExecutionContext withToolUseId(String id) {
        return toBuilder().toolUseId(id).build();
    }

    /** Returns a copy sharing the supplied top-level turn token target/counter. */
    public ToolExecutionContext withTurnTokenBudget(TurnTokenBudget budget) {
        return toBuilder().turnTokenBudget(budget).build();
    }

    /** Returns a copy with a session-scoped foreground-shell cwd mutation port. */
    public ToolExecutionContext withWorkingDirectoryController(WorkingDirectoryController controller) {
        return toBuilder().workingDirectoryController(controller).build();
    }

    /** Returns a copy that records non-allow decisions in the supplied owner. */
    public ToolExecutionContext withPermissionDenialSink(
            Consumer<SDKMessage.PermissionDenial> sink) {
        return toBuilder().permissionDenialSink(sink).build();
    }

    /** Returns a copy carrying the owning engine's effective permission mode. */
    public ToolExecutionContext withPermissionMode(PermissionModeKind mode) {
        return toBuilder().currentPermissionMode(mode).build();
    }

    /** Builder with safe neutral defaults for optional execution capabilities. */
    public static final class Builder {
        private AbortController abortController;
        private String sessionId;
        private String workingDirectory = System.getProperty("user.dir");
        private ProgressSink progressSink = ProgressSink.NOOP;
        private PermissionAskCallback permissionAskCallback;
        private FileStateCache fileStateCache = new FileStateCache();
        private FileHistoryManager fileHistoryManager;
        private String currentUserMessageId;
        private MessageQueueManager messageQueueManager;
        private String agentId;
        private int agentDepth;
        private Integer subagentMaxDepthSnapshot;
        private Set<String> nestedMemoryAttachmentTriggers = ConcurrentHashMap.newKeySet();
        private Set<String> loadedNestedMemoryPaths = ConcurrentHashMap.newKeySet();
        private boolean teamMemoryEnabled;
        private String currentModel;
        private SandboxConfig sandboxConfig;
        private List<FileReadIgnorePattern> readDenyIgnorePatterns = List.of();
        private String toolUseId;
        private ToolDurationTiming toolDurationTiming;
        private TurnTokenBudget turnTokenBudget = TurnTokenBudget.unlimited();
        private WorkingDirectoryController workingDirectoryController = WorkingDirectoryController.NOOP;
        private List<String> enabledTools = List.of();
        private Consumer<SDKMessage.PermissionDenial> permissionDenialSink;
        private PermissionModeKind currentPermissionMode;
        private List<Message> conversationMessages = List.of();
        private String renderedSystemPrompt;

        private Builder(AbortController abortController, String sessionId) {
            this.abortController = abortController;
            this.sessionId = sessionId;
        }

        private Builder(ToolExecutionContext source) {
            abortController = source.abortController;
            sessionId = source.sessionId;
            workingDirectory = source.workingDirectory;
            progressSink = source.progressSink;
            permissionAskCallback = source.permissionAskCallback;
            fileStateCache = source.fileStateCache;
            fileHistoryManager = source.fileHistoryManager;
            currentUserMessageId = source.currentUserMessageId;
            messageQueueManager = source.messageQueueManager;
            agentId = source.agentId;
            agentDepth = source.agentDepth;
            subagentMaxDepthSnapshot = source.subagentMaxDepthSnapshot;
            nestedMemoryAttachmentTriggers = source.nestedMemoryAttachmentTriggers;
            loadedNestedMemoryPaths = source.loadedNestedMemoryPaths;
            teamMemoryEnabled = source.teamMemoryEnabled;
            currentModel = source.currentModel;
            sandboxConfig = source.sandboxConfig;
            readDenyIgnorePatterns = source.readDenyIgnorePatterns;
            toolUseId = source.toolUseId;
            toolDurationTiming = source.toolDurationTiming;
            turnTokenBudget = source.turnTokenBudget;
            workingDirectoryController = source.workingDirectoryController;
            enabledTools = source.enabledTools;
            permissionDenialSink = source.permissionDenialSink;
            currentPermissionMode = source.currentPermissionMode;
            conversationMessages = source.conversationMessages;
            renderedSystemPrompt = source.renderedSystemPrompt;
        }

        public Builder abortController(AbortController value) { abortController = value; return this; }
        public Builder sessionId(String value) { sessionId = value; return this; }
        public Builder workingDirectory(String value) { workingDirectory = value; return this; }
        public Builder progressSink(ProgressSink value) { progressSink = value; return this; }
        public Builder permissionAskCallback(PermissionAskCallback value) { permissionAskCallback = value; return this; }
        public Builder fileStateCache(FileStateCache value) { fileStateCache = value; return this; }
        public Builder fileHistoryManager(FileHistoryManager value) { fileHistoryManager = value; return this; }
        public Builder currentUserMessageId(String value) { currentUserMessageId = value; return this; }
        public Builder messageQueueManager(MessageQueueManager value) { messageQueueManager = value; return this; }
        public Builder agentId(String value) { agentId = value; return this; }
        public Builder agentDepth(int value) { agentDepth = value; return this; }
        public Builder subagentMaxDepthSnapshot(Integer value) { subagentMaxDepthSnapshot = value; return this; }
        public Builder nestedMemoryAttachmentTriggers(Set<String> value) { nestedMemoryAttachmentTriggers = value; return this; }
        public Builder loadedNestedMemoryPaths(Set<String> value) { loadedNestedMemoryPaths = value; return this; }
        public Builder teamMemoryEnabled(boolean value) { teamMemoryEnabled = value; return this; }
        public Builder currentModel(String value) { currentModel = value; return this; }
        public Builder sandboxConfig(SandboxConfig value) { sandboxConfig = value; return this; }
        public Builder readDenyIgnorePatterns(List<FileReadIgnorePattern> value) { readDenyIgnorePatterns = value; return this; }
        public Builder toolUseId(String value) { toolUseId = value; return this; }
        public Builder toolDurationTiming(ToolDurationTiming value) { toolDurationTiming = value; return this; }
        public Builder turnTokenBudget(TurnTokenBudget value) { turnTokenBudget = value; return this; }
        public Builder workingDirectoryController(WorkingDirectoryController value) { workingDirectoryController = value; return this; }
        public Builder enabledTools(List<String> value) { enabledTools = value; return this; }
        public Builder permissionDenialSink(Consumer<SDKMessage.PermissionDenial> value) { permissionDenialSink = value; return this; }
        public Builder currentPermissionMode(PermissionModeKind value) { currentPermissionMode = value; return this; }
        public Builder conversationMessages(List<Message> value) { conversationMessages = value; return this; }
        public Builder renderedSystemPrompt(String value) { renderedSystemPrompt = value; return this; }

        public ToolExecutionContext build() {
            return new ToolExecutionContext(abortController, sessionId, workingDirectory,
                progressSink, permissionAskCallback, fileStateCache, fileHistoryManager,
                currentUserMessageId, messageQueueManager, agentId, agentDepth,
                subagentMaxDepthSnapshot,
                nestedMemoryAttachmentTriggers, loadedNestedMemoryPaths,
                teamMemoryEnabled, currentModel, sandboxConfig,
                readDenyIgnorePatterns, toolUseId, toolDurationTiming, turnTokenBudget,
                workingDirectoryController, enabledTools, permissionDenialSink,
                currentPermissionMode, conversationMessages, renderedSystemPrompt);
        }
    }

    /** Per-call timing handoff between the query runtime and concrete tool registry. */
    public static final class ToolDurationTiming {
        private final LongConsumer sink;
        private final LongSupplier nanoClock;
        private final AtomicBoolean handled = new AtomicBoolean();
        private final AtomicBoolean reported = new AtomicBoolean();

        public ToolDurationTiming(LongConsumer sink) {
            this(sink, System::nanoTime);
        }

        public ToolDurationTiming(LongConsumer sink, LongSupplier nanoClock) {
            this.sink = sink;
            this.nanoClock = nanoClock;
        }

        public void claimBoundary() {
            handled.set(true);
        }

        public long startNanos() {
            return nanoClock.getAsLong();
        }

        public void recordSince(long startNanos) {
            long elapsedNanos = Math.max(0L, nanoClock.getAsLong() - startNanos);
            recordElapsed(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        }

        public void recordElapsed(long elapsedMs) {
            handled.set(true);
            if (reported.compareAndSet(false, true)) {
                sink.accept(Math.max(0L, elapsedMs));
            }
        }

        public boolean handled() {
            return handled.get();
        }

        public boolean reported() {
            return reported.get();
        }
    }

    public void reportProgress(double progress, String message) {
        progressSink.accept(ProgressUpdate.of(progress, message));
    }

/**
     * Structured progress reporting — carries the raw tool payload.
     */
    public void reportProgress(ProgressUpdate update) {
        progressSink.accept(update);
    }


    public record ProgressUpdate(
        double progress,
        String message,
        long timestamp,
        String dataType,
        String toolUseId,
        String parentToolUseId,
        String output,
        String fullOutput,
        long totalLines,
        long totalBytes,
        double elapsedSeconds,
        long timeoutMs,
        boolean complete,
        Message agentMessage,
        String prompt,
        String agentId,
        Double progressValue,
        Double total,
        String progressMessage,
        String query,
        Long resultCount,
        String resolvedModel
    ) {
        public static ProgressUpdate.Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder(this);
        }

        /** Legacy/string-only progress (status-line text only). */
        public static ProgressUpdate of(double progress, String message) {
            return builder().progress(progress).message(message).build();
        }

        /** Structured progress carrying the raw tool payload. */
        public static ProgressUpdate of(double progress, String message,
                String dataType, String toolUseId, String parentToolUseId,
                String output, String fullOutput, long totalLines, long totalBytes,
                double elapsedSeconds, long timeoutMs, boolean complete) {
            return builder()
                .progress(progress).message(message).dataType(dataType)
                .toolUseId(toolUseId).parentToolUseId(parentToolUseId)
                .output(output).fullOutput(fullOutput).totalLines(totalLines)
                .totalBytes(totalBytes).elapsedSeconds(elapsedSeconds)
                .timeoutMs(timeoutMs).complete(complete).build();
        }


        public static ProgressUpdate agent(Message childMessage, String prompt, String agentId) {
            return agent(childMessage, prompt, agentId, null);
        }

        public static ProgressUpdate agent(
                Message childMessage, String prompt, String agentId, String resolvedModel) {
            return builder().dataType("agent_progress").agentMessage(childMessage)
                .prompt(prompt).agentId(agentId).resolvedModel(resolvedModel).build();
        }


        public static ProgressUpdate agentBackgroundHint() {
            return builder().message("Press Ctrl+B to run in background")
                .dataType("agent_background_hint").build();
        }

/** Raw MCP progress payload consumed by x. */
        public static ProgressUpdate mcp(double current, Double total,
                String progressMessage, boolean complete) {
            double ratio = total != null && total > 0
                ? Math.max(0.0, Math.min(1.0, current / total)) : 0.0;
            return builder().progress(ratio).message(progressMessage)
                .dataType("mcp_progress").complete(complete).progressValue(current)
                .total(total).progressMessage(progressMessage).build();
        }

/** Raw WebSearch progress payload consumed by x. */
        public static ProgressUpdate webSearch(String type, String query,
                Long resultCount, String message) {
            return builder().message(message).dataType(type).query(query)
                .resultCount(resultCount).build();
        }


        public ProgressUpdate withIdentity(String toolUseId, String parentToolUseId) {
            return toBuilder().toolUseId(toolUseId)
                .parentToolUseId(parentToolUseId).build();
        }

        public static final class Builder {
            private double progress;
            private String message = "";
            private long timestamp = System.currentTimeMillis();
            private String dataType;
            private String toolUseId;
            private String parentToolUseId;
            private String output;
            private String fullOutput;
            private long totalLines;
            private long totalBytes;
            private double elapsedSeconds;
            private long timeoutMs;
            private boolean complete;
            private Message agentMessage;
            private String prompt;
            private String agentId;
            private Double progressValue;
            private Double total;
            private String progressMessage;
            private String query;
            private Long resultCount;
            private String resolvedModel;

            private Builder() {}

            private Builder(ProgressUpdate source) {
                progress = source.progress;
                message = source.message;
                timestamp = source.timestamp;
                dataType = source.dataType;
                toolUseId = source.toolUseId;
                parentToolUseId = source.parentToolUseId;
                output = source.output;
                fullOutput = source.fullOutput;
                totalLines = source.totalLines;
                totalBytes = source.totalBytes;
                elapsedSeconds = source.elapsedSeconds;
                timeoutMs = source.timeoutMs;
                complete = source.complete;
                agentMessage = source.agentMessage;
                prompt = source.prompt;
                agentId = source.agentId;
                progressValue = source.progressValue;
                total = source.total;
                progressMessage = source.progressMessage;
                query = source.query;
                resultCount = source.resultCount;
                resolvedModel = source.resolvedModel;
            }

            public Builder progress(double value) { progress = value; return this; }
            public Builder message(String value) { message = value; return this; }
            public Builder timestamp(long value) { timestamp = value; return this; }
            public Builder dataType(String value) { dataType = value; return this; }
            public Builder toolUseId(String value) { toolUseId = value; return this; }
            public Builder parentToolUseId(String value) { parentToolUseId = value; return this; }
            public Builder output(String value) { output = value; return this; }
            public Builder fullOutput(String value) { fullOutput = value; return this; }
            public Builder totalLines(long value) { totalLines = value; return this; }
            public Builder totalBytes(long value) { totalBytes = value; return this; }
            public Builder elapsedSeconds(double value) { elapsedSeconds = value; return this; }
            public Builder timeoutMs(long value) { timeoutMs = value; return this; }
            public Builder complete(boolean value) { complete = value; return this; }
            public Builder agentMessage(Message value) { agentMessage = value; return this; }
            public Builder prompt(String value) { prompt = value; return this; }
            public Builder agentId(String value) { agentId = value; return this; }
            public Builder progressValue(Double value) { progressValue = value; return this; }
            public Builder total(Double value) { total = value; return this; }
            public Builder progressMessage(String value) { progressMessage = value; return this; }
            public Builder query(String value) { query = value; return this; }
            public Builder resultCount(Long value) { resultCount = value; return this; }
            public Builder resolvedModel(String value) { resolvedModel = value; return this; }

            public ProgressUpdate build() {
                return new ProgressUpdate(progress, message, timestamp, dataType,
                    toolUseId, parentToolUseId, output, fullOutput, totalLines,
                    totalBytes, elapsedSeconds, timeoutMs, complete, agentMessage,
                    prompt, agentId, progressValue, total, progressMessage, query,
                    resultCount, resolvedModel);
            }
        }
    }

    @FunctionalInterface
    public interface ProgressSink extends Consumer<ProgressUpdate> {
        ProgressSink NOOP = _ -> {};
    }
}
