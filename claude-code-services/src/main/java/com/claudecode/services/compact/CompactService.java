package com.claudecode.services.compact;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.ThinkingClearLatch;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.cache.PromptCacheBreakDetection;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;






























public class CompactService implements MessageCompactor {


    public record TokenWarningState(
        long percentLeft,
        boolean isAboveWarningThreshold,
        boolean isAboveErrorThreshold,
        boolean isAboveAutoCompactThreshold,
        boolean isAtBlockingLimit
    ) {}

    private final TokenEstimator tokenEstimator;
    private final CompactSummarizer summarizer;
    private boolean autoCompactEnabled;

    private boolean reactiveCompactEnabled;

    private final MicrocompactStrategy microcompact;
    private final AutoCompactStrategy autoCompact;
    private final ManualCompactStrategy manualCompact;

    /**
     * Per-session suppress flag for the token / auto-compact warning banner.
     */
    private volatile boolean compactWarningSuppressed = false;

    /**
     * Supplies the live {@link FileStateCache} for post-compact file
     * re-attachment. {@code CompactService} is constructed before
     * {@code QuerySession} (which owns the cache) in {@code ClaudeCodeCli}, so
     * this is a late-bound supplier — matches the existing
     * {@code config.setPermissionModeSupplier(...)} pattern — rather than a
     * plain field. Defaults to a no-op ({@code null} cache) until the real
     * engine wires itself in via {@link #setFileStateCacheSupplier}.
     */
    private Supplier<FileStateCache> fileStateCacheSupplier = () -> null;

    /** Freezes concrete tool-owned registries into immutable compact read models. */
    private CompactAttachmentStateProvider attachmentStateProvider =
        ToolCompactAttachmentStateProvider.standard();

    /**
     * The current session id, needed by the plan-file producer/reminder.
     * Injected post-construction from {@code ClaudeCodeCli} (no process-wide
     * default makes sense here, unlike the process-global state wrapped by
     * {@link #attachmentStateProvider}).
     */
    private SessionIdentity sessionIdentity;

    /**
     * Exact persisted JSONL path used in the post-compact continuation
     * message. Production wiring may supply the startup-session path directly;
     * otherwise it is derived from the current session identity and cwd.
     */
    private Supplier<String> transcriptPathSupplier = () -> null;

    /** Full first-turn agents+skills listing used to rebuild the compact-time
     * {@code agent_listing_delta}; late-bound like the live file cache. */
    private Supplier<String> agentListingSupplier = () -> null;

    /** Connected MCP server instructions re-announced after compaction. */
    private Supplier<Map<String, String>> mcpInstructionsSupplier = Map::of;

    /** Current model-visible tool pool used for compact-time deferred-tool deltas. */
    private Supplier<List<String>> toolNamesSupplier = List::of;

    /**
     * The sub-agent id this service instance is scoped to, when it backs a sub-agent's {@link
     * com.claudecode.core.engine.QuerySession}.
     */
    private String agentId;

    /**
     * Creates a CompactService with default settings (no summarizer, auto-compact enabled).
     */
    public CompactService() {
        this(TokenEstimator.getInstance(), null, true);
    }

    /**
     * Creates a CompactService with the given dependencies.
     */
    public CompactService(TokenEstimator tokenEstimator, CompactSummarizer summarizer,
                          boolean autoCompactEnabled) {
        this.tokenEstimator = tokenEstimator;
        this.summarizer = summarizer;
        this.autoCompactEnabled = autoCompactEnabled;
        this.microcompact = new DefaultMicrocompactStrategy();
        this.autoCompact = new DefaultAutoCompactStrategy(tokenEstimator);
        this.manualCompact = new DefaultManualCompactStrategy(tokenEstimator);
    }

    /**
     * Wires the live {@link FileStateCache} supplier once the owning
     * {@code QuerySession} exists. Must be called by {@code ClaudeCodeCli}
     * right after {@code QuerySession} construction — see class-level note.
     */
    public void setFileStateCacheSupplier(Supplier<FileStateCache> fileStateCacheSupplier) {
        this.fileStateCacheSupplier = fileStateCacheSupplier != null ? fileStateCacheSupplier : () -> null;
    }

    /** Injects the current session id, needed by the plan-file post-compact producer. */
    public void setSessionIdentity(SessionIdentity sessionIdentity) {
        this.sessionIdentity = sessionIdentity;
    }

    /** Supplies the exact transcript path retained by the session recorder. */
    public void setTranscriptPathSupplier(Supplier<String> transcriptPathSupplier) {
        this.transcriptPathSupplier = transcriptPathSupplier != null
            ? transcriptPathSupplier : () -> null;
    }

    /** Supplies the current full agents+skills listing for post-compact replay. */
    public void setAgentListingSupplier(Supplier<String> agentListingSupplier) {
        this.agentListingSupplier = agentListingSupplier != null
            ? agentListingSupplier : () -> null;
    }

    /** Supplies current server instructions for compact-time delta replay. */
    public void setMcpInstructionsSupplier(Supplier<Map<String, String>> mcpInstructionsSupplier) {
        this.mcpInstructionsSupplier = mcpInstructionsSupplier != null
            ? mcpInstructionsSupplier : Map::of;
    }

