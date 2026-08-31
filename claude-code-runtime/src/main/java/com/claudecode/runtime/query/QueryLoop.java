package com.claudecode.runtime.query;


import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.AbortException;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.ProcessedInput;
import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.engine.SdkEventSequencedIterator;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolCallInfo;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.message.ApiErrorMessages;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.HookAdditionalContextAttachment;
import com.claudecode.core.message.HookNonBlockingErrorAttachment;
import com.claudecode.core.message.HookSystemMessageAttachment;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.QueuedCommandAttachment;
import com.claudecode.core.message.RefusalErrorMessage;
import com.claudecode.core.message.RefusalFallbackAnnouncement;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.RefusalFallbackFeature;
import com.claudecode.core.message.RefusalFallbackPromptCopy;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.StopDetails;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.ToolUseSummaryMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.WireMessages;
import com.claudecode.core.paste.ImagePaste;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.model.ApiProviderScope;
import com.claudecode.core.model.RefusalFallbackTarget;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.metrics.SessionMetricsTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class QueryLoop implements SdkEventSequencedIterator {

    private static final Logger log = LoggerFactory.getLogger(QueryLoop.class);


    private static final int MAX_OUTPUT_TOKENS_RECOVERY_LIMIT = 3;

    /**
     * The abort reason a user who chose to rewrite a refused prompt is given.
     */
    static final String REFUSAL_FALLBACK_EDIT = AbortController.REFUSAL_FALLBACK_EDIT;

    private final BlockingQueue<SequencedOutput> queue = new LinkedBlockingQueue<>();
    private final MessageQueueManager messageQueue;
    private SequencedOutput nextMessage;
    private long lastDeliveredSdkEventSequence;
    private boolean done = false;

    private final ToolRunner toolRunner;
    /** Raw prompt when entered via {@code submitMessage(prompt, options)} (the
     *  query wrapper path); {@code null} when entered directly with a fully
     *  assembled {@link QueryParams} (the test/direct path). */
    private final Object prompt;
    private final SubmitOptions options;
    /** Usage accumulated only within this submitted query; resets for the next stdin user turn. */
    private Usage queryUsage = Usage.EMPTY;
    /** UserPromptSubmit context is produced before system/init, but committed
     * after the normal attachment pass so transcript/request order remains
     * user → initial listings → hook context, matching processUserInputBase. */
    private HookAdditionalContextAttachment pendingUserPromptHookContext;
/**
     * Image source metadata is appended after the initial attachment list.
     */
    private UserMessage pendingImageMetadataMessage;

    private AttachmentMessage pendingCommandPermissions;
    /** Augmented system prompt computed in {@link #runPreamble} (base + auto-memory
     *  mechanics); {@code null} on the direct-params path,
     *  in which case {@link #runLoop} falls back to {@code params.systemPrompt}. */
    private String effectiveSystemPrompt;
    private boolean firstRequestLatencyLogged;
    private boolean firstStreamLatencyLogged;
    /** Rows this invocation has already streamed, withdrawn on a model fallback. */
    private final TombstoneEmitter retraction = new TombstoneEmitter();

    public QueryLoop(DefaultQuerySession engine, QueryParams params) {
        this(engine, params, null, SubmitOptions.DEFAULT);
    }

    public QueryLoop(DefaultQuerySession engine, QueryParams params, Object prompt, SubmitOptions options) {
        this.toolRunner = params.deps() != null ? params.deps().toolRunner() : ToolRunner.resolve();
        this.prompt = prompt;
        this.options = options;
        this.messageQueue = engine.getMessageQueue();
        Thread.ofVirtual().name("query-loop-v2").start(() -> {
            try {
                runLoop(engine, params);
            } catch (Exception e) {
                log.error("[QUERY] Query loop failed unexpectedly [sessionId={}, model={}, "
                        + "querySource={}, agentId={}, failureType={}]",
                    engine.getSessionId(), params.model(), params.querySource(),
                    engine.getConfig().agentId(), e.getClass().getName(),
                    ErrorUtils.redactedForLogging(e));
                emit(SDKMessage.error(e));
            } finally {
                emit(SDKMessage.SENTINEL);
            }
        });
    }

    @Override
    public boolean hasNext() {
        if (done) return false;
        if (nextMessage != null) return true;
        try {
            nextMessage = queue.take();
            if (nextMessage.message() instanceof SDKMessage.Sentinel) {
                done = true;
                nextMessage = null;
                return false;
            }
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            done = true;
            return false;
        }
    }

    @Override
    public SDKMessage next() {
        if (!hasNext()) throw new NoSuchElementException();
        SequencedOutput output = nextMessage;
        nextMessage = null;
        lastDeliveredSdkEventSequence = output.sdkEventSequence();
        return output.message();
    }

    private void emit(SDKMessage msg) {
        queue.add(new SequencedOutput(msg, messageQueue.sdkEventSequence()));
    }

    /**
     * Emits a frame produced while draining tool execution, buffering the tool_result rows so a later
     * fallback can withdraw them too.
     */
    private void emitToolFrame(SDKMessage msg) {
        if (msg instanceof SDKMessage.User user) retraction.recordUser(user.message());
        emit(msg);
    }

    @Override
    public long sdkEventSequenceForLastMessage() {
        return lastDeliveredSdkEventSequence;
    }

    private record SequencedOutput(SDKMessage message, long sdkEventSequence) {}

    private void emitResult(DefaultQuerySession engine, Terminal terminal, Exception error,
                            int numTurns, String stopReason, long loopStartTime) {
        try {
            QueryHelpers.emitResult(engine, queryUsage, terminal, error, numTurns, stopReason,
                loopStartTime, null, SessionCostState.get().apiDurationMs(), this::emit);
        } finally {
            engine.clearMcpAttribution();
        }
    }

    private void emitResult(DefaultQuerySession engine, Terminal terminal,
                            int numTurns, String stopReason, long loopStartTime,
                            JsonNode structuredOutput) {
        try {
            QueryHelpers.emitResult(engine, queryUsage, terminal, null, numTurns, stopReason,
                loopStartTime, structuredOutput, SessionCostState.get().apiDurationMs(), this::emit);
        } finally {
            engine.clearMcpAttribution();
        }
    }

    /**
     * Carrier for {@link #runAutoCompactPhase}: updated state + whether a
     * compaction actually fired (used to skip the blocking-limit pre-check).
     */
    private record AutoCompactStep(QueryState state, boolean compacted) {}

    /**
     * Carrier for {@link #consumeStream}: the accumulated turn state plus {@code fallbackModel} —
     * non-null only when a {@link FallbackTriggeredError} was recovered mid-stream, signaling the
     * caller to restart the whole turn (re-resolve the model, rebuild the request) rather than treat
     * this as a completed turn.
     */
    private record StreamResult(
        List<ContentBlock> toolUseBlocks,
        List<AssistantMessage> assistantMessages,
        AssistantMessage apiErrorMessage,
        Usage turnUsage,
        boolean streamError,
        boolean abortedDuringStream,
        Exception lastError,
        String lastStopReason,
        String fallbackModel,
        StopDetails stopDetails,
        String requestId
    ) {
        /**
         * A fallback abandons the current stream attempt before it can finish as either an error result or
         * a user abort.
         */
        private static StreamResult fallback(List<ContentBlock> toolUseBlocks,
                                             List<AssistantMessage> assistantMessages,
                                             Usage turnUsage,
                                             Exception lastError,
                                             String lastStopReason,
                                             String fallbackModel) {
            return new StreamResult(toolUseBlocks, assistantMessages, null, turnUsage,
                false, false, lastError, lastStopReason, fallbackModel, null, null);
        }
    }

    /**
     * Builds this turn's dynamic attachments.
     */
    private void collectAttachments(DefaultQuerySession engine, String input, String querySource) {
        var svc = engine.getAttachmentService();
        if (svc == null) return;
        AttachmentContext ctx = AttachmentContext
            .builder(engine.getConfig().workingDirectory())
            .messages(engine.getAttachmentContextMessages())
            .input(input)
            .fileStateCache(engine.getFileStateCache())
            .fileReadDenied(engine.getConfig().fileReadDeniedPredicate())
            .loadedNestedMemoryPaths(engine.getLoadedNestedMemoryPaths())
            .nestedMemoryAttachmentTriggers(engine.getNestedMemoryAttachmentTriggers())
            .hookDispatcher(engine.getHookDispatcher())
            .agentId(engine.getConfig().agentId())
            .querySource(querySource)
            .toolNames(engine.getConfig().tools())
            .criticalSystemReminder(engine.getConfig().criticalSystemReminder())
            .activeAgents(engine.getConfig().activeAgentsSupplier().get())
            .mcpServerInstructions(engine.getConfig().mcpServerInstructionsSupplier().get())
            .previousTurnTools(engine.getPreviousTurnTools())
            .compactionOccurred(engine.hasCompactionOccurred())
            .outputStyle(engine.getConfig().outputStyleSupplier() != null
                ? engine.getConfig().outputStyleSupplier().get() : null)
            .todos(engine.getConfig().todosSupplier() != null
                ? engine.getConfig().todosSupplier().get() : null)
            .planModeExit(engine.getConfig().planModeExitSupplier() != null
                ? engine.getConfig().planModeExitSupplier().get() : null)
            .dynamicSkillDirTriggers(engine.getConfig().dynamicSkillDirTriggersSupplier() != null
                ? engine.getConfig().dynamicSkillDirTriggersSupplier().get() : Set.of())
            .skills(engine.getConfig().skillListingSupplier() != null
                ? engine.getConfig().skillListingSupplier().get() : null)
            .mcpResourceReader(engine.getConfig().mcpResourceReader())
            .usage(engine.getConfig().usageSupplier() != null
                ? engine.getConfig().usageSupplier().get() : null)
            .build();
        var attachments = svc.collect(ctx);
        for (var payload : attachments) {
// Native JSONL normally records the typed attachment event as well as rendering it into
// the request's merged user message.
            AttachmentMessage attachment =
                new AttachmentMessage(UUID.randomUUID().toString(), payload);
            engine.getMutableMessages().add(attachment);
            boolean transientSubAgentAttachment = engine.getConfig().agentId() != null
                && (payload instanceof AgentListingDeltaAttachment
                    || payload instanceof QueuedCommandAttachment queued
                        && Strings.CS.equals("agent-message", queued.mode()));
            if (!transientSubAgentAttachment) {
                QueryHelpers.recordTranscript(engine, attachment);
            }
        }
        engine.clearNestedMemoryAttachmentTriggers();
// Snapshot this turn's announced tools so next turn's deferred_tools_delta can diff against
// them.
        engine.setPreviousTurnTools(engine.getConfig().tools());
    }


    private void runLoop(DefaultQuerySession engine, QueryParams params) {
        QuerySessionSpec config = engine.getConfig();
        MessageCompactor compactService = engine.getCompactService();

        long loopStartTime = System.currentTimeMillis();
        HeadlessTurnProfiler profiler = config.headlessTurnProfiler();
        profiler.checkpoint("query_started");
        engine.resetQueryTiming();

        // Entry via submitMessage(prompt, options): run the
        // (minimal) query preamble before the turn loop.
        if (prompt != null) {
            Terminal early = runPreamble(engine, params);
            if (early != null) {
                emitResult(engine, early, null, 1, null, loopStartTime);
                profiler.finishTurn();
                return;
            }
        }

        SessionMetricsTracker sessionMetrics = engine.sessionMetricsTracker();
        String metricsTurnId = engine.getCurrentTurnMessageId();
        if (StringUtils.isBlank(metricsTurnId)) metricsTurnId = UUID.randomUUID().toString();
        sessionMetrics.beginTurn(metricsTurnId);
        try {

        // CLAUDE.md + currentDate context is computed once and prepended to
// every API call via buildRequestMessages (matches production line 390).
        String claudeMdUserContext = QueryHelpers.buildClaudeMdUserContext(engine);
        String systemPrompt = this.effectiveSystemPrompt != null
            ? this.effectiveSystemPrompt
            : (params.systemPrompt() != null ? params.systemPrompt() : "");

        QueryState state = QueryState.initial();
        if (params.maxOutputTokensOverride() != null) {
            state = state.withMaxOutputTokensOverride(params.maxOutputTokensOverride());
        }
        if (state.autoCompactTracking() == null) {
            state = state.withAutoCompactTracking(AutoCompactTrackingState.initial());
        }

        boolean hasTurnModelOverride = StringUtils.isNotBlank(options.modelOverride());
        String currentModel = hasTurnModelOverride
            ? params.model() : QueryHelpers.resolveRuntimeModel(engine);
        boolean fallbackActive = false;
        List<StreamingClient.StreamRequest.RequestMessage> fallbackRetryMessages = null;
        List<StreamingClient.StreamRequest.ToolDef> fallbackRetryToolDefs = null;
        String lastStopReason = null;


        // how many StructuredOutput calls are
        // allowed before erroring, so a model that never satisfies the Stop hook
        // cannot loop forever in JSON-schema mode.
        int maxStructuredOutputRetries = parseMaxStructuredOutputRetries();
        int initialStructuredOutputCalls = options.hasJsonSchema()
            ? MessageConstants.countToolCalls(engine.getMutableMessages(), "StructuredOutput", null)
            : 0;
        Exception lastError = null;
        int maxTurns = params.maxTurns() != null ? params.maxTurns() : config.maxTurns();
        // The maxTurns gate counts API round-trips, NOT yielded user rows.
        int loopTurns = 1;
        // Consecutive stop-hook blocking re-entries (bundle `stopHookBlockingCount`);
        // reset by every other transition, capped below.
        int stopHookBlockingCount = 0;
        boolean hasAttemptedReactiveCompact = false;

        boolean firstTurn = true;
        String turnUserContext = null;
        Long apiRetryChainStartMs = null;
        while (true) {

            if (!(state.transition() instanceof Continue.StopHookReentry)) {
                stopHookBlockingCount = 0;
            }
            for (SystemMessage pending : engine.drainNotifications()) {
                emit(new SDKMessage.System(pending));
            }

            if (!fallbackActive) {
                currentModel = hasTurnModelOverride
                    ? params.model() : QueryHelpers.resolveRuntimeModel(engine);
            }

            if (config.maxBudgetUsd() > 0) {
                double cost = engine.getCostCalculator().calculateCost(engine.getTotalUsage());
                if (cost >= config.maxBudgetUsd()) {
                    emitResult(engine, new Terminal.MaxBudget(cost), null, state.turnCount(), lastStopReason, loopStartTime);
                    return;
                }
            }

            emit(new SDKMessage.StreamRequestStart(currentModel, engine.getMessages().size()));

            if (compactService != null) {
                List<Message> currentMessages = engine.getMutableMessages();
                MessageCompactor.MicrocompactResult mcResult =
                    compactService.microcompactMessages(currentMessages, /* liveMainThread */ true);
                List<Message> compacted = mcResult.messages();
                if (compacted != currentMessages) {
                    currentMessages.clear();
                    currentMessages.addAll(compacted);
                }
            }

            AutoCompactStep acStep = runAutoCompactPhase(engine, params, state, false);
            state = acStep.state();
            boolean compactionHappened = acStep.compacted();
            if (compactionHappened) {
                engine.setCompactionOccurred(true);
            }


            // /compact when proactive compaction did not produce a smaller
            // conversation. Keep compact/session_memory fork queries exempt: they
            // need to call the model precisely in order to reduce the context.
            if (!compactionHappened
                    && compactService != null
                    && !Strings.CS.equals("compact", params.querySource())
                    && !Strings.CS.equals("session_memory", params.querySource())
                    && !(compactService.isReactiveCompactEnabled()
                        && compactService.isAutoCompactEnabled())
                    && compactService.isAtBlockingLimit(
                        engine.getMutableMessages(), config.model())) {

// when reactiveCompact.isReactiveCompactEnabled &&
// isAutoCompactEnabled so the real API call can happen and the
// reactive-compact catch path (below, and in
// runAutoCompactPhase(..., reactive=true)) gets a chance to react
// to a genuine provider 413 instead of the turn dying on a local
// estimate before the call is even made.

                // unported feature and plays no part in this condition.
                // When auto-compact is off, keep the old behavior: reserve
                // room so the user can still run /compact manually.
                AssistantMessage errorMessage =
                    MessageFactory.createAssistantAPIErrorMessage(
                        "Prompt is too long", null, "invalid_request");
                engine.getMutableMessages().add(errorMessage);
                emit(new SDKMessage.Assistant(errorMessage, engine.getTotalUsage()));
                QueryHelpers.recordTranscript(engine, errorMessage);
                QueryHelpers.dispatchStopFailure(engine);
                emitResult(engine, new Terminal.PromptTooLong(), null,
                    state.turnCount(), lastStopReason, loopStartTime);
                return;
            }

            if (firstTurn) {
                turnUserContext = claudeMdUserContext;
                if (!options.suppressInitialAttachments()) {
                    collectAttachments(engine, promptText(prompt), params.querySource());
                }
                appendPendingImageMetadata(engine);
                if (pendingUserPromptHookContext != null) {
                    AttachmentMessage hookContext = new AttachmentMessage(
                        UUID.randomUUID().toString(), pendingUserPromptHookContext);
                    engine.getMutableMessages().add(hookContext);
                    QueryHelpers.recordTranscript(engine, hookContext);
                    pendingUserPromptHookContext = null;
                }
                if (pendingCommandPermissions != null) {
                    engine.getMutableMessages().add(pendingCommandPermissions);
                    QueryHelpers.recordTranscript(engine, pendingCommandPermissions);
                    pendingCommandPermissions = null;
                }
                appendAdditionalUserMessages(engine, options);
                firstTurn = false;
            }
            sessionMetrics.beginStep();
            boolean replayingFallbackRequest = fallbackRetryMessages != null;
            ToolExecutionContext toolPromptContext = QueryHelpers.toolPromptContext(
                engine, currentModel);
            List<StreamingClient.StreamRequest.ToolDef> toolDefs = replayingFallbackRequest
                ? fallbackRetryToolDefs
                : ToolSearchGate.isEnabled(currentModel)
                    ? config.toolExecutor().getToolDefinitions(
                        ToolSearchGate.extractDiscoveredToolNames(engine.getMessages()),
                        toolPromptContext)
                    : config.toolExecutor().getToolDefinitions(toolPromptContext);
            List<StreamingClient.StreamRequest.RequestMessage> requestMessages;
            if (replayingFallbackRequest) {
                requestMessages = fallbackRetryMessages;
                fallbackRetryMessages = null;
                fallbackRetryToolDefs = null;
            } else {
                List<Message> requestConversation = applyToolResultBudget(engine, config);
                requestMessages = QueryHelpers.buildRequestMessages(
                    engine, requestConversation, turnUserContext,
                    currentModel, List.of(), toolDefs);
            }



            String effectiveModel = currentModel;
            String effortValue = StringUtils.isNotBlank(options.effortOverride())
                ? options.effortOverride()
                : (engine.getEffortOverride() != null
                    ? engine.getEffortOverride() : config.effortValue());
            String resolvedEffort = EffortHelpers.resolveAppliedEffort(
                effectiveModel, effortValue, config.isCustomModel(effectiveModel));

            Integer maxOutputTokensOverride = state.maxOutputTokensOverride();
            int effectiveRequestMaxTokens = maxOutputTokensOverride != null
                ? maxOutputTokensOverride : config.maxTokens();
            StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
                currentModel, config.maxTokens(), systemPrompt, requestMessages, true, toolDefs,
                options.hasJsonSchema() ? options.jsonSchema() : null, resolvedEffort, config.fallbackModel(),
                maxOutputTokensOverride, params.taskBudget(), null, null,
                config.isThinkingEnabled(), engine.getSessionId(), config.agentId(), params.skipCacheWrite(),
                params.querySource(), engine.getAbortController(), config.thinkingBudgetTokens(),
                config.fastModeController().isFastRequest(currentModel),
                (status, retryAfterSeconds) -> config.fastModeController().enterCooldown(
                    Duration.ofSeconds(retryAfterSeconds == null
                        ? 1_800L : Math.max(600L, retryAfterSeconds)),
                    status == 529 ? FastModeCooldownReason.OVERLOADED
                        : FastModeCooldownReason.RATE_LIMIT));

            long apiStartMs = System.currentTimeMillis();
            if (apiRetryChainStartMs == null) apiRetryChainStartMs = apiStartMs;
            profiler.checkpoint("api_request_sent");
            engine.markQueryRequestStarted();
            long requestHandshakeStartNanos = System.nanoTime();

            Iterator<StreamingClient.StreamingEvent> stream;
            try {
                stream = params.deps().callModel(request);
                if (!firstRequestLatencyLogged) {
                    firstRequestLatencyLogged = true;
                    long responseHeadersMs =
                        (System.nanoTime() - requestHandshakeStartNanos) / 1_000_000L;
                    log.debug("[QUERY_LATENCY] request_ready_ms={} response_headers_ms={} messages={} tools={}",
                        engine.getQueryTimeToRequestMs(), responseHeadersMs,
                        requestMessages.size(), toolDefs.size());
                }
            } catch (FallbackTriggeredError fte) {
                String fallback = QueryHelpers.handleFallback(engine, fte, this::emit);
                if (fallback != null) {
                    fallbackRetryMessages = List.copyOf(requestMessages);
                    fallbackRetryToolDefs = List.copyOf(toolDefs);
                    currentModel = fallback;
                    fallbackActive = true;
                    if (config.isThinkingEnabled()) {
                        List<Message> stripped =
                            MessageConstants.stripSignatureBlocks(engine.getMutableMessages());
                        engine.getMutableMessages().clear();
                        engine.getMutableMessages().addAll(stripped);
                    }
                    continue;
                }
                log.warn("[QUERY] Model request fallback failed [sessionId={}, model={}, "
                        + "querySource={}, agentId={}, failureType={}]",
                    engine.getSessionId(), currentModel, params.querySource(),
                    config.agentId(), fte.getClass().getName(),
                    ErrorUtils.redactedForLogging(fte));
                emit(SDKMessage.error(fte));
                QueryHelpers.dispatchStopFailure(engine);
                emitResult(engine, new Terminal.StreamError(fte.getMessage(), fte), fte, state.turnCount(), lastStopReason, loopStartTime);
                return;
            } catch (Exception e) {
                if (engine.getAbortController().isAborted()) {
                    int resultTurns = emitVisibleInterruption(
                        engine, state.turnCount(), false);
                    emitResult(engine, new Terminal.Aborted("aborted_streaming"), null,
                        resultTurns, lastStopReason, loopStartTime);
                    return;
                }
                if (isPromptTooLong(e) && compactService != null
                        && compactService.isReactiveCompactEnabled()) {
                    if (hasAttemptedReactiveCompact) {
                        emitAutomaticCompactionFailure(engine, state, lastStopReason,
                            loopStartTime, e);
                        return;
                    }
                    AutoCompactStep recovery = runAutoCompactPhase(engine, params, state, true);
                    state = recovery.state();
                    if (recovery.compacted()) {
                        apiRetryChainStartMs = null;
                        hasAttemptedReactiveCompact = true;
                        engine.setCompactionOccurred(true);
                        state = state.withTransition(new Continue.PromptTooLongRecovery());
                        continue;
                    }
                    emitAutomaticCompactionFailure(engine, state, lastStopReason,
                        loopStartTime, e);
                    return;
                }
                log.warn("[QUERY] Model request failed [sessionId={}, model={}, querySource={}, "
                        + "agentId={}, failureType={}]",
                    engine.getSessionId(), currentModel, params.querySource(),
                    config.agentId(), e.getClass().getName(),
                    ErrorUtils.redactedForLogging(e));
                maybeInjectTooLargeApiError(engine, e);
                emit(SDKMessage.error(e));
                QueryHelpers.dispatchStopFailure(engine);
                emitResult(engine, new Terminal.StreamError(e.getMessage(), e), e, state.turnCount(), lastStopReason, loopStartTime);
                return;
            }

            StreamResult sr = consumeStream(stream, engine, lastStopReason, lastError,
                effectiveRequestMaxTokens);
            if (sr.fallbackModel() != null) {
                fallbackRetryMessages = List.copyOf(requestMessages);
                fallbackRetryToolDefs = List.copyOf(toolDefs);
                currentModel = sr.fallbackModel();
                fallbackActive = true;
                if (config.isThinkingEnabled()) {
                    List<Message> stripped =
                        MessageConstants.stripSignatureBlocks(engine.getMutableMessages());
                    engine.getMutableMessages().clear();
                    engine.getMutableMessages().addAll(stripped);
                }
                continue;
            }
            List<ContentBlock> toolUseBlocks = sr.toolUseBlocks();
            List<AssistantMessage> assistantMessages = sr.assistantMessages();
            AssistantMessage apiErrorMessage = sr.apiErrorMessage();
            Usage turnUsage = sr.turnUsage();
            boolean streamError = sr.streamError();
            boolean abortedDuringStream = sr.abortedDuringStream();
            lastError = sr.lastError();
            lastStopReason = sr.lastStopReason();

            if (streamError) {
                if (isPromptTooLong(lastError) && compactService != null
                        && compactService.isReactiveCompactEnabled()) {
                    if (hasAttemptedReactiveCompact) {
                        emitAutomaticCompactionFailure(engine, state, lastStopReason,
                            loopStartTime, lastError);
                        return;
                    }
                    AutoCompactStep recovery = runAutoCompactPhase(engine, params, state, true);
                    state = recovery.state();
                    if (recovery.compacted()) {
                        apiRetryChainStartMs = null;
                        hasAttemptedReactiveCompact = true;
                        engine.setCompactionOccurred(true);
                        state = state.withTransition(new Continue.PromptTooLongRecovery());
                        continue;
                    }
                    emitAutomaticCompactionFailure(engine, state, lastStopReason,
                        loopStartTime, lastError);
                    return;
                }
                log.warn("[QUERY] Model stream failed [sessionId={}, model={}, querySource={}, "
                        + "agentId={}, failureType={}]",
                    engine.getSessionId(), currentModel, params.querySource(),
                    config.agentId(), lastError.getClass().getName(),
                    ErrorUtils.redactedForLogging(lastError));
                maybeInjectTooLargeApiError(engine, lastError);
                QueryHelpers.dispatchStopFailure(engine);
                emitResult(engine, new Terminal.StreamError(
                    lastError.getMessage(), lastError), lastError,
                    state.turnCount(), lastStopReason, loopStartTime);
                return;
            }

            queryUsage = queryUsage.add(turnUsage);
            engine.setTotalUsage(engine.getTotalUsage().add(turnUsage));
            engine.getTurnTokenBudget().addOutputTokens(turnUsage.outputTokens());
            long apiCompletedMs = System.currentTimeMillis();
            long finalAttemptStartMs = stream instanceof StreamingClient.TimedStreamingIterator timed
                && timed.lastAttemptStartMs() > 0L
                    ? timed.lastAttemptStartMs() : apiStartMs;
            SessionCostState.get().recordApiRequest(
                currentModel, turnUsage,
                apiCompletedMs - apiRetryChainStartMs,
                apiCompletedMs - finalAttemptStartMs);
            apiRetryChainStartMs = null;
            sessionMetrics.usage(turnUsage);
            if (!assistantMessages.isEmpty()) sessionMetrics.assistantMessage();

            AssistantMessage assistantMsg = assistantMessages.isEmpty()
                ? null : assistantMessages.getLast();

            if (abortedDuringStream || engine.getAbortController().isAborted()) {
                for (AssistantMessage message : assistantMessages) {
                    QueryHelpers.synthesizeMissingToolResults(
                        engine, message, "Interrupted by user", this::emit);
                }
                int resultTurns = emitVisibleInterruption(
                    engine, state.turnCount(), false);
                emitResult(engine, new Terminal.Aborted("aborted_streaming"), null,
                    resultTurns, lastStopReason, loopStartTime);
                return;
            }

// ── A refused turn replays itself on another model ────────────────.
            if (Strings.CS.equals("refusal", lastStopReason)) {
                boolean firstPartyLike =
                    ApiProviderScope.usesFirstPartyModelIds(config.llmClient().provider());
                RefusalFallbackTarget.Inputs targetInputs = new RefusalFallbackTarget.Inputs(
                    firstPartyLike, SubprocessEnvironment::get, config::isModelAllowed);
                String refusalTarget =
                    RefusalFallbackTarget.resolve(currentModel, targetInputs);
                if (refusalTarget == null) {
// Nothing to offer, so nothing to ask about.
                    if (config.agentId() == null) {
                        announceNoRefusalFallback(engine, params, currentModel,
                            sr.stopDetails(), sr.requestId());
                    }
                    emitRefusalError(engine, currentModel, sr.stopDetails(), sr.requestId(),
                        turnUsage, RefusalFallbackTarget.exists(currentModel, targetInputs),
                        firstPartyLike);
                } else if (RefusalFallbackFeature.enabled(SubprocessEnvironment::get)) {
                    RefusalFallbackDecision.Choice choice = refusalChoice(
                        engine, currentModel, refusalTarget,
                        sr.stopDetails() == null ? null : sr.stopDetails().category(),
                        firstPartyLike);
                    if (choice == RefusalFallbackDecision.Choice.RETRY_FALLBACK) {
                        List<Message> withdrawn = retraction.retractAll(engine, this::emit);
                        String refusedModel = currentModel;
                        currentModel = refusalTarget;
                        fallbackActive = true;
                        if (config.agentId() == null && !hasTurnModelOverride) {
                            config.activateRefusalFallback(refusalTarget);
                        }
                        if (config.isThinkingEnabled()) {
                            List<Message> stripped = MessageConstants
                                .stripSignatureBlocks(engine.getMutableMessages());
                            engine.getMutableMessages().clear();
                            engine.getMutableMessages().addAll(stripped);
                        }
                        if (config.agentId() == null) {
                            announceRefusalFallback(engine, params, refusedModel,
                                refusalTarget, sr.stopDetails(), sr.requestId(), withdrawn);
                        }
                        continue;
                    }
// The user was asked and declined the switch.
                    retraction.retractAll(engine, this::emit);
                    if (choice == RefusalFallbackDecision.Choice.EDIT_PROMPT) {
                        engine.getAbortController().abort(REFUSAL_FALLBACK_EDIT);
                    } else if (!engine.getAbortController().isAborted()) {
                        emitRefusalError(engine, currentModel, sr.stopDetails(), sr.requestId(),
                            turnUsage, RefusalFallbackTarget.exists(currentModel, targetInputs),
                            firstPartyLike);
                    }
                    if (engine.getAbortController().isAborted()) {

                        int resultTurns = emitVisibleInterruption(
                            engine, state.turnCount(), false);
                        emitResult(engine, new Terminal.Aborted("aborted_streaming"), null,
                            resultTurns, lastStopReason, loopStartTime);
                        return;
                    }
                }
// A target existed but CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK is set.
            }

            if (state.pendingToolUseSummary() != null) {
                ToolUseSummaryMessage pendingSummary = state.pendingToolUseSummary().join();
                if (pendingSummary != null) {
                    emit(new SDKMessage.ToolUseSummary(
                        pendingSummary.summary(), pendingSummary.precedingToolUseIds()));
                }
                state = state.withPendingToolUseSummary(null);
            }

            if (config.maxBudgetUsd() > 0) {
                double cost = engine.getCostCalculator().calculateCost(engine.getTotalUsage());
                if (cost >= config.maxBudgetUsd()) {
                    QueryHelpers.synthesizeMissingToolResults(engine, assistantMsg,
                        "Budget exceeded at " + FormatUtils.formatCost(cost), this::emit);
                    emitResult(engine, new Terminal.MaxBudget(cost), null, state.turnCount(), lastStopReason, loopStartTime);
                    return;
                }
            }

            boolean hasToolUse = !toolUseBlocks.isEmpty();

            if (hasToolUse) {
                int messagesBeforeTools = engine.getMutableMessages().size();
                ToolRunner.RunOutcome outcome = toolRunner.run(
                    toolUseBlocks, engine, options.hasJsonSchema(),
                    state.turnCount(), this::emitToolFrame,
                    assistantMsg != null ? assistantMsg.uuid() : null);

                if (engine.getAbortController().isAborted()) {
                    // Tool execution has already emitted one combined user

                    // for that user before it emits the visible interruption.
                    int afterToolResultTurns = state.turnCount() + 1;
                    int resultTurns = emitVisibleInterruption(
                        engine, afterToolResultTurns, true);
                    // Bundle: `let Le = se + 1; if (c && Le > c) yield max_turns_reached`
                    // — the gate is on the round-trip counter, not on num_turns.
                    if (maxTurns > 0 && loopTurns + 1 > maxTurns) {
                        emit(new SDKMessage.Attachment("max_turns_reached",
                            "Reached max turns (" + maxTurns + ")", null));
                    }
                    emitResult(engine, new Terminal.Aborted("aborted_tools"), null,
                        resultTurns, lastStopReason, loopStartTime);
                    return;
                }

                if (outcome.preventContinuation()) {
                    emitResult(engine, new Terminal.HookStopped(outcome.stopReason()), null, state.turnCount(), lastStopReason, loopStartTime);
                    return;
                }

                if (outcome.structuredOutput() != null) {
                    state = state.withStructuredOutput(outcome.structuredOutput());
                }


                // StructuredOutput calls made during this query are capped only
                // while no valid payload has been accepted. The released runtime
                // permits the final allowed attempt itself to succeed.
                if (options.hasJsonSchema()) {
                    int currentCalls = MessageConstants.countToolCalls(engine.getMutableMessages(), "StructuredOutput", null);
                    int callsThisQuery = currentCalls - initialStructuredOutputCalls;
                    if (state.structuredOutput() == null
                            && callsThisQuery >= maxStructuredOutputRetries) {
                        emitResult(engine, new Terminal.MaxStructuredOutputRetries(callsThisQuery),
                            new RuntimeException("Failed to provide valid structured output after "
                                + maxStructuredOutputRetries + " attempts"),
                            state.turnCount(), lastStopReason, loopStartTime);
                        return;
                    }
                }

                CompletableFuture<ToolUseSummaryMessage> nextPendingSummary = null;
                if (QueryHelpers.shouldFireToolUseSummary(config.toolBatchSummarizer(), config.agentId(),
                        engine.getAbortController().isAborted(), SubprocessEnvironment::get)) {
                    List<ToolCallInfo> toolCallInfo = QueryHelpers.buildToolCallInfo(toolUseBlocks, engine);
                    List<String> toolUseIds = toolUseBlocks.stream()
                        .filter(ToolUseBlock.class::isInstance)
                        .map(b -> ((ToolUseBlock) b).id())
                        .toList();
                    String lastAssistantText = QueryHelpers.extractLastAssistantText(assistantMsg);
                    boolean nonInteractive = engine.getConfig().promptRuntimeSupplier() != null
                        && engine.getConfig().promptRuntimeSupplier().get().isNonInteractiveSession();
                    nextPendingSummary = config.toolBatchSummarizer()
                        .summarizeAsync(toolCallInfo, lastAssistantText, nonInteractive)
                        .thenApply(summary -> (StringUtils.isBlank(summary))
                            ? null
                            : new ToolUseSummaryMessage(UUID.randomUUID().toString(), summary, toolUseIds))
                        .exceptionally(_ -> null);
                }
                state = state.withPendingToolUseSummary(nextPendingSummary);

                QueryHelpers.drainQueuedCommands(engine, this::emit);


                // completed tool batch. This is where Read-triggered nested
                // memories, changed files, late MCP instructions and queued
                // notifications become visible to the continuation request.
                // Delta providers see liveAttachmentHistory, so initial
                // agent/skill/MCP listings are not emitted twice.
                collectAttachments(engine, null, params.querySource());


                // message. A normal tool contributes its tool_result; Skill
                // additionally injects its synthetic body as a second user
                // message, and a multi-tool batch contributes one result per
                // call. Count the actual appended user rows instead of assuming
                // every tool batch is exactly one logical turn.
                int yieldedUserMessages = (int) engine.getMutableMessages().subList(
                    messagesBeforeTools, engine.getMutableMessages().size()).stream()
                    .filter(UserMessage.class::isInstance)
                    .count();
                int nextTurnCount = state.turnCount() + yieldedUserMessages;

                int nextLoopTurns = loopTurns + 1;
                if (maxTurns > 0 && nextLoopTurns > maxTurns) {
                    emit(new SDKMessage.Attachment("max_turns_reached",
                        "Reached max turns (" + maxTurns + ")", null));
                    emitResult(engine, new Terminal.MaxTurns(nextLoopTurns),
                        new RuntimeException(
                            "Reached maximum number of turns (" + maxTurns + ")"),
                        nextLoopTurns, lastStopReason, loopStartTime);
                    return;
                }
                loopTurns = nextLoopTurns;
                state = state.withTurnCount(nextTurnCount)
                             .withMaxOutputTokensRecoveryCount(0)
                             .withMaxOutputTokensOverride(null)
                             .withTransition(new Continue.ToolUse(toolUseBlocks.size()));
                sessionMetrics.endStep();
                continue;
            }

            if (apiErrorMessage != null) {
                String slotEnv = SubprocessEnvironment.get(
                    "CLAUDE_CODE_MAX_OUTPUT_TOKENS");
                boolean canEscalate = config.featureFlags()
                    .isEnabled(FeatureFlag.MAX_OUTPUT_TOKENS_SLOT)
                    && state.maxOutputTokensOverride() == null
                    && params.maxOutputTokensOverride() == null
                    && !config.maxTokensExplicit()
                    && (StringUtils.isBlank(slotEnv));

                if (canEscalate) {
                    state = state.withMaxOutputTokensOverride(64_000)
                        .withPendingToolUseSummary(null)
                        .withStopHookActive(null)
                        .withTransition(new Continue.MaxOutputTokensEscalate());
                    sessionMetrics.endStep();
                    continue;
                }

                if (state.maxOutputTokensRecoveryCount() < MAX_OUTPUT_TOKENS_RECOVERY_LIMIT) {
                    int attempt = state.maxOutputTokensRecoveryCount() + 1;
                    UserMessage recoveryMsg = new UserMessage(
                        UUID.randomUUID().toString(),
                        MessageContent.ofText(
                            "Output token limit hit. Resume directly — no apology, no recap of what you were doing. "
                                + "Pick up mid-thought if that is where the cut happened. Break remaining work into smaller pieces."),
                        true, false, null, MessageOrigin.USER, null, Instant.now(), null, null,
                        engine.getSessionId(), null);
                    engine.getMutableMessages().add(recoveryMsg);
                    QueryHelpers.recordTranscript(engine, recoveryMsg);
                    state = state.withMaxOutputTokensRecoveryCount(attempt)
                        .withMaxOutputTokensOverride(null)
                        .withPendingToolUseSummary(null)
                        .withStopHookActive(null)
                        .withTransition(new Continue.MaxOutputTokensRecovery(attempt));
                    sessionMetrics.endStep();
                    continue;
                }

// Recovery exhausted — surface the withheld API error once and take
// the StopFailure path.
                engine.getMutableMessages().add(apiErrorMessage);
                emit(new SDKMessage.Assistant(apiErrorMessage, turnUsage));
                QueryHelpers.recordTranscript(engine, apiErrorMessage);
                QueryHelpers.dispatchStopFailure(engine,
                    apiErrorMessage.error() != null
                        ? apiErrorMessage.error() : "max_output_tokens");
                emitResult(engine, new Terminal.Normal(), null, state.turnCount(),
                    lastStopReason, loopStartTime);
                return;
            }

// Stop hooks run on EVERY natural turn end (matches


            // enforcement flow.
            List<String> stopBlockingErrors = new ArrayList<>();
            String stopHookAdditionalContext = null;
            HookDispatcher stopHooks = engine.getHookDispatcher();
            if (stopHooks != null) {
                emit(new SDKMessage.StreamEvent("stop_hook_run_start", "Stop"));
                HookDispatcher.HookOutcome stopOutcome =
                    stopHooks.dispatchStopWithOutcome(SDKMessage.Result.SUCCESS,
                        Boolean.TRUE.equals(state.stopHookActive()));
                emit(new SDKMessage.StreamEvent("stop_hook_run_done", ""));
                persistHookMessages(engine, stopHooks.consumeHookMessages());
                stopHooks.consumeGoalTransition()
                    .ifPresent(transition -> persistGoalTransition(engine, transition));
                if (stopOutcome.preventContinuation()) {
                    emitResult(engine, new Terminal.StopHookPrevented(stopOutcome.stopReason()),
                        state.turnCount(), lastStopReason, loopStartTime, state.structuredOutput());
                    return;
                }
                if (stopOutcome.hasBlockingErrors()) {
                    stopBlockingErrors.addAll(stopOutcome.blockingErrors());
                }
                if (stopOutcome.hasAdditionalContext()) {
                    stopHookAdditionalContext = stopOutcome.additionalContext();
                }
            }

            // StructuredOutput completion enforcement — a minimal, purpose-built

// merged into the same Stop
            // dispatch rather than a general-purpose function-hook framework.
            // Blocks natural termination until a successful StructuredOutput tool

            if (options.hasJsonSchema()
                    && !MessageConstants.hasSuccessfulToolCall(engine.getMutableMessages(), "StructuredOutput")) {
                stopBlockingErrors.add("You MUST call the StructuredOutput tool to complete this request. Call this tool now.");
            }

            if (!stopBlockingErrors.isEmpty()) {

                int nextLoopTurns = loopTurns + 1;
                if (maxTurns > 0 && nextLoopTurns > maxTurns) {
                    emit(new SDKMessage.Attachment("max_turns_reached",
                        "Reached max turns (" + maxTurns + ")", null));
                    emitResult(engine, new Terminal.MaxTurns(nextLoopTurns),
                        new RuntimeException(
                            "Reached maximum number of turns (" + maxTurns + ")"),
                        nextLoopTurns, lastStopReason, loopStartTime);
                    return;
                }
                int nextBlockingCount = stopHookBlockingCount + 1;
                int blockCap = parseStopHookBlockCap();
                if (blockCap > 0 && nextBlockingCount > blockCap) {

                    emit(new SDKMessage.System(new SystemMessage(
                        UUID.randomUUID().toString(), "informational", "warning",
                        "A hook blocked the turn from ending " + nextBlockingCount
                            + " consecutive times — overriding and ending turn. "
                            + "For Stop/SubagentStop hooks, check stop_hook_active in"
                            + " the input and return success while it's true. Set"
                            + " CLAUDE_CODE_STOP_HOOK_BLOCK_CAP to raise this limit.")));
                    emitResult(engine, new Terminal.Normal(), state.turnCount(),
                        lastStopReason, loopStartTime, state.structuredOutput());
                    return;
                }


                for (String err : stopBlockingErrors) {
                    QueryHelpers.injectStopHookFeedback(engine, this::emit, "Stop hook feedback:\n" + err);
                }
                loopTurns = nextLoopTurns;
                stopHookBlockingCount = nextBlockingCount;
                state = state.withStopHookActive(true);
                state = state.withMaxOutputTokensOverride(null)
                    .withTransition(new Continue.StopHookReentry());
                sessionMetrics.endStep();
                continue; // re-enter loop; num_turns is unchanged (no yielded user turn)
            }
            if (stopHookAdditionalContext != null) {
                QueryHelpers.injectHookContext(engine, stopHookAdditionalContext);
            }


            // turn so /btw and SDK side_question can share the exact system,
            // tools, model, normalized message prefix and thinking settings.
            // Store a fully assembled request template here, while this turn's
            // CLAUDE.md context snapshot and effective prompt are still in scope.
            if (config.agentId() == null) {
                List<Message> forkConversation = applyToolResultBudget(engine, config);
                ToolExecutionContext forkToolPromptContext = QueryHelpers.toolPromptContext(
                    engine, currentModel);
                List<StreamingClient.StreamRequest.ToolDef> forkTools = ToolSearchGate.isEnabled(currentModel)
                    ? config.toolExecutor().getToolDefinitions(
                        ToolSearchGate.extractDiscoveredToolNames(engine.getMessages()),
                        forkToolPromptContext)
                    : config.toolExecutor().getToolDefinitions(forkToolPromptContext);
                List<StreamingClient.StreamRequest.RequestMessage> forkMessages =
                    QueryHelpers.buildRequestMessages(engine, forkConversation,
                        claudeMdUserContext, currentModel, List.of(), forkTools);
                String forkEffortValue = StringUtils.isNotBlank(options.effortOverride())
                    ? options.effortOverride()
                    : (engine.getEffortOverride() != null
                        ? engine.getEffortOverride() : config.effortValue());
                engine.setLastCacheSafeForkRequest(new StreamingClient.StreamRequest(
                    currentModel, config.maxTokens(), systemPrompt, forkMessages, true,
                    forkTools, null,
                    EffortHelpers.resolveAppliedEffort(
                        currentModel, forkEffortValue, config.isCustomModel(currentModel)),
                    config.fallbackModel(), null, params.taskBudget(), null, null,
                    config.isThinkingEnabled(), engine.getSessionId(), null, false,
                    "user", null, config.thinkingBudgetTokens()));
            }

            if (config.memoryExtractor() != null && config.agentId() == null) {
                config.memoryExtractor().extractAsync(engine.getMutableMessages(), engine);
            }
            // Auto-dream: background memory consolidation. Same main-thread-only
// gate as the memory extractor (config.agentId() == null) — matches

            if (config.autoDreamEngine() != null && config.agentId() == null) {
                config.autoDreamEngine().maybeRunAutoDream(engine);
            }
            break;
        }

        emitResult(engine, new Terminal.Normal(), state.turnCount(), lastStopReason,
            loopStartTime, state.structuredOutput());
        } finally {
            profiler.finishTurn();
            sessionMetrics.endTurn();
        }
    }

    /**
     * Emits the visible synthetic user interruption unless this is a soft submit-interrupt.
     */
    private int emitVisibleInterruption(DefaultQuerySession engine, int turnCount, boolean toolUse) {
        if (engine.isSoftInterruptRequested()
                || Strings.CS.equals("interrupt", engine.getAbortController().getReason())
                || Strings.CS.equals(REFUSAL_FALLBACK_EDIT,
                    engine.getAbortController().getReason())) {
            return turnCount;
        }
        QueryHelpers.emitInterruptionMessage(engine, toolUse, this::emit);
        return turnCount + 1;
    }

    /**
     * Whether a refused turn may move to {@code fallbackModel}, asking the user when.
     */
    private RefusalFallbackDecision.Choice refusalChoice(
            DefaultQuerySession engine, String refusedModel, String fallbackModel,
            String category, boolean firstPartyLike) {
        RefusalFallbackPrompt prompt = engine.getRefusalFallbackPrompt();
        RefusalFallbackDecision.Suppression suppression =
            RefusalFallbackDecision.suppression(
                new RefusalFallbackDecision.Host.Builder()
                    .mainThread(engine.getConfig().agentId() == null)
                    .dialogHostAvailable(prompt != null)
                    .switchModelsOnFlag(engine.getConfig().isSwitchModelsOnFlag())
                    .consumerLacksDialogCapability(
                        prompt != null && !prompt.consumerSupportsDialog())
                    .build());
        if (suppression != null) {
            log.debug("[REFUSAL_FALLBACK] from={} to={} category={} suppression={}",
                refusedModel, fallbackModel, category, suppression);
            return RefusalFallbackDecision.choiceWithoutDialog(suppression);
        }
        RefusalFallbackDecision.Choice choice = RefusalFallbackPrompt.askOrCancel(prompt,
            new RefusalFallbackPrompt.Request(refusedModel, fallbackModel, category,
                RefusalFallbackPromptCopy.guidance(firstPartyLike),
                WireMessages.retractedUuids(retraction.pending())));
        log.debug("[REFUSAL_FALLBACK] from={} to={} category={} choice={}",
            refusedModel, fallbackModel, category, choice);
        return choice;
    }

    /**
     * Puts the refusal error line in front of the user for a turn that cannot be replayed anywhere.
     */
    private void emitRefusalError(DefaultQuerySession engine, String refusedModel,
                                  StopDetails stopDetails, String requestId, Usage turnUsage,
                                  boolean fallbackTargetExists, boolean firstPartyLike) {
        AssistantMessage row = MessageFactory.createRefusalErrorMessage(
            RefusalErrorMessage.text(stopDetails, requestId, refusedModel, fallbackTargetExists,
                isNonInteractiveSession(engine), firstPartyLike),
            requestId, stopDetails);
        engine.getMutableMessages().add(row);
        emit(new SDKMessage.Assistant(row, turnUsage));
        QueryHelpers.recordTranscript(engine, row);
    }

    /**
     * Tells the user their turn was refused and replayed somewhere else, and records which rows that
     * cost them.
     */
    private void announceRefusalFallback(DefaultQuerySession engine, QueryParams params,
                                         String refusedModel, String fallbackModel,
                                         StopDetails stopDetails, String requestId,
                                         List<Message> withdrawn) {
        SystemMessage row = RefusalFallbackAnnouncement.row(
            refusedModel, fallbackModel, stopDetails, requestId,
            WireMessages.retractedUuids(withdrawn),
            HumanTurns.lastUnansweredHumanTurnUuid(params.messages()));
        emit(new SDKMessage.System(row));
        QueryHelpers.recordTranscript(engine, row);
    }

    /** Emits the silent main-thread diagnostic frame that precedes a refusal error. */
    private void announceNoRefusalFallback(DefaultQuerySession engine, QueryParams params,
                                           String refusedModel, StopDetails stopDetails,
                                           String requestId) {
        SystemMessage row = RefusalFallbackAnnouncement.noFallbackRow(
            refusedModel, stopDetails, requestId,
            HumanTurns.lastUnansweredHumanTurnUuid(params.messages()));
        emit(new SDKMessage.System(row));
        QueryHelpers.recordTranscript(engine, row);
    }

    /**
     * Whether this session has no user at the keyboard, which decides whether the
     * refusal line may suggest pressing escape. Engines wired without a prompt
     * runtime (tests, workers) read as interactive, matching the supplier's own
     * default.
     */
    private static boolean isNonInteractiveSession(DefaultQuerySession engine) {
        var supplier = engine.getConfig().promptRuntimeSupplier();
        if (supplier == null) return false;
        try {
            var runtime = supplier.get();
            return runtime != null && runtime.isNonInteractiveSession();
        } catch (Exception _) {
            return false;
        }
    }


    private void persistGoalTransition(DefaultQuerySession engine,
                                       HookDispatcher.GoalTransition transition) {
        GoalStatusAttachment payload = switch (transition.kind()) {
            case PENDING -> GoalStatusAttachment.pending(
                transition.condition(), transition.reason());
            case MET -> GoalStatusAttachment.achieved(
                transition.condition(), transition.reason(), transition.iterations(),
                transition.durationMs(), transition.tokens());
            case FAILED -> GoalStatusAttachment.failed(
                transition.condition(), transition.reason(), transition.iterations(),
                transition.durationMs(), transition.tokens());
        };
        AttachmentMessage message = new AttachmentMessage(
            UUID.randomUUID().toString(), payload);
        engine.appendTranscriptMessage(message);
        try {
            emit(new SDKMessage.Attachment("goal_status",
                JsonUtils.getMapper().writeValueAsString(payload), null));
        } catch (Exception _) {
            emit(new SDKMessage.Attachment("goal_status", transition.condition(), null));
        }
    }

    /** Persist diagnostics produced by hook evaluators without adding model context. */
    private void persistHookMessages(DefaultQuerySession engine, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        boolean hasNonBlockingError = false;
        for (Message message : messages) {
            engine.appendTranscriptMessage(message);
            if (!(message instanceof AttachmentMessage attachment)) continue;
            if (attachment.payload() instanceof HookNonBlockingErrorAttachment) {
                hasNonBlockingError = true;
            }
            String attachmentType = attachment.payload() instanceof HookSystemMessageAttachment
                ? "hook_system_message" : "hook_non_blocking_error";
            try {
                emit(new SDKMessage.Attachment(attachmentType,
                    JsonUtils.getMapper().writeValueAsString(attachment.payload()), null));
            } catch (Exception _) {
                emit(new SDKMessage.Attachment(attachmentType, "", null));
            }
        }
        if (hasNonBlockingError) {
            emit(new SDKMessage.Notification(
                "stop-hook-error",
                "Stop hook error occurred · ctrl+o to see",
                "immediate"));
        }
    }

    /**
     * Phase 5 — run one proactive or reactive compaction attempt.
     */
    private AutoCompactStep runAutoCompactPhase(DefaultQuerySession engine, QueryParams params,
        QueryState state, boolean reactive) {
        AutoCompactTrackingState tracking = state.autoCompactTracking();
        AutoCompactTrackingState effective = !reactive && tracking != null
            ? tracking : AutoCompactTrackingState.initial();
        if (!reactive && !effective.shouldRetry()) {
            return new AutoCompactStep(state, false);
        }

        MessageCompactor compact = engine.getCompactService();
        if (compact == null) {
            return new AutoCompactStep(state, false);
        }
        // Proactive compaction uses the local estimate. Reactive recovery is
        // entered only after the provider rejected the real serialized request,
        // so repeating the estimate here would recreate the dead session.
        if (!reactive && !compact.shouldAutoCompact(
                engine.getMutableMessages(), params.model(), params.querySource())) {
            return new AutoCompactStep(state, false);
        }
        Consumer<CompactProgressEvent> notify = engine.getOnCompactProgress();
        HookDispatcher hooks = engine.getHookDispatcher();

        List<String> compactedUuids = engine.getMutableMessages().stream().map(Message::uuid).toList();
        emit(new SDKMessage.CompactBoundary(compactedUuids, engine.getTotalUsage()));
        emit(new SDKMessage.Status("compacting", null, null));

        if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("pre_compact"));
        String hookInstructions = null;
        if (hooks != null) {
            hookInstructions = hooks.dispatchPreCompactWithOutcome(
                "auto", null, compact.estimateTokenCount(engine.getMutableMessages())).additionalContext();
        }
        if (notify != null) notify.accept(new CompactProgressEvent.CompactStart());




        long snipTokensFreed = 0;


        QueryDeps.AutoCompactResult result = reactive
            ? params.deps().reactiveCompact(
                engine.getMutableMessages(), params.model(), params.querySource(), effective,
                hookInstructions)
            : params.deps().autocompact(
                engine.getMutableMessages(), params.model(), params.querySource(), effective,
                hookInstructions, snipTokensFreed);

        if (result.compactionResult() != null) {
            MessageCompactor.CompactionResult cr = result.compactionResult();
            List<Message> post = cr.buildPostCompactMessages();
            TranscriptSink transcriptSink = engine.getTranscriptSink();
            String currentPrompt = promptText(prompt);
            if (transcriptSink != null) {
                try {
                    transcriptSink.prepareAutoCompactMetadata(engine.getSessionId(), currentPrompt);
                } catch (Throwable failure) {
                    log.warn("[TRANSCRIPT] Auto-compact metadata persistence failed "
                            + "[sessionId={}, failureType={}]",
                        engine.getSessionId(), failure.getClass().getName(),
                        ErrorUtils.redactedForLogging(failure));
                }
            }
            engine.loadCompactedMessages(post);
            state = state.withAutoCompactTracking(
                    reactive ? null : effective.withSuccess(params.deps().uuid()))
                .withMaxOutputTokensOverride(null)
                .withTransition(new Continue.Compact());

            Usage compactionUsage = cr.compactionUsage() != null
                ? cr.compactionUsage() : Usage.EMPTY;
            if (compactionUsage.inputTokens() > 0
                    || compactionUsage.outputTokens() > 0
                    || compactionUsage.cacheCreationInputTokens() > 0
                    || compactionUsage.cacheReadInputTokens() > 0) {
                engine.setTotalUsage(engine.getTotalUsage().add(compactionUsage));
            }

// Persist the compacted view to the transcript (boundary + summary + kept segment +
// attachments + hooks) so a later --resume reloads the summary instead of the full
// pre-compact history.

            // recorded immediately, so no separate flush-before-boundary is needed

            for (Message m : post) {
                QueryHelpers.recordTranscript(engine, m);
            }

            if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("session_start"));
            if (hooks != null) {
                engine.injectSystemReminder(hooks.dispatchSessionStartWithOutcome("compact").additionalContext());
            }

            if (notify != null) notify.accept(new CompactProgressEvent.HooksStart("post_compact"));
            if (hooks != null) {
                Usage cu = cr.compactionUsage();
                long postTokens = (cu != null && (cu.inputTokens() > 0 || cu.outputTokens() > 0))
                    ? TokenEstimator.contextTokens(cu, params.model())
                    : compact.estimateTokenCount(post);
                hooks.dispatchPostCompact("auto", cr.summaryText(), postTokens);
            }

            Runnable postCompact = engine.getPostCompactCallback();
            if (postCompact != null) {
                try { postCompact.run(); }
                catch (Exception failure) {
                    log.warn("[COMPACT] Post-compact callback failed "
                            + "[sessionId={}, failureType={}]",
                        engine.getSessionId(), failure.getClass().getName(),
                        ErrorUtils.redactedForLogging(failure));
                }
            }
            if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
            emit(new SDKMessage.Status(null, "success", null));
            emit(new SDKMessage.CompactBoundary(
                compactedUuids, engine.getTotalUsage(), cr.boundaryMarker()));
            for (Message summaryMessage : cr.summaryMessages()) {
                if (summaryMessage instanceof UserMessage summaryUser) {
                    emit(new SDKMessage.User(summaryUser, false, null, null, null, true));
                }
            }

            state = state.withTurnCount(state.turnCount() + 1);
            return new AutoCompactStep(state, true);
        } else if (result.consecutiveFailures() != null) {
            Usage compactionUsage = result.compactionUsage() != null
                ? result.compactionUsage() : Usage.EMPTY;
            if (compactionUsage.inputTokens() > 0
                    || compactionUsage.outputTokens() > 0
                    || compactionUsage.cacheCreationInputTokens() > 0
                    || compactionUsage.cacheReadInputTokens() > 0) {
                engine.setTotalUsage(engine.getTotalUsage().add(compactionUsage));
            }
            if (!reactive) {
                state = state.withAutoCompactTracking(
                    effective.withFailure(result.consecutiveFailures()));
            }
            if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
            emit(new SDKMessage.Status(null, "failed", result.compactError()));
            return new AutoCompactStep(state, false);
        }
        if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
        emit(new SDKMessage.Status(null, null, null));
        return new AutoCompactStep(state, false);
    }


    private StreamResult consumeStream(Iterator<StreamingClient.StreamingEvent> stream,
                                        DefaultQuerySession engine, String lastStopReason,
                                        Exception lastError, int effectiveMaxTokens) {
        String messageId = null;
        String responseModel = null;
        String requestId = null;
        List<ContentBlock> toolUseBlocks = new ArrayList<>();
        List<AssistantMessage> assistantMessages = new ArrayList<>();
        AssistantMessage apiErrorMessage = null;
        Usage turnUsage = Usage.EMPTY;
        boolean streamError = false;
        boolean abortedDuringStream = false;
        StopDetails stopDetails = null;
        Map<Integer, QueryHelpers.BlockBuilder> inProgressBlocks = new HashMap<>();

        try {
            while (stream.hasNext()) {
                if (engine.getAbortController().isAborted()) {
                    abortedDuringStream = true;
                    break;
                }
                StreamingClient.StreamingEvent event = stream.next();
                engine.getConfig().headlessTurnProfiler().checkpoint("first_chunk");
                engine.markQueryStreamEvent();
                if (!firstStreamLatencyLogged) {
                    firstStreamLatencyLogged = true;
                    long requestReadyMs = engine.getQueryTimeToRequestMs();
                    long streamMs = engine.getQueryTtftStreamMs();
                    log.debug("[QUERY_LATENCY] first_sse_ms={} post_request_wait_ms={}",
                        streamMs, Math.max(0L, streamMs - requestReadyMs));
                }
                switch (event) {
                    case StreamingClient.StreamingEvent.MessageStartEvent mse -> {
                        messageId = mse.messageId();
                        responseModel = mse.model();
                        requestId = mse.requestId();
                        turnUsage = turnUsage.updateCumulative(mse.usage());
                    }
                    case StreamingClient.StreamingEvent.ContentBlockStartEvent cbs -> {
                        engine.markQueryOutput();
                        if (Strings.CS.equals("tool_use", cbs.type())
                                && StringUtils.isNotBlank(cbs.name())) {
                            engine.sessionMetricsTracker().firstToken();
                        }
                        inProgressBlocks.put(cbs.index(),
                            new QueryHelpers.BlockBuilder(
                                cbs.type(), cbs.id(), cbs.name(), cbs.block()));
                        if (Strings.CS.equals("tool_use", cbs.type())) {
                            emit(new SDKMessage.StreamEvent("tool_streaming_start",
                                cbs.name() + "|" + cbs.id() + "|"
                                    + (messageId == null ? "" : messageId)));
                        }
                    }
                    case StreamingClient.StreamingEvent.ContentBlockDeltaEvent cbd -> {
                        engine.markQueryOutput();
                        if (cbd.deltaText() == null) break;
                        if (!cbd.deltaText().isEmpty()
                                && (Strings.CS.equals("text_delta", cbd.deltaType())
                                    || Strings.CS.equals("thinking_delta", cbd.deltaType())
                                    || Strings.CS.equals("input_json_delta", cbd.deltaType()))) {
                            engine.sessionMetricsTracker().firstToken();
                        }
                        QueryHelpers.BlockBuilder builder = inProgressBlocks.computeIfAbsent(cbd.index(),
                            _ -> new QueryHelpers.BlockBuilder(QueryHelpers.BlockBuilder.typeForDelta(cbd.deltaType()), null, null));
                        switch (cbd.deltaType()) {
                            case "text_delta" -> {
                                builder.text.append(cbd.deltaText());
                                emit(new SDKMessage.StreamEvent("content_block_delta", cbd.deltaText()));
                            }
                            case "thinking_delta" -> {
                                builder.thinking.append(cbd.deltaText());
                                emit(new SDKMessage.StreamEvent("thinking_delta", cbd.deltaText()));
                            }
                            case "signature_delta" -> builder.signature.append(cbd.deltaText());
                            case "input_json_delta" -> builder.inputJson.append(cbd.deltaText());
                            default -> { }
                        }
                    }
                    case StreamingClient.StreamingEvent.ContentBlockStopEvent cbs -> {
                        QueryHelpers.BlockBuilder builder = inProgressBlocks.remove(cbs.index());
                        if (builder != null) {
                            ContentBlock built = builder.build();
                            if (built != null) {
                                built = backfillObservableToolInput(built);
                                assistantMessages.add(emitAssistantBlock(
                                    engine, messageId, responseModel, requestId, built, turnUsage));
                                if (built instanceof ToolUseBlock tub) {
                                    toolUseBlocks.add(tub);
                                    String fullInput = tub.input() != null ? tub.input().toString() : "{}";
                                    emit(new SDKMessage.StreamEvent("tool_streaming_done",
                                        tub.name() + "|" + tub.id() + "|" + fullInput));
                                }
                            }
                        }
                    }
                    case StreamingClient.StreamingEvent.MessageDeltaEvent mde -> {
                        if (mde.usage() != null) {
                            turnUsage = turnUsage.updateCumulative(mde.usage());
                        }
                        updateLastAssistantEnvelope(engine, assistantMessages, turnUsage,
                            mde.stopReason(), mde.stopSequence(), mde.stopDetails());
                        if (!assistantMessages.isEmpty()) {
                            emit(new SDKMessage.StreamEvent(
                                SDKMessage.ASSISTANT_USAGE_FINALIZED_EVENT,
                                assistantMessages.getLast().uuid()));
                        }
                        if (mde.stopDetails() != null) {
                            stopDetails = mde.stopDetails();
                        }
                        if (mde.stopReason() != null) {
                            lastStopReason = mde.stopReason();
                            if (apiErrorMessage == null
                                    && (Strings.CS.equals("max_tokens", mde.stopReason())
                                        || Strings.CS.equals("model_context_window_exceeded", mde.stopReason()))) {
                                String content = Strings.CS.equals("model_context_window_exceeded", mde.stopReason())
                                    ? "API Error: The model has reached its context window limit."
                                    : "API Error: Claude's response exceeded " + effectiveMaxTokens
                                        + " output token maximum. To configure this behavior, set the "
                                        + "CLAUDE_CODE_MAX_OUTPUT_TOKENS environment variable.";
                                apiErrorMessage = MessageFactory.createAssistantAPIErrorMessage(
                                    content, "max_output_tokens", "max_output_tokens");
                            }
                        }
                    }
                    case StreamingClient.StreamingEvent.MessageStopEvent _ ->
                        emit(new SDKMessage.StreamEvent("message_stop", ""));
                    case StreamingClient.StreamingEvent.FallbackBeganEvent _ ->
                        emit(new SDKMessage.StreamEvent("streaming_fallback_began", ""));
                    case StreamingClient.StreamingEvent.ApiRetryEvent retry ->
                        emit(new SDKMessage.ApiRetry(retry.attempt(), retry.maxRetries(),
                            retry.retryDelayMs(), retry.status() > 0 ? retry.status() : null,
                            retry.status() == 529 ? "overloaded" : "api_error"));
                    case StreamingClient.StreamingEvent.SystemApiErrorEvent warning ->
                        apiErrorMessage = MessageFactory.createAssistantAPIErrorMessage(
                            warning.content(), warning.apiError(), warning.error());
                    case StreamingClient.StreamingEvent.ErrorEvent ee -> {
                        if (engine.getAbortController().isAborted()) {
// OkHttp cancellation is delivered as an
// ErrorEvent even though the semantic cause
// is the already-recorded user interrupt.
                            abortedDuringStream = true;
                        } else {
                            lastError = ee.exception();
                            emit(SDKMessage.error(ee.exception()));
                            streamError = true;
                        }
                    }
                }
                emitRawStreamEvent(event, engine);
                if (streamError || abortedDuringStream) break;
            }
        } catch (FallbackTriggeredError fte) {
            String fallback = QueryHelpers.handleFallback(engine, fte, this::emit);
            if (fallback != null) {
                // An overload can be reported while opening/reading the stream,
                // before the model produced an assistant row. In that case the
                // buffered users are the original prompt attachments and must be
                // replayed on the fallback model, not tombstoned as output.
                if (retraction.hasAssistantRows()) {
                    retraction.retractAll(engine, this::emit);
                }
                return StreamResult.fallback(toolUseBlocks, List.of(), turnUsage,
                    lastError, lastStopReason, fallback);
            }
            lastError = fte;
            emit(SDKMessage.error(fte));
            streamError = true;
        } catch (AbortException _) {
            abortedDuringStream = true;
        } catch (Exception e) {
            lastError = e;
            emit(SDKMessage.error(e));
            streamError = true;
        }

        if (!inProgressBlocks.isEmpty()) {
            List<Map.Entry<Integer, QueryHelpers.BlockBuilder>> pending =
                new ArrayList<>(inProgressBlocks.entrySet());
            pending.sort(Map.Entry.comparingByKey());
            for (Map.Entry<Integer, QueryHelpers.BlockBuilder> entry : pending) {
                QueryHelpers.BlockBuilder builder = entry.getValue();
                if (abortedDuringStream && !Strings.CS.equals("text", builder.type)) continue;
                ContentBlock built = builder.build();
                if (built != null) {
                    built = backfillObservableToolInput(built);
                    assistantMessages.add(emitAssistantBlock(
                        engine, messageId, responseModel, requestId, built, turnUsage));
                    if (!abortedDuringStream && built instanceof ToolUseBlock tub) {
                        toolUseBlocks.add(tub);
                    }
                }
            }
            inProgressBlocks.clear();
        }

        if (toolUseBlocks.stream().anyMatch(block ->
                block instanceof ToolUseBlock toolUse
                    && (Strings.CS.equals("EnterPlanMode", toolUse.name())
                        || Strings.CS.equals("ExitPlanMode", toolUse.name())))) {
            Consumer<String> initializer = engine.getConfig().planSlugInitializer();
            if (initializer != null) initializer.accept(engine.getSessionId());
        }
        for (AssistantMessage message : assistantMessages) {
            QueryHelpers.recordTranscript(engine, message);
        }

        return new StreamResult(toolUseBlocks, assistantMessages, apiErrorMessage, turnUsage,
            streamError, abortedDuringStream,
            lastError, lastStopReason, null, stopDetails, requestId);
    }

