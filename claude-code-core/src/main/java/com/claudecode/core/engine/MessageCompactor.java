package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface for message compaction operations used by the query engine.
 */
public interface MessageCompactor {

    static String mergeHookInstructions(String userInstructions, String hookInstructions) {
        boolean hasHook = StringUtils.isNotBlank(hookInstructions);
        boolean hasUser = StringUtils.isNotBlank(userInstructions);
        if (!hasHook) return hasUser ? userInstructions : null;
        if (!hasUser) return hookInstructions;
        return userInstructions + "\n\n" + hookInstructions;
    }

    static SystemMessage annotateBoundaryWithPreservedSegment(
            SystemMessage boundary, String anchorUuid, List<Message> messagesToKeep) {
        if (messagesToKeep == null || messagesToKeep.isEmpty()) return boundary;
        PreservedSegment segment = new PreservedSegment(
            messagesToKeep.getFirst().uuid(), anchorUuid, messagesToKeep.getLast().uuid());
        List<String> keptUuids = messagesToKeep.stream().map(Message::uuid).toList();
        PreservedMessages preservedMessages = new PreservedMessages(
            anchorUuid, keptUuids, keptUuids);
        CompactMetadata base = boundary.compactMetadata() != null
            ? boundary.compactMetadata() : new CompactMetadata((PreservedSegment) null);
        CompactMetadata metadata = base.withPreserved(segment, preservedMessages);
        return new SystemMessage(boundary.uuid(), boundary.subtype(), boundary.level(),
            boundary.content(), boundary.parentUuid().orElse(null),
            boundary.timestamp().orElse(null), metadata);
    }

    /** Suppress the stale low-context warning after a successful manual compact. */
    default void suppressCompactWarning() { }

    /**
     * Marker implemented by compaction failures that already received an API usage snapshot before
     * failing.
     */
    interface UsageBearingFailure {
        Usage compactionUsage();
    }

    /**
     * Circuit breaker: stop retrying autocompact after this many consecutive failures.
     */
    int MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3;

    /**
     * Result of a microcompact operation.
     */
    record MicrocompactResult(List<Message> messages) {}

    /**
     * Rich partial-compact result used by the message-selector lifecycle hooks.
     *
     * @param messages assembled boundary, kept slice, summary, attachments, and hook messages
     * @param compactionUsage real summarizer API usage when available
     * @param summaryText raw LLM summary supplied to PostCompact hooks
     */
    record PartialCompactOutput(
        List<Message> messages,
        Usage compactionUsage,
        String summaryText
    ) {
        public PartialCompactOutput {
            messages = messages == null ? List.of() : List.copyOf(messages);
            compactionUsage = compactionUsage == null ? Usage.EMPTY : compactionUsage;
        }
    }