    /** Supplies the current tool names for compact-time delta reconstruction. */
    public void setToolNamesSupplier(Supplier<List<String>> toolNamesSupplier) {
        this.toolNamesSupplier = toolNamesSupplier != null ? toolNamesSupplier : List::of;
    }


    public void setCustomModelContextWindowResolver(Function<String, Long> resolver) {
        if (autoCompact instanceof DefaultAutoCompactStrategy strategy) {
            strategy.setCustomContextWindowResolver(resolver);
        }
    }

    /**
     * Scopes this instance to a sub-agent id so per-agent attachment producers
     * (invoked skills, task status) resolve against that agent's entries rather
     * than the root. Empty (the default) means "main-thread / root" — set by
     * {@link SubAgentCompactServiceImpl} for sub-agent engines only.
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * Backward-compatible test/override hook. The default provider keeps the
     * concrete {@link TaskStore} behind the snapshot boundary.
     */
    public void setTaskStore(TaskStore taskStore) {
        if (attachmentStateProvider instanceof ToolCompactAttachmentStateProvider provider) {
            provider.setTaskStore(taskStore);
        }
    }

    /** Backward-compatible test/override hook for the tool-backed provider. */
    public void setInvokedSkillRegistry(InvokedSkillRegistry invokedSkills) {
        if (attachmentStateProvider instanceof ToolCompactAttachmentStateProvider provider) {
            provider.setInvokedSkills(invokedSkills);
        }
    }

    /** Replaces the attachment-state adapter, primarily for composition/tests. */
    public void setAttachmentStateProvider(CompactAttachmentStateProvider provider) {
        this.attachmentStateProvider = provider != null
            ? provider
            : (_, scopedAgentId, subAgent) ->
                CompactAttachmentStateProvider.Snapshot.empty(scopedAgentId, subAgent);
    }

    /**
     * Builds the lazy live-process context {@link DefaultManualCompactStrategy}'s post-compact
     * attachment producers read after summary generation succeeds.
     */
    private CompactAttachmentContext buildAttachmentContext() {
        String sessionId = sessionIdentity != null ? sessionIdentity.get() : null;
        String transcriptPath = null;
        try {
            transcriptPath = transcriptPathSupplier.get();
        } catch (RuntimeException _) { /* fall through to deterministic derivation */ }
        if ((StringUtils.isBlank(transcriptPath)) && sessionId != null) {
            transcriptPath = new SessionManager(System.getProperty("user.dir"))
                .getSessionFile(sessionId).toString();
        }
        String scopedAgentId = agentId;
        boolean subAgent = StringUtils.isNotBlank(scopedAgentId);
        return new CompactAttachmentContext(
            fileStateCacheSupplier.get(),
            () -> attachmentStateProvider.snapshot(sessionId, scopedAgentId, subAgent),
            transcriptPath,
            agentListingSupplier.get(),
            mcpInstructionsSupplier,
            toolNamesSupplier);
    }

    // ========== 1. MicroCompact (time-based trigger) ==========

    @Override
    public MessageCompactor.MicrocompactResult microcompactMessages(List<Message> messages) {
        clearCompactWarningSuppression();
        return microcompact.apply(messages);
    }

    @Override
    public MessageCompactor.MicrocompactResult microcompactMessages(List<Message> messages,
                                                                    boolean liveMainThread) {
        clearCompactWarningSuppression();
        return microcompact.apply(messages, liveMainThread);
    }

    @Override
    public void prepareManualCompact() {
        if (summarizer != null) {
            summarizer.prepareManualCompact();
        }
    }

    /**
     * Walk assistant messages and collect tool_use IDs whose tool name is in
     * {@link MicrocompactStrategy#COMPACTABLE_TOOLS}.
     */
    Set<String> collectCompactableToolIds(List<Message> messages) {
        return ((DefaultMicrocompactStrategy) microcompact).collectCompactableToolIds(messages);
    }

    // ========== 2. AutoCompact (automatic full compaction) ==========

    /**
     * Check whether auto-compaction should be triggered.
     * <p>
     * Trigger condition: token count exceeds ~93% of effective context window.
     * Recursive protection: skip if querySource is "compact" or "session_memory".
     *
     * @param messages    the conversation messages
     * @param model       the model name (used to determine context window size)
     * @param querySource the source of the current query
     * @return true if auto-compaction should be triggered
     */
    @Override public boolean shouldAutoCompact(List<Message> messages, String model, String querySource) {
        return autoCompact.shouldTrigger(messages, model, querySource, isAutoCompactEnabled());
    }

    @Override
    public long getAutoCompactThreshold(String model) {
        return autoCompact.getAutoCompactThreshold(model);
    }

    @Override
    public String getAutoCompactSource(String model) {
        return autoCompact.getAutoCompactSource(model);
    }

    @Override
    public boolean shouldAutoCompact(List<Message> messages, String model, String querySource, long snipTokensFreed) {
        return autoCompact.shouldTrigger(messages, model, querySource, isAutoCompactEnabled(), snipTokensFreed);
    }