/** matches SendMessageTool.backfillObservableInput before SDK/transcript emission. */
    private static ContentBlock backfillObservableToolInput(ContentBlock block) {
        if (!(block instanceof ToolUseBlock toolUse)
                || !Strings.CS.equals("SendMessage", toolUse.name())
                || !(toolUse.input() instanceof ObjectNode input)
                || input.has("type") || !input.path("to").isTextual()
                || !input.path("message").isTextual()) {
            return block;
        }
        input.put("type", "message");
        input.put("recipient", input.path("to").asText());
        input.put("content", input.path("message").asText());
        return toolUse;
    }

    /**
     * matches 's {@code content_block_stop}
     * branch: one assistant message is yielded per completed block before the
     * corresponding raw stop event is forwarded.
     */
    private AssistantMessage emitAssistantBlock(DefaultQuerySession engine, String messageId,
                                                String responseModel, String requestId,
                                                ContentBlock block, Usage usage) {
        AssistantMessage message = new AssistantMessage(
            UUID.randomUUID().toString(),
            AssistantContent.apiResponse(
                messageId, List.of(block), usage, responseModel, null, null),
            false, null, Instant.now(),
            engine.getAttributionSkill(), engine.getAttributionPlugin(),
            engine.getAttributionMcpServer(), engine.getAttributionMcpTool(),
            null, null, null, requestId, null, null);
        engine.getMutableMessages().add(message);
        retraction.recordAssistant(message);
        if (MessageConstants.isNotEmptyMessage(message)) {
            emit(new SDKMessage.Assistant(message, usage, responseModel));
        }
        return message;
    }

    /** Apply final message-delta fields to the last yielded per-block response. */
    private static void updateLastAssistantEnvelope(DefaultQuerySession engine,
                                                    List<AssistantMessage> messages,
                                                    Usage usage,
                                                    String stopReason,
                                                    String stopSequence,
                                                    StopDetails stopDetails) {
        if (messages.isEmpty()) return;
        AssistantMessage previous = messages.getLast();
        AssistantContent content = previous.message();
        AssistantMessage updated = new AssistantMessage(
            previous.uuid(),
            content.withFinalDelta(usage, stopReason, stopSequence, stopDetails),
            previous.isApiErrorMessage(), previous.parentUuidValue(), previous.timestampValue(),
            previous.attributionSkill(), previous.attributionPlugin(),
            previous.attributionMcpServer(), previous.attributionMcpTool(),
            previous.apiError(), previous.error(), previous.isVirtual(),
            previous.requestId(), previous.advisorModel(), previous.isMeta());
        messages.set(messages.size() - 1, updated);
        List<Message> mutable = engine.getMutableMessages();
        for (int i = mutable.size() - 1; i >= 0; i--) {
            if (mutable.get(i) instanceof AssistantMessage assistant
                    && assistant.uuid().equals(previous.uuid())) {
                mutable.set(i, updated);
                break;
            }
        }
    }

    /** Forward only lossless API events; internal UI StreamEvent signals stay separate. */
    private void emitRawStreamEvent(StreamingClient.StreamingEvent event,
                                    DefaultQuerySession engine) {
        if (event.rawEvent() == null) return;
        Long ttftMs = null;
        if (event instanceof StreamingClient.StreamingEvent.MessageStartEvent) {
            ttftMs = Math.max(0L,
                engine.getQueryTtftStreamMs() - engine.getQueryTimeToRequestMs());
        }
        emit(new SDKMessage.RawStreamEvent(event.rawEvent(), ttftMs));
    }

    /** Text projection used for hooks/attachment discovery; wire content stays structured. */
    private static String promptText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof MessageContent(String text, List<ContentBlock> blocks)) {
            if (text != null) return text;
            if (blocks == null) return "";
            return blocks.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static List<ContentBlock> contentBlocks(MessageContent content) {
        if (content == null) return List.of();
        if (content.blocks() != null) return content.blocks();
        if (content.text() != null) return List.of(new TextBlock(content.text()));
        return List.of();
    }

    private record PreparedInputImage(ContentBlock block, ImageResizer.PastedDims dimensions) {}


    private static PreparedInputImage prepareInputImage(ImageBlock imageBlock) {
        JsonNode source = imageBlock.source();
        if (source == null || !source.isObject()
                || !Strings.CS.equals("base64", source.path("type").asText())) {
            return new PreparedInputImage(imageBlock, null);
        }

        String data = source.path("data").asText("");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException _) {
            try {
                decoded = Base64.getMimeDecoder().decode(data);
            } catch (IllegalArgumentException _) {
                return degradedInputImage(ImageResizer.IMAGE_PROCESSING_UNAVAILABLE);
            }
        }

        String mediaType = source.path("media_type").asText("image/png");
        String extension = Strings.CS.startsWith(mediaType, "image/")
            ? mediaType.substring("image/".length()) : mediaType;
        try {
            ImageResizer.ResizeResult resized =
                ImageResizer.maybeResizeForInputBlock(decoded, extension);
            JsonNode normalizedSource = ImagePaste.toImageSource(
                new ImagePaste.ImageWithDimensions(
                    Base64.getEncoder().encodeToString(resized.buffer()),
                    resized.mediaType(), null),
                JsonUtils.getMapper());
            return new PreparedInputImage(new ImageBlock(normalizedSource), resized.dimensions());
        } catch (IllegalArgumentException error) {
            return degradedInputImage(error.getMessage());
        }
    }

    private static PreparedInputImage degradedInputImage(String message) {
        return new PreparedInputImage(
            new TextBlock("[Image could not be processed: " + message + "]"), null);
    }

    /**
     * Query preamble for the {@code submitMessage(prompt, options)} entry point — runs only when this
     * loop was constructed with a raw prompt.
     */
    private Terminal runPreamble(DefaultQuerySession engine, QueryParams params) {
        QuerySessionSpec config = engine.getConfig();
        MessageContent structuredPrompt = prompt instanceof MessageContent content ? content : null;
        String promptText = promptText(prompt);
        ProcessedInput processed = structuredPrompt == null
            ? engine.processUserInput(promptText)
            : ProcessedInput.forQuery(promptText);

        String querySource = options.querySource() != null ? options.querySource() : "user";

        // ---- Pasted-image → model wiring ----

        // Pasted image chips become inline base64 ImageBlocks so the model actually
        // receives the picture; ImageStore.storeImagesAsync() also persists each to disk so
        // the model can later reference the saved file path; image metadata (dimensions
        // + source path) rides along as an isMeta user message (addImageMetadataMessage).
        List<Integer> imagePasteIds = new ArrayList<>();
        List<ContentBlock> imageContentBlocks = new ArrayList<>();
        List<String> imageMetadataTexts = new ArrayList<>();
        List<ContentBlock> structuredBlocks = new ArrayList<>();
        for (ContentBlock block : contentBlocks(structuredPrompt)) {
            if (block instanceof ImageBlock imageBlock) {
                PreparedInputImage prepared = prepareInputImage(imageBlock);
                structuredBlocks.add(prepared.block());
                String metadata = ImageResizer.createImageMetadataText(prepared.dimensions(), null);
                if (metadata != null) imageMetadataTexts.add(metadata);
            } else {
                structuredBlocks.add(block);
            }
        }
        if (options.hasPastedContents()) {
            Map<Integer, PastedContent> pasted = options.pastedContents();

            // map so each image's sourcePath can be resolved from it (below).
            Map<Integer, String> storedImagePaths =
                ImageStore.storeImagesAsync(pasted, engine.getSessionId());
            for (PastedContent pc : pasted.values()) {
                if (!PastedRefParser.isValidImagePaste(pc)) continue;
                imagePasteIds.add(pc.id());
                byte[] originalImage = Base64.getDecoder().decode(pc.content());
                String imageExt = pc.mediaType() != null && Strings.CS.startsWith(pc.mediaType(), "image/")
                    ? pc.mediaType().substring("image/".length()) : "png";
                ImageResizer.ResizeResult resized =
                    ImageResizer.maybeResizeForApiBlock(originalImage, imageExt);
                String resizedBase64 = Base64.getEncoder().encodeToString(resized.buffer());
                JsonNode imageSource = ImagePaste.toImageSource(
                    new ImagePaste.ImageWithDimensions(resizedBase64, resized.mediaType(), null),
                    JsonUtils.getMapper());
                imageContentBlocks.add(new ImageBlock(imageSource));
                String sourcePath = pc.sourcePath() != null
                    ? pc.sourcePath()
                    : storedImagePaths.get(pc.id());
                String meta = buildImageMetadataText(resized.dimensions(), pc.dimensions(), sourcePath);
                if (meta != null) imageMetadataTexts.add(meta);
            }
        }

        boolean isSlash = options.isSlashCommand();
        // Text block value: a slash/prompt/skill re-submission uses the raw command


        // processTextPrompt (stdin trailing newlines are wire-significant).
        String promptBody = isSlash ? promptText : processed.processedPrompt();

        MessageContent content;
        if (!imageContentBlocks.isEmpty() || structuredPrompt != null) {
            List<ContentBlock> blocks = new ArrayList<>();
            if (isSlash) {

                // — pasted images FIRST, then the prompt text (#1).
                blocks.addAll(imageContentBlocks);
                if (structuredPrompt != null) blocks.addAll(structuredBlocks);
                else if (promptBody != null && !promptBody.trim().isEmpty()) blocks.add(new TextBlock(promptBody));
            } else {

                if (structuredPrompt != null) blocks.addAll(structuredBlocks);
                else if (promptBody != null && !promptBody.trim().isEmpty()) blocks.add(new TextBlock(promptBody));
                blocks.addAll(imageContentBlocks);
            }
            content = MessageContent.ofBlocks(blocks);
        } else if (promptBody != null && !promptBody.trim().isEmpty()) {
            content = MessageContent.ofText(promptBody);
        } else {
            // Symmetric with the two branches above (both guard blank promptBody
            // before building a TextBlock): a blank/empty promptBody here would
            // otherwise reach the wire as a text content block with text:"", which
            // strict downstream backends reject with "message content cannot be
            // empty". The UI-edge queued-command drain
            // (LanternaReplScreen.executeQueuedCommands) already guards against
            // submitting this in the first place; this is defense-in-depth for
            // any other caller of runPreamble.
            content = MessageContent.ofText(MessageConstants.NO_CONTENT_MESSAGE);
        }

        if (Strings.CS.equals("plan", options.permissionMode())
                && config.planSlugInitializer() != null) {
            config.planSlugInitializer().accept(engine.getSessionId());
        }

        if (isSlash) {
            for (MessageContent precedingContent : options.precedingUserMessages()) {
                UserMessage preceding = new UserMessage(
                    UUID.randomUUID().toString(),
                    precedingContent,
                    false, false, null, MessageOrigin.USER, null, Instant.now(),
                    null, null, engine.getSessionId(), null);
                engine.getMutableMessages().add(preceding);
                emit(new SDKMessage.User(preceding, true));
                QueryHelpers.recordTranscript(engine, preceding);
            }
        }




        String userUuid = StringUtils.isNotBlank(options.promptUuid())
            ? options.promptUuid() : UUID.randomUUID().toString();
        Instant userTimestamp = options.promptTimestamp() != null
            ? options.promptTimestamp() : Instant.now();
        boolean visibleLocalJsxResult = isSlash && isLocalCommandOutput(content);
        UserMessage userMsg = new UserMessage(
            userUuid,
            content,
            (isSlash && !visibleLocalJsxResult) || options.isMeta(), false, null,
            Strings.CS.equals("task-notification", options.querySource())
                ? MessageOrigin.TASK_NOTIFICATION
                : Strings.CS.equals("auto-continuation", options.querySource())
                    ? MessageOrigin.AUTO_CONTINUATION : MessageOrigin.USER,
            null, userTimestamp,
            imagePasteIds.isEmpty() ? null : List.copyOf(imagePasteIds),
            isSlash ? null : options.permissionMode(), engine.getSessionId(), null, null,
            null, null, null, options.planContent());
        engine.getMutableMessages().add(userMsg);
        engine.setCurrentTurnMessageId(userMsg.uuid());
        FileHistoryManager fhm = engine.getFileHistoryManager();
        if (fhm != null) fhm.scheduleSnapshot(userMsg.uuid());
        emit(new SDKMessage.User(userMsg, true));
        QueryHelpers.recordTranscript(engine, userMsg);


        // addImageMetadataMessage appends this isMeta row. Keep it pending until
        // the first-turn attachment pass so transcript parentage is identical.
        if (!isSlash && !imageMetadataTexts.isEmpty()) {
            List<ContentBlock> metaBlocks = new ArrayList<>(imageMetadataTexts.size());
            for (String t : imageMetadataTexts) metaBlocks.add(new TextBlock(t));
            pendingImageMetadataMessage = new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofBlocks(metaBlocks),
                true, false, null, MessageOrigin.USER, null, Instant.now(),
                null, null, engine.getSessionId(), null);
        }

        if (isSlash && options.commandPermissions() != null) {
            pendingCommandPermissions = new AttachmentMessage(
                UUID.randomUUID().toString(), options.commandPermissions());
        }

        if (!processed.shouldQuery()) {
            emitPreambleInit(engine, config, querySource);
            String cmdResult = processed.localCommandResult().orElse("");
            SystemMessage localCmdMsg = MessageFactory.createCommandInputMessage(cmdResult);

// converts them to a user turn so the model can reference previous command output in
// later turns — must land in history, not just the SDK/UI stream.
            engine.getMutableMessages().add(localCmdMsg);
            emit(new SDKMessage.System(localCmdMsg));
            QueryHelpers.recordTranscript(engine, localCmdMsg);
            return new Terminal.Normal();
        }


        // the original prompt (a system warning replaces it, nothing reaches the
        // model), continue:false ends the turn while keeping the prompt in
        // context, and additionalContext is injected (truncated) as a
        // <system-reminder>.
        HookDispatcher hookDispatcher = engine.getHookDispatcher();
        if (hookDispatcher != null) {
            HookDispatcher.HookOutcome promptOutcome =
                hookDispatcher.dispatchUserPromptSubmitWithOutcome(processed.processedPrompt());
            if (promptOutcome.hasBlockingErrors()) {
                engine.getMutableMessages().remove(userMsg);
                boolean suppressOriginal = promptOutcome.specificOutput("UserPromptSubmit")
                    .map(output -> output.path("suppressOriginalPrompt").asBoolean(false))
                    .orElse(false);
                SystemMessage blockedMsg = new SystemMessage(
                    UUID.randomUUID().toString(), "user_prompt_submit_blocked", "warning",
                    "UserPromptSubmit operation blocked by hook:\n"
                        + promptOutcome.blockingErrors().getFirst()
                        + (suppressOriginal ? ""
                            : "\n\nOriginal prompt: " + processed.processedPrompt()));
                emitPreambleInit(engine, config, querySource);
                emit(new SDKMessage.System(blockedMsg));
                return new Terminal.Normal();
            }
            if (promptOutcome.preventContinuation()) {
                String notice = StringUtils.isNotBlank(promptOutcome.stopReason())
                    ? "Operation stopped by hook: " + promptOutcome.stopReason()
                    : "Operation stopped by hook";
                UserMessage stopMsg = new UserMessage(
                    UUID.randomUUID().toString(), MessageContent.ofText(notice));
                engine.getMutableMessages().add(stopMsg);
                emitPreambleInit(engine, config, querySource);
                emit(new SDKMessage.User(stopMsg));
                QueryHelpers.recordTranscript(engine, stopMsg);
                return new Terminal.Normal();
            }
            if (promptOutcome.hasAdditionalContext()) {
                List<String> contexts = promptOutcome.additionalContexts().stream()
                    .map(QueryHelpers::truncateHookOutput)
                    .filter(StringUtils::isNotBlank)
                    .toList();
                if (!contexts.isEmpty()) {
                    pendingUserPromptHookContext = new HookAdditionalContextAttachment(
                        contexts,
                        "UserPromptSubmit",
                        "hook-" + UUID.randomUUID(),
                        "UserPromptSubmit");
                }
            }
        }


        // callbacks) before yielding the SDK init message. Keeping this after
        // the hook is wire-observable because callback requests write directly
        // to the shared stream-json stdout channel.
        emitPreambleInit(engine, config, querySource);

        // System prompt assembly: base + auto-memory mechanics (when a custom

        String base = params.systemPrompt() != null ? params.systemPrompt() : "";
        StringBuilder sp = new StringBuilder(base);
        String memOverride = SubprocessEnvironment.get(
            "CLAUDE_COWORK_MEMORY_PATH_OVERRIDE");
        boolean hasCustomPrompt = StringUtils.isNotBlank(params.systemPrompt());
        boolean hasAutoMemPathOverride = StringUtils.isNotBlank(memOverride);
        if (hasCustomPrompt && hasAutoMemPathOverride) {
            String memoryPrompt = QueryHelpers.loadMemoryPrompt(engine);
            if (StringUtils.isNotEmpty(memoryPrompt)) {
                sp.append("\n\n").append(memoryPrompt);
            }
        }
        this.effectiveSystemPrompt = sp.toString();

        return null;
    }


    private static boolean isLocalCommandOutput(MessageContent content) {
        if (content == null) return false;
        if (content.text() != null) {
            return Strings.CS.startsWith(content.text(), "<local-command-stdout>")
                || Strings.CS.startsWith(content.text(), "<local-command-stderr>");
        }
        if (content.blocks() == null || content.blocks().size() != 1
                || !(content.blocks().getFirst() instanceof TextBlock text)) {
            return false;
        }
        return Strings.CS.startsWith(text.text(), "<local-command-stdout>")
            || Strings.CS.startsWith(text.text(), "<local-command-stderr>");
    }

    /**
     * Materializes commands 2..N of a batched queue drain as their own user messages.
     */
    private void appendAdditionalUserMessages(DefaultQuerySession engine, SubmitOptions options) {
        if (options.additionalUserMessages().isEmpty()) return;
        boolean taskNotification = Strings.CS.equals("task-notification", options.querySource());
        for (MessageContent content : options.additionalUserMessages()) {
            UserMessage extra = new UserMessage(
                UUID.randomUUID().toString(),
                content,
                false, false, null,
                taskNotification ? MessageOrigin.TASK_NOTIFICATION : MessageOrigin.USER,
                null, Instant.now(),
                null, options.permissionMode(), engine.getSessionId(), null);
            engine.getMutableMessages().add(extra);
            emit(new SDKMessage.User(extra, true));
            QueryHelpers.recordTranscript(engine, extra);
        }
    }

    private void appendPendingImageMetadata(DefaultQuerySession engine) {
        UserMessage metadata = pendingImageMetadataMessage;
        if (metadata == null) return;
        pendingImageMetadataMessage = null;
        engine.getMutableMessages().add(metadata);
        emit(new SDKMessage.User(metadata));
        QueryHelpers.recordTranscript(engine, metadata);
    }

    private void emitPreambleInit(DefaultQuerySession engine, QuerySessionSpec config,
                                  String querySource) {
        int toolCount = config.tools() != null ? config.tools().size() : 0;
        SystemMessage initMsg = new SystemMessage(
            UUID.randomUUID().toString(), "system_init", "info",
            String.format("Session: %s | Model: %s | Tools: %d | Source: %s",
                engine.getSessionId(), config.model(), toolCount, querySource));
        emit(new SDKMessage.System(initMsg));
        config.headlessTurnProfiler().checkpoint("system_message_yielded");

        Optional<String> orphaned = engine.handleOrphanedPermissions();
        orphaned.ifPresent(text -> {
            SystemMessage permMsg = new SystemMessage(
                UUID.randomUUID().toString(), "orphaned_permissions", "warn", text);
            emit(new SDKMessage.System(permMsg));
        });

        QueryHelpers.loadSkillsAndPlugins(engine);
    }

    /**
     * Applies the request-only aggregate result budget and persists any new
     * replacement decisions alongside the transcript. The transcript remains
     * unchanged; only the API request view receives preview content.
     */
    private static List<Message> applyToolResultBudget(
            DefaultQuerySession engine, QuerySessionSpec config) {
        List<Message> requestMessages = config.toolExecutor().applyToolResultBudget(
            engine.getMessages(), engine.getSessionId(), config.workingDirectory(), config.agentId());
        List<ToolResultBudget.Replacement> replacements =
            config.toolExecutor().drainToolResultBudgetReplacements(
                engine.getSessionId(), config.workingDirectory(), config.agentId());
        if (!replacements.isEmpty()) {
            TranscriptSink sink = engine.getTranscriptSink();
            if (sink != null) {
                try {
                    sink.recordContentReplacements(engine.getSessionId(), replacements);
                } catch (Throwable failure) {
                    log.warn("[TRANSCRIPT] Content-replacement persistence failed "
                            + "[sessionId={}, replacements={}, failureType={}]",
                        engine.getSessionId(), replacements.size(), failure.getClass().getName(),
                        ErrorUtils.redactedForLogging(failure));
                    // Transcript persistence is best-effort and must not alter
                    // the request that has already been assembled.
                }
            }
        }
        return requestMessages;
    }


    private static int parseMaxStructuredOutputRetries() {        String raw = SubprocessEnvironment.get(
            "MAX_STRUCTURED_OUTPUT_RETRIES");
        if (StringUtils.isBlank(raw)) return 5;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return 5;
        }
    }

    /**
     * Reads the consecutive stop-hook blocking ceiling from {@code CLAUDE_CODE_STOP_HOOK_BLOCK_CAP}.
     */
    private static int parseStopHookBlockCap() {
        String raw = SubprocessEnvironment.get("CLAUDE_CODE_STOP_HOOK_BLOCK_CAP");
        if (StringUtils.isBlank(raw)) return 8;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            return 8;
        }
    }



    /**
     * Builds the isMeta image-metadata line for a pasted image, preferring the post-resize dimensions
     * but falling back to the paste-time dimensions.
     */
    private static String buildImageMetadataText(
            ImageResizer.PastedDims rd, PastedContent.ImageDimensions od, String sourcePath) {
        if (rd != null) {
            return createImageMetadataText(
                rd.originalWidth(), rd.originalHeight(), rd.displayWidth(), rd.displayHeight(), sourcePath);
        }
        if (od != null) {
            return createImageMetadataText(
                od.originalWidth(), od.originalHeight(), od.displayWidth(), od.displayHeight(), sourcePath);
        }
        return sourcePath != null ? "[Image source: " + sourcePath + "]" : null;
    }


    private static String createImageMetadataText(
            Integer origW, Integer origH, Integer dispW, Integer dispH, String sourcePath) {
        return ImageResizer.createImageMetadataText(
            new ImageResizer.PastedDims(origW, origH, dispW, dispH), sourcePath);
    }


    private void maybeInjectTooLargeApiError(DefaultQuerySession engine, Throwable e) {
        ApiErrorMessages.TooLargeKind kind = ApiErrorMessages.classify(e.getMessage());
        if (kind == null) return;
        boolean nonInteractive = false;
        try {
            var supplier = engine.getConfig().promptRuntimeSupplier();
            nonInteractive = supplier != null && supplier.get().isNonInteractiveSession();
        } catch (RuntimeException _) {
        }
        String text = ApiErrorMessages.tooLargeMessage(kind, nonInteractive);
        AssistantMessage msg = MessageFactory.createAssistantAPIErrorMessage(text);
        engine.getMutableMessages().add(msg);
        emit(new SDKMessage.Assistant(msg, Usage.EMPTY));
        QueryHelpers.recordTranscript(engine, msg);
    }


    private static boolean isPromptTooLong(Throwable error) {
        return error != null
            && ApiErrorMessages.classify(error.getMessage())
                == ApiErrorMessages.TooLargeKind.PROMPT_TOO_LONG;
    }

    private void emitAutomaticCompactionFailure(DefaultQuerySession engine, QueryState state,
                                                String lastStopReason, long loopStartTime,
                                                Exception cause) {
        Exception surfaced = new IllegalStateException(
            "Automatic compaction failed", cause);
        log.warn("[QUERY] Automatic compaction failed [sessionId={}, model={}, agentId={}, "
                + "failureType={}]",
            engine.getSessionId(), engine.getConfig().model(), engine.getConfig().agentId(),
            surfaced.getClass().getName(), ErrorUtils.redactedForLogging(surfaced));
        QueryHelpers.dispatchStopFailure(engine, "automatic_compaction_failed");
        emitResult(engine, new Terminal.StreamError(
            surfaced.getMessage(), surfaced), surfaced,
            state.turnCount(), lastStopReason, loopStartTime);
    }
}