    /**
     * Result of a full compaction operation.
     */
    record CompactionResult(
        SystemMessage boundaryMarker,
        List<Message> summaryMessages,
        List<Message> attachments,
        List<Message> hookResults,
        List<Message> messagesToKeep,
        long preCompactTokenCount,
        Usage compactionUsage,
        String rawSummary
    ) {
        /**
         * Convenience constructor for callers that don't have a real API
         * {@link Usage} for the summarization call (e.g. passthrough
         * compaction with no summarizer configured, or partial-compact
         * result types built before {@link Usage} threading existed) —
         * defaults to {@link Usage#EMPTY} and no raw summary.
         */
        public CompactionResult(SystemMessage boundaryMarker, List<Message> summaryMessages,
                                 List<Message> attachments, List<Message> hookResults,
                                 List<Message> messagesToKeep, long preCompactTokenCount) {
            this(boundaryMarker, summaryMessages, attachments, hookResults, messagesToKeep,
                preCompactTokenCount, Usage.EMPTY, null);
        }

        /** Compat constructor predating {@link #rawSummary}. */
        public CompactionResult(SystemMessage boundaryMarker, List<Message> summaryMessages,
                                 List<Message> attachments, List<Message> hookResults,
                                 List<Message> messagesToKeep, long preCompactTokenCount,
                                 Usage compactionUsage) {
            this(boundaryMarker, summaryMessages, attachments, hookResults, messagesToKeep,
                preCompactTokenCount, compactionUsage, null);
        }


        public List<Message> buildPostCompactMessages() {
            List<Message> out = new ArrayList<>();
            out.add(boundaryMarker);
            out.addAll(summaryMessages);
            out.addAll(messagesToKeep);
            out.addAll(attachments);
            out.addAll(hookResults);
            return out;
        }

        /**
         * The LLM-generated summary that replaced the compacted turns — used as the PostCompact hook's
         * {@code compact_summary} payload field.
         */
        public String summaryText() {
            if (StringUtils.isNotBlank(rawSummary)) return rawSummary;
            StringBuilder sb = new StringBuilder();
            for (Message m : summaryMessages) {
                if (m instanceof UserMessage um && um.message().isText()) {
                    if (!sb.isEmpty()) sb.append('\n');
                    sb.append(um.message().text());
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
    }

    /**
     * Truncate long tool outputs in the message list.
     *
     * @param messages the conversation messages
     * @return result with (possibly modified) message list
     */
    MicrocompactResult microcompactMessages(List<Message> messages);

    /**
     * Like {@link #microcompactMessages(List)} but marks the live main-thread request path, where the
     * time-based trigger may fire and short-circuit.
     */
    default MicrocompactResult microcompactMessages(List<Message> messages, boolean liveMainThread) {
        return microcompactMessages(messages);
    }

    /**
     * Starts a user-initiated compaction operation.
     *
     * <p>The interactive implementation uses this boundary to discard cancellation state left by
     * the preceding turn before hooks and the compact request begin. Automatic compaction runs
     * inside the active query and must not call this method.</p>
     */
    default void prepareManualCompact() {
        // Most compactor implementations do not own operation cancellation state.
    }

    /**
     * Check whether auto-compaction should be triggered.
     *
     * @param messages    the conversation messages
     * @param model       the model name
     * @param querySource the source of the current query
     * @return true if auto-compaction should be triggered
     */
    boolean shouldAutoCompact(List<Message> messages, String model, String querySource);

    /**
     * Variant that accounts for {@code snipTokensFreed} (tokens already removed by the snip step) in
     * the threshold decision.
     */
    default boolean shouldAutoCompact(List<Message> messages, String model, String querySource, long snipTokensFreed) {
        return shouldAutoCompact(messages, model, querySource);
    }

    /**
     * Compact the conversation messages.
     *
     * @param messages      the conversation messages
     * @param isAutoCompact true if triggered automatically
     * @return the compaction result with post-compact messages
     */
    CompactionResult compactConversation(List<Message> messages, boolean isAutoCompact);

    /**
     * Compact with user-supplied {@code /compact <instructions>} guidance for the summarization prompt.
     */
    default CompactionResult compactConversation(List<Message> messages, boolean isAutoCompact,
                                                  String customInstructions) {
        return compactConversation(messages, isAutoCompact);
    }

    /**
     * Model-aware auto-compact variant.
     */
    default CompactionResult compactConversation(List<Message> messages, boolean isAutoCompact,
                                                  String customInstructions, String model) {
        return compactConversation(messages, isAutoCompact, customInstructions);
    }

    /**
     * Returns true if {@code tokenUsage} has reached or exceeded the hard blocking limit ({@code
     * effectiveContextWindow - MANUAL_COMPACT_BUFFER_TOKENS}).
     */
    default boolean isAtBlockingLimit(long tokenUsage, String model) {
        return false; // safe default; CompactService overrides with real logic
    }

    /**
     * Returns true if the conversation messages have reached or exceeded the hard blocking limit.
     */
    default boolean isAtBlockingLimit(List<Message> messages, String model) {
        return false; // safe default; CompactService overrides with real logic
    }

    /** Whether unattended compaction recovery is enabled for this session. */
    default boolean isAutoCompactEnabled() {
        return false;
    }

    /**
     * Whether provider-error-triggered reactive compaction is enabled.
     */
    default boolean isReactiveCompactEnabled() {
        return false;
    }

    /**
     * Partial compaction: summarize either the messages BEFORE the pivot ("up_to") or the messages FROM
     * the pivot onward ("from"), keeping the other slice intact.
     */
    default List<Message> partialCompactAndAssemble(
            List<Message> messages, int pivotIndex, String direction, String feedback) {
        throw new UnsupportedOperationException(
            "partial compaction not supported by this MessageCompactor");
    }

    /**
     * Partial compact with prompt instructions kept separate from the user feedback persisted in
     * rewind metadata. Implementations that do not need that distinction retain the legacy path.
     */
    default PartialCompactOutput partialCompact(
            List<Message> messages, int pivotIndex, String direction,
            String feedback, String customInstructions) {
        return new PartialCompactOutput(
            partialCompactAndAssemble(messages, pivotIndex, direction, feedback),
            Usage.EMPTY, null);
    }

    /**
     * Estimates the token count of {@code messages}.
     */
    default long estimateTokenCount(List<Message> messages) {
        return TokenEstimator.getInstance().estimateTokenCount(messages);
    }


    default long estimatePostCompactTokenCount(List<Message> messages) {
        return TokenEstimator.getInstance().estimatePostCompactTokenCount(messages);
    }

    /**
     * Canonical full-context count used immediately before compaction.
     */
    default long contextTokenCount(List<Message> messages, String model) {
        return TokenEstimator.getInstance().tokenCountWithEstimation(messages, model, 4);
    }

    /**
     * Auto-compact trigger exposed to context-usage consumers.
     */
    default long getAutoCompactThreshold(String model) {
        return 167_000L;
    }


    default String getAutoCompactSource(String model) {
        return "auto";
    }
}