    @Override
    public boolean isAtBlockingLimit(long tokenUsage, String model) {
        return autoCompact.isAtBlockingLimit(tokenUsage, model, isAutoCompactEnabled());
    }

    @Override
    public boolean isAtBlockingLimit(List<Message> messages, String model) {
        return autoCompact.isAtBlockingLimit(messages, model, isAutoCompactEnabled());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Delegates to the real, content-aware {@link TokenEstimator} instead of
     * the interface's flat per-message fallback.
     */
    @Override
    public long estimateTokenCount(List<Message> messages) {
        return tokenEstimator.estimateTokenCount(messages);
    }

    /** {@inheritDoc} */
    @Override
    public long contextTokenCount(List<Message> messages, String model) {
        return tokenEstimator.tokenCountWithEstimation(
            messages, model, DefaultAutoCompactStrategy.charsPerTokenForModel(model));
    }

    // ========== Token warning state ==========

    /**
     * Compute the auto-compact token-warning state for the given live token usage.
     */
    public TokenWarningState calculateTokenWarningState(long tokenUsage, String model) {
        return autoCompact.calculateTokenWarningState(tokenUsage, model, isAutoCompactEnabled());
    }

    /**
     * Suppress the token / auto-compact warning for the rest of the session.
     */
    @Override
    public void suppressCompactWarning() {
        compactWarningSuppressed = true;
    }

/**
     * Whether the token warning is currently suppressed.
     */
    public boolean isCompactWarningSuppressed() {
        return compactWarningSuppressed;
    }

    /**
     * Clear the suppression after a successful compaction so the warning can re-arm.
     */
    public void clearCompactWarningSuppression() {
        compactWarningSuppressed = false;
    }

    // ========== 3. Full Compaction (auto + manual shared) ==========

    /**
     * Compact the conversation by summarizing messages via the provided summarizer.
     * <p>
     * Flow:
     * <ol>
     *   <li>Validate messages are non-empty</li>
     *   <li>Estimate pre-compact token count</li>
     *   <li>Build compact prompt</li>
     *   <li>Call summarizer for summary (with prompt-too-long retry)</li>
     *   <li>Create compact_boundary marker</li>
     *   <li>Create summary message</li>
     *   <li>Generate post-compact attachments</li>
     *   <li>Return CompactionResult</li>
     * </ol>
     *
     * @param messages   the conversation messages to compact
     * @param compactSummarizer the summarizer to use for this compaction
     * @param isAuto     true if triggered automatically, false if manual
     * @return the compaction result
     * @throws CompactException if compaction fails
     */
    public MessageCompactor.CompactionResult compactConversation(List<Message> messages,
                                                 CompactSummarizer compactSummarizer,
                                                 boolean isAuto) {
        return compactConversation(messages, compactSummarizer, isAuto, null);
    }

    /**
     * Compact the conversation by summarizing messages via LLM.
     * <p>
     * Flow:
     * <ol>
     *   <li>Validate messages are non-empty</li>
     *   <li>Estimate pre-compact token count</li>
     *   <li>Build compact prompt</li>
     *   <li>Call LLM for summary (with prompt-too-long retry)</li>
     *   <li>Create compact_boundary marker</li>
     *   <li>Create summary message</li>
     *   <li>Generate post-compact attachments</li>
     *   <li>Return CompactionResult</li>
     * </ol>
     *
     * @param messages       the conversation messages to compact
     * @param isAutoCompact  true if triggered automatically, false if manual
     * @param customInstructions optional custom instructions for the compact prompt
     * @return the compaction result
     * @throws CompactException if compaction fails
     */
    @Override
    public MessageCompactor.CompactionResult compactConversation(List<Message> messages,
                                                 boolean isAutoCompact,
                                                 String customInstructions) {
        return compactConversation(messages, isAutoCompact, customInstructions, null);
    }

    @Override
    public MessageCompactor.CompactionResult compactConversation(List<Message> messages,
                                                 boolean isAutoCompact,
                                                 String customInstructions,
                                                 String model) {
        if (summarizer == null) {
            // Auto-compact fires unattended at the context-window threshold —
            // when no LLM-driven summary is wired (test/override environments),
            // pass the messages through unchanged instead of throwing so the
            // engine stays alive. Manual /compact fails loud instead: the user
            // explicitly asked for a summary that can't be produced.
            if (isAutoCompact) {
                long preCompactTokens = contextTokenCount(messages, model);
                SystemMessage boundary = createCompactBoundaryMarker("passthrough", preCompactTokens);
                ThinkingClearLatch.reset();
                // A completed compaction shrinks the conversation enough that the
                // prompt-cache-break tracker's cached baseline is stale — reset it


                PromptCacheBreakDetection.notifyCompaction("compact", null);
// A compaction consumed the over-threshold context, so re-arm the token warning
// (clear any prior suppression).
                clearCompactWarningSuppression();
                return new MessageCompactor.CompactionResult(
                        boundary, List.of(), List.of(), List.of(),
                        new ArrayList<>(messages), preCompactTokens);
            }
            throw new CompactException("No CompactSummarizer configured");
        }
        MessageCompactor.CompactionResult result =
                compactConversation(messages, summarizer, isAutoCompact, customInstructions, model);
// A completed compaction (manual /compact or auto-compact) shrinks the conversation enough
// that the thinking-clear latch's "keep only the last turn" state should re-evaluate from
// scratch.
        ThinkingClearLatch.reset();
        // A completed compaction (manual /compact or auto-compact) shrinks the
        // conversation enough that the prompt-cache-break tracker's cached


        PromptCacheBreakDetection.notifyCompaction("compact", null);
// A compaction consumed the over-threshold context, so re-arm the token warning (clear any
// prior suppression).
        clearCompactWarningSuppression();
        return result;
    }

    /**
     * Internal compaction implementation shared by all overloads.
     */
    private MessageCompactor.CompactionResult compactConversation(List<Message> messages,
                                                 CompactSummarizer compactSummarizer,
                                                 boolean isAutoCompact,
                                                 String customInstructions) {
        return compactConversation(messages, compactSummarizer, isAutoCompact, customInstructions, null);
    }

    private MessageCompactor.CompactionResult compactConversation(List<Message> messages,
                                                 CompactSummarizer compactSummarizer,
                                                 boolean isAutoCompact,
                                                 String customInstructions,
                                                 String model) {
        return manualCompact.compact(messages, compactSummarizer, isAutoCompact, customInstructions,
                buildAttachmentContext(), model);
    }

    /**
     * Truncate the oldest message group to reduce prompt size for retry.
     * Uses {@link MessageGrouping#groupByApiRound} to identify groups,
     * then removes the first group.
     *
     * @param messages the current messages
     * @return messages with the oldest group removed
     * @throws CompactException if there are not enough groups to truncate
     */
    List<Message> truncateHeadForPTLRetry(List<Message> messages) {
        return ((DefaultManualCompactStrategy) manualCompact).truncateHeadForPTLRetry(messages);
    }

    // ========== Compact boundary marker ==========

    /**
     * Create a compact_boundary system message.
     *
     * @param compactType         "auto" or "manual"
     * @param preCompactTokenCount token count before compaction
     * @return the boundary marker system message
     */
    static SystemMessage createCompactBoundaryMarker(String compactType,
                                                      long preCompactTokenCount) {
        return new SystemMessage(
                UUID.randomUUID().toString(),
                "compact_boundary",
                "info",
                "Conversation compacted",
                null,
                Instant.now(),
                new CompactMetadata(compactType, preCompactTokenCount)
        );
    }

    /**
     * Adds the completion metrics written by Claude Code.
     */
    static SystemMessage finalizeCompactBoundaryMetadata(SystemMessage boundary,
                                                           List<Message> preCompactMessages,
                                                           long durationMs,
                                                           long postTokens) {
        CompactMetadata metadata = boundary.compactMetadata();
        if (metadata == null) return boundary;
        long previousDropped = 0;
        if (preCompactMessages != null) {
            for (Message message : preCompactMessages) {
                if (message instanceof SystemMessage system
                        && Strings.CS.equals("compact_boundary", system.subtype())
                        && system.compactMetadata() != null
                        && system.compactMetadata().cumulativeDroppedTokens() != null) {
                    previousDropped = system.compactMetadata().cumulativeDroppedTokens();
                }
            }
        }
        long preTokens = metadata.preTokens() != null ? metadata.preTokens() : 0;
        long cumulativeDropped = previousDropped + Math.max(0, preTokens - postTokens);
        CompactMetadata completed = metadata.withCompletion(
            Math.max(0, durationMs), Math.max(0, postTokens), cumulativeDropped);
        return copyBoundaryWithMetadata(boundary, completed);
    }

    /**
     * Attach a {@code preservedSegment} to a compact boundary marker so the kept (preserved) portion of
     * a partial compact can be re-linked and kept intact on session replay.
     */
    public static SystemMessage annotateBoundaryWithPreservedSegment(SystemMessage boundary,
                                                                      String anchorUuid,
                                                                      List<Message> messagesToKeep) {
        if (messagesToKeep == null || messagesToKeep.isEmpty()) {
            return boundary;
        }
        PreservedSegment segment = new PreservedSegment(
                messagesToKeep.getFirst().uuid(),
                anchorUuid,
                messagesToKeep.getLast().uuid());
        List<String> keptUuids = messagesToKeep.stream().map(Message::uuid).toList();
        PreservedMessages preservedMessages = new PreservedMessages(
            anchorUuid, keptUuids, keptUuids);
        CompactMetadata base = boundary.compactMetadata() != null
            ? boundary.compactMetadata() : new CompactMetadata(null);
        CompactMetadata metadata = base.withPreserved(segment, preservedMessages);
        return copyBoundaryWithMetadata(boundary, metadata);
    }

    /** Adds the 2.1.197 partial-compact context and explicit logical parent. */
    static SystemMessage annotatePartialCompactBoundary(SystemMessage boundary,
                                                         String logicalParentUuid,
                                                         String userContext,
                                                         int messagesSummarized) {
        CompactMetadata base = boundary.compactMetadata() != null
            ? boundary.compactMetadata() : new CompactMetadata("manual", 0L);
        return new SystemMessage(
            boundary.uuid(), boundary.subtype(), boundary.level(), boundary.content(),
            logicalParentUuid, boundary.timestamp().orElse(null),
            base.withPartialContext(userContext, messagesSummarized));
    }

    /** Persists the discovered tool-reference set that existed before compaction. */
    static SystemMessage annotatePreCompactDiscoveredTools(
            SystemMessage boundary, List<Message> messages) {
        List<String> discovered = ToolSearchGate.extractDiscoveredToolNames(messages).stream()
            .sorted().toList();
        if (discovered.isEmpty()) return boundary;
        CompactMetadata base = boundary.compactMetadata() != null
            ? boundary.compactMetadata() : new CompactMetadata((PreservedSegment) null);
        return copyBoundaryWithMetadata(
            boundary, base.withPreCompactDiscoveredTools(discovered));
    }

    /** Adds the compact-summary metadata rendered by the 2.1.197 normal conversation UI. */
    static UserMessage annotatePartialCompactSummary(UserMessage summary,
                                                      int messagesSummarized,
                                                      String userContext,
                                                      String direction,
                                                      boolean hasKeptMessages) {
        return new UserMessage(
            summary.uuid(), summary.message(), summary.isMeta(), summary.isCompactSummary(),
            summary.toolUseResult(), summary.origin(), summary.parentUuidValue(),
            summary.timestampValue(), summary.imagePasteIds(), summary.permissionMode(),
            summary.sessionIdValue(), summary.sourceToolAssistantUUID(),
            summary.sourceToolUseID(), summary.isVirtual(), summary.mcpMeta(),
            hasKeptMessages ? null : Boolean.TRUE, summary.planContent(),
            hasKeptMessages
                ? new SummarizeMetadata(messagesSummarized, userContext, direction)
                : null);
    }

    private static SystemMessage copyBoundaryWithMetadata(SystemMessage boundary,
                                                            CompactMetadata metadata) {
        return new SystemMessage(
                boundary.uuid(),
                boundary.subtype(),
                boundary.level(),
                boundary.content(),
                boundary.parentUuid().orElse(null),
                boundary.timestamp().orElse(null),
                metadata);
    }

    /**
     * Create a compact summary user message.
     */
    static UserMessage createCompactSummaryMessage(String summary) {
        return new UserMessage(
                UUID.randomUUID().toString(),
                MessageContent.ofText(summary),
                false,
                true, // isCompactSummary
                null,
                MessageOrigin.COMPACT_SUMMARY,
                null,
                Instant.now(),
                null,  // imagePasteIds
                null,  // permissionMode
                null,  // sessionId
                null,  // sourceToolAssistantUUID
                null,  // sourceToolUseID
                null,  // isVirtual
                null,  // mcpMeta
                true   // isVisibleInTranscriptOnly
        );
    }

    // ========== Post-compact attachments ==========

    /**
     * Generate post-compact attachment messages.
     * Placeholder: returns empty list. Future implementation will re-read key files
     * and restore plan state.
     *
     * @return list of attachment messages (currently empty)
     */
    public List<Message> createPostCompactAttachments() {
        return List.of();
    }

    // ========== Compact prompt ==========

    /**
     * Marker prefix a summarizer returns when the underlying API rejected the request as
     * prompt-too-long — triggers the PTL head-truncation retry loop.
     */
    public static final String PROMPT_TOO_LONG_MARKER = "Prompt is too long";

    /** User-facing guidance emitted after partial/full compact PTL retries are exhausted. */
    public static final String ERROR_MESSAGE_PROMPT_TOO_LONG =
        "Conversation too long. Press esc twice to go up a few messages and try again.";


    private static final String NO_TOOLS_PREAMBLE = """
        CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

        - Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
        - You already have all the context you need in the conversation above.
        - Tool calls will be REJECTED and will waste your only turn — you will fail the task.
        - Your entire response must be plain text: an <analysis> block followed by a <summary> block.

        """;


    private static final String BASE_COMPACT_PROMPT = """
        Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
        This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

        Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

        1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
           - The user's explicit requests and intents
           - Your approach to addressing the user's requests
           - Key decisions, technical concepts and code patterns
           - Specific details like:
             - file names
             - full code snippets
             - function signatures
             - file edits
           - Errors that you ran into and how you fixed them
           - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
           - Note any security-relevant instructions or constraints the user stated (e.g., sensitive files or data to avoid, operations that must not be performed, credential or secret handling rules). These MUST be preserved verbatim in the summary so they continue to apply after compaction.
        2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

        Your summary should include the following sections:

        1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
        2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
        3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.
        4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
        5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
        6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent. Preserve any security-relevant instructions or constraints verbatim so they remain in effect after compaction.
        7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
        8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
        9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests, and the task you were working on immediately before this summary request. If your last task was concluded, then only list next steps if they are explicitly in line with the users request. Do not start on tangential requests or really old requests that were already completed without confirming with the user first.
                               If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off. This should be verbatim to ensure there's no drift in task interpretation.

        Here's an example of how your output should be structured:

        <example>
        <analysis>
        [Your thought process, ensuring all points are covered thoroughly and accurately]
        </analysis>

        <summary>
        1. Primary Request and Intent:
           [Detailed description]

        2. Key Technical Concepts:
           - [Concept 1]
           - [Concept 2]
           - [...]

        3. Files and Code Sections:
           - [File Name 1]
              - [Summary of why this file is important]
              - [Summary of the changes made to this file, if any]
              - [Important Code Snippet]
           - [File Name 2]
              - [Important Code Snippet]
           - [...]

        4. Errors and fixes:
            - [Detailed description of error 1]:
              - [How you fixed the error]
              - [User feedback on the error if any]
            - [...]

        5. Problem Solving:
           [Description of solved problems and ongoing troubleshooting]

        6. All user messages:\s
            - [Detailed non tool use user message]
            - [...]

        7. Pending Tasks:
           - [Task 1]
           - [Task 2]
           - [...]

        8. Current Work:
           [Precise description of current work]

        9. Optional Next Step:
           [Optional Next step to take]

        </summary>
        </example>

        Please provide your summary based on the conversation so far, following this structure and ensuring precision and thoroughness in your response.\s

        There may be additional summarization instructions provided in the included context. If so, remember to follow these instructions when creating the above summary. Examples of instructions include:
        <example>
        ## Compact Instructions
        When summarizing the conversation focus on typescript code changes and also remember the mistakes you made and how you fixed them.
        </example>

        <example>
        # Summary instructions
        When you are using compact - please focus on test output and code changes. Include file reads verbatim.
        </example>
        """;


    private static final String NO_TOOLS_TRAILER =
        """


            REMINDER: Do NOT call any tools. Respond with plain text only — \
            an <analysis> block followed by a <summary> block. \
            Tool calls will be rejected and you will fail the task.""";


    private static final String DETAILED_ANALYSIS_INSTRUCTION_PARTIAL = """
        Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

        1. Analyze the recent messages chronologically. For each section thoroughly identify:
           - The user's explicit requests and intents
           - Your approach to addressing the user's requests
           - Key decisions, technical concepts and code patterns
           - Specific details like:
             - file names
             - full code snippets
             - function signatures
             - file edits
           - Errors that you ran into and how you fixed them
           - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
        2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.\
        """;


    private static final String PARTIAL_COMPACT_PROMPT = """
        Your task is to create a detailed summary of the RECENT portion of the conversation — the messages that follow earlier retained context. The earlier messages are being kept intact and do NOT need to be summarized. Focus your summary on what was discussed, learned, and accomplished in the recent messages only.

        """ + DETAILED_ANALYSIS_INSTRUCTION_PARTIAL + """

        Your summary should include the following sections:

        1. Primary Request and Intent: Capture the user's explicit requests and intents from the recent messages
        2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed recently.
        3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Include full code snippets where applicable and include a summary of why this file read or edit is important.
        4. Errors and fixes: List errors encountered and how they were fixed.
        5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
        6. All user messages: List ALL user messages from the recent portion that are not tool results.
        7. Pending Tasks: Outline any pending tasks from the recent messages.
        8. Current Work: Describe precisely what was being worked on immediately before this summary request.
        9. Optional Next Step: List the next step related to the most recent work. Include direct quotes from the most recent conversation.

        Here's an example of how your output should be structured:

        <example>
        <analysis>
        [Your thought process, ensuring all points are covered thoroughly and accurately]
        </analysis>

        <summary>
        1. Primary Request and Intent:
           [Detailed description]

        2. Key Technical Concepts:
           - [Concept 1]
           - [Concept 2]

        3. Files and Code Sections:
           - [File Name 1]
              - [Summary of why this file is important]
              - [Important Code Snippet]

        4. Errors and fixes:
            - [Error description]:
              - [How you fixed it]

        5. Problem Solving:
           [Description]

        6. All user messages:
            - [Detailed non tool use user message]

        7. Pending Tasks:
           - [Task 1]

        8. Current Work:
           [Precise description of current work]

        9. Optional Next Step:
           [Optional next step to take]

        </summary>
        </example>

        Please provide your summary based on the RECENT messages only (after the retained earlier context), following this structure and ensuring precision and thoroughness in your response.\
        """;


    private static final String PARTIAL_COMPACT_UP_TO_PROMPT = """
        Your task is to create a detailed summary of this conversation. This summary will be placed at the start of a continuing session; newer messages that build on this context will follow after your summary (you do not see them here). Summarize thoroughly so that someone reading only your summary and then the newer messages can fully understand what happened and continue the work.

        Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

        1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
           - The user's explicit requests and intents
           - Your approach to addressing the user's requests
           - Key decisions, technical concepts and code patterns
           - Specific details like:
             - file names
             - full code snippets
             - function signatures
             - file edits
           - Errors that you ran into and how you fixed them
           - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
        2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

        Your summary should include the following sections:

        1. Primary Request and Intent: Capture the user's explicit requests and intents in detail
        2. Key Technical Concepts: List important technical concepts, technologies, and frameworks discussed.
        3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Include full code snippets where applicable and include a summary of why this file read or edit is important.
        4. Errors and fixes: List errors encountered and how they were fixed.
        5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
        6. All user messages: List ALL user messages that are not tool results.
        7. Pending Tasks: Outline any pending tasks.
        8. Work Completed: Describe what was accomplished by the end of this portion.
        9. Context for Continuing Work: Summarize any context, decisions, or state that would be needed to understand and continue the work in subsequent messages.

        Here's an example of how your output should be structured:

        <example>
        <analysis>
        [Your thought process, ensuring all points are covered thoroughly and accurately]
        </analysis>

        <summary>
        1. Primary Request and Intent:
           [Detailed description]

        2. Key Technical Concepts:
           - [Concept 1]
           - [Concept 2]

        3. Files and Code Sections:
           - [File Name 1]
              - [Summary of why this file is important]
              - [Important Code Snippet]

        4. Errors and fixes:
            - [Error description]:
              - [How you fixed it]

        5. Problem Solving:
           [Description]

        6. All user messages:
            - [Detailed non tool use user message]

        7. Pending Tasks:
           - [Task 1]

        8. Work Completed:
           [Detailed description of what was accomplished]

        9. Context for Continuing Work:
           [Key context, decisions, or state needed to continue the work]

        </summary>
        </example>

        Please provide your summary following this structure, ensuring precision and thoroughness in your response.\
        """;

    /**
     * Build the compact prompt that instructs the LLM to summarize the conversation.
     */
    static String buildCompactPrompt(String customInstructions) {
        StringBuilder sb = new StringBuilder(NO_TOOLS_PREAMBLE).append(BASE_COMPACT_PROMPT);
        if (StringUtils.isNotBlank(customInstructions)) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }
        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * Build the partial-compact prompt that instructs the LLM to summarize only a slice of the
     * conversation.
     */
    static String buildPartialCompactPrompt(String customInstructions, String direction) {
        String template = Strings.CS.equals("up_to", direction) ? PARTIAL_COMPACT_UP_TO_PROMPT : PARTIAL_COMPACT_PROMPT;
        StringBuilder sb = new StringBuilder(NO_TOOLS_PREAMBLE).append(template);
        if (StringUtils.isNotBlank(customInstructions)) {
            sb.append("\n\nAdditional Instructions:\n").append(customInstructions);
        }
        sb.append(NO_TOOLS_TRAILER);
        return sb.toString();
    }

    /**
     * Strips the {@code <analysis>} drafting scratchpad and replaces the {@code <summary>} XML tags
     * with a readable {@code Summary:} header.
     */
    static String formatCompactSummary(String summary) {
        String formatted = summary.replaceFirst("(?s)<analysis>.*?</analysis>", "");
        Matcher m = Pattern.compile("(?s)<summary>(.*?)</summary>").matcher(formatted);
        if (m.find()) {
            String content = m.group(1) == null ? "" : m.group(1);
            formatted = formatted.substring(0, m.start())
                + "Summary:\n" + content.trim()
                + formatted.substring(m.end());
        }
        return formatted.replaceAll("\n{2,}", "\n\n").trim();
    }

    static String buildCompactUserSummaryText(String summary, boolean suppressFollowUpQuestions,
                                              String transcriptPath) {
        String base = "This session is being continued from a previous conversation that "
            + "ran out of context. The summary below covers the earlier portion of the conversation.\n\n"
            + formatCompactSummary(summary);
        if (StringUtils.isNotBlank(transcriptPath)) {
            base += "\n\nIf you need specific details from before compaction (like exact code snippets, "
                + "error messages, or content you generated), read the full transcript at: "
                + transcriptPath;
        }
        if (!suppressFollowUpQuestions) return base;
        return base + "\n"
            + "Continue the conversation from where it left off without asking the user any "
            + "further questions. Resume directly — do not acknowledge the summary, do not "
            + "recap what was happening, do not preface with \"I'll continue\" or similar. "
            + "Pick up the last task as if the break never happened.";
    }

    // ========== 4. Partial Compact ==========

    /**
     * Compact only a portion of the conversation messages.
     * <p>
     * Depending on direction:
     * <ul>
     *   <li>"from" — compact messages from pivotIndex to end</li>
     *   <li>"up_to" — compact messages from start up to pivotIndex</li>
     * </ul>
     * Progress rows are filtered from the kept portion. For {@code up_to}, stale compact
     * boundaries and summaries are filtered as well; {@code from} deliberately preserves them so
     * an earlier compact summary remains part of the active history.
     *
     * @param messages   the full conversation messages
     * @param pivotIndex the index to split at
     * @param direction  "from" or "up_to"
     * @param feedback   optional feedback/instructions for the summarizer
     * @return the partial compaction result
     * @throws CompactException if compaction fails
     */
    public PartialCompactResult partialCompactConversation(
            List<Message> messages, int pivotIndex, String direction, String feedback) {
        return partialCompactConversation(
            messages, pivotIndex, direction, feedback, feedback);
    }

    private PartialCompactResult partialCompactConversation(
            List<Message> messages, int pivotIndex, String direction,
            String feedback, String customInstructions) {
        PartialCompactResult result = manualCompact.partialCompact(
            messages, pivotIndex, direction, feedback, customInstructions,
            summarizer, buildAttachmentContext());
        ThinkingClearLatch.reset();
        PromptCacheBreakDetection.notifyCompaction("compact", null);
        clearCompactWarningSuppression();
        return result;
    }


    @Override
    public List<Message> partialCompactAndAssemble(
            List<Message> messages, int pivotIndex, String direction, String feedback) {
        PartialCompactResult r = partialCompactConversation(messages, pivotIndex, direction,
            StringUtils.isBlank(feedback) ? null : feedback);
        return buildPostPartialCompactMessages(r);
    }

    @Override
    public MessageCompactor.PartialCompactOutput partialCompact(
            List<Message> messages, int pivotIndex, String direction,
            String feedback, String customInstructions) {
        PartialCompactResult result = partialCompactConversation(
            messages, pivotIndex, direction,
            StringUtils.isBlank(feedback) ? null : feedback,
            StringUtils.isBlank(customInstructions) ? null : customInstructions);
        MessageCompactor.CompactionResult compacted = result.compactionResult();
        return new MessageCompactor.PartialCompactOutput(
            buildPostPartialCompactMessages(result),
            compacted != null ? compacted.compactionUsage() : com.claudecode.core.message.Usage.EMPTY,
            compacted != null ? compacted.rawSummary() : null);
    }


    static List<Message> buildPostPartialCompactMessages(PartialCompactResult r) {
        List<Message> kept = r.keptMessages();
        // No summary produced (empty compact slice) — the kept side is the whole

        // still switch to filtered kept-only messages (compact-boundary etc. gone).
        if (!r.hasCompaction()) {
            return List.copyOf(kept);
        }
        MessageCompactor.CompactionResult cr = r.compactionResult();
        List<Message> ordered = new ArrayList<>();
        if (Strings.CS.equals("up_to", r.direction())) {
            ordered.addAll(cr.summaryMessages());
            ordered.addAll(kept);
        } else {
            ordered.addAll(kept);
            ordered.addAll(cr.summaryMessages());
        }
        List<Message> post = new ArrayList<>();
        post.add(cr.boundaryMarker());
        post.addAll(ordered);
        post.addAll(cr.attachments());
        post.addAll(cr.hookResults());
        return post;
    }

    /**
     * Filter messages to remove progress, compact_boundary, and compact_summary types
     * from the kept portion of a partial compact.
     */
    List<Message> filterKeptMessages(List<Message> messages) {
        return ((DefaultManualCompactStrategy) manualCompact).filterKeptMessages(messages);
    }

    /**
     * Check if a message should be filtered out during partial compact.
     */
    static boolean isFilterableMessage(Message msg) {
        if (msg instanceof SystemMessage sm) {
            String subtype = sm.subtype();
            return Strings.CS.equals("progress", subtype)
                    || Strings.CS.equals("compact_boundary", subtype)
                    || Strings.CS.equals("compact_summary", subtype);
        }
        if (msg instanceof UserMessage(_, _, _, var isCompactSummary, _, _, _, _, _, _, _, _, _, _, _, _, _, _)) {
            return isCompactSummary;
        }
        return false;
    }

    // ========== Configuration ==========

    /**
     * Returns whether auto-compact is currently enabled.
     */
    @Override
    public boolean isAutoCompactEnabled() {
        if (EnvUtils.isEnvTruthy(
                SubprocessEnvironment.get("DISABLE_COMPACT"))) {
            return false;
        }
        if (EnvUtils.isEnvTruthy(
                SubprocessEnvironment.get("DISABLE_AUTO_COMPACT"))) {
            return false;
        }
        return autoCompactEnabled;
    }

    public void setAutoCompactEnabled(boolean enabled) {
        this.autoCompactEnabled = enabled;
    }

    @Override
    public boolean isReactiveCompactEnabled() {
        return reactiveCompactEnabled;
    }

    @Explanation("Adds an opt-in bridge for reactive compaction")
    public void setReactiveCompactEnabled(boolean enabled) {
        this.reactiveCompactEnabled = enabled;
    }

    // ========== MessageCompactor interface (auto-compact bridge) ==========

    /**
     * {@inheritDoc}
     * <p>
     * Bridges to the full {@link #compactConversation(List, boolean, String)}
     * overload. When no summarizer is configured, returns the messages
     * unchanged (with the estimated pre-compact token count) instead of
     * throwing — this keeps the engine alive when auto-compact fires at the
     * 93% threshold but no LLM-driven summary is wired.
     */
    @Override
    public MessageCompactor.CompactionResult compactConversation(
            List<Message> messages, boolean isAutoCompact) {
        if (summarizer == null) {
            long preCompactTokens = contextTokenCount(messages, null);
            SystemMessage boundary = createCompactBoundaryMarker("passthrough", preCompactTokens);
            // A compaction consumed the over-threshold context, so re-arm the token

            clearCompactWarningSuppression();
            return new MessageCompactor.CompactionResult(
                    boundary, List.of(), List.of(), List.of(),
                    new ArrayList<>(messages), preCompactTokens);
        }
        return compactConversation(messages, isAutoCompact, null);
    }

}
