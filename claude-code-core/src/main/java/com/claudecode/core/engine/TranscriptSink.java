package com.claudecode.core.engine;

import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsEvent;
import java.util.List;

/**
 * Sink for persisting conversation messages to a session transcript.
 */
public interface TranscriptSink {

    /** Append a single message to the active session's transcript. */
    void record(String sessionId, Message message);

    /**
     * Rebase subsequent transcript appends onto the retained conversation prefix after rewind.
     * Implementations should preserve abandoned rows as an alternate branch rather than deleting
     * them, while ensuring the next recorded message points at the retained tail.
     */
    default void rewindConversation(String sessionId, List<Message> retainedMessages) {}

    /**
     * Remove a previously persisted row retracted by a tombstone. Implementations
     * without mutable transcript storage may ignore the callback.
     */
    default void remove(String sessionId, String messageUuid) {}

    /**
     * Append an SDK queue lifecycle entry before the corresponding user turn.
     * Implementations that only persist conversation messages may ignore it.
     */
    default void recordQueueOperation(String sessionId, String operation, String content) {}

    /** Append one non-chain whole-session metrics event to the active JSONL. */
    default void recordSessionMetrics(String sessionId, SessionMetricsEvent event) {}

    /**
     * Establish the turn-scoped prompt identity before any user rows are recorded.
     * Interactive REPL submissions use {@code typed}; SDK/headless queue drains use
     * {@code sdk}. Every user row in the turn, including tool results, shares the id.
     */
    default void recordPromptStart(String sessionId, String promptSource) {}

    /**
     * Marks a fresh interactive transcript for the metadata re-append performed when the first
     * conversation entry materializes the session file.
     */
    default void prepareSessionMaterialization(String sessionId) {}

    /**
     * Append the SDK {@code last-prompt} sentinel after the turn has drained.
     * Implementations may resolve the leaf UUID from their own ordered chain state.
     */
    default void recordLastPrompt(String sessionId, String prompt) {}

    /** Append a model-querying prompt command as the visible last prompt.
     * Local slash commands remain leaf-only through {@link #recordLastPrompt}. */
    default void recordQueriedCommandLastPrompt(String sessionId, String prompt) {
        recordLastPrompt(sessionId, prompt);
    }

    /**
     * Append the current prompt metadata immediately before a successful compact boundary.
     * Unlike {@link #recordLastPrompt(String, String)}, this is not a turn-completion signal:
     * implementations must preserve the active prompt identity so the compact-summary user row
     * remains part of the same submitted turn.
     */
    default void recordPreCompactLastPrompt(String sessionId, String prompt) {}


    default void prepareManualCompactMetadata(String sessionId) {}

    /**
     * Same transition, keyed to the generated {@code <command-name>/compact}
     * row so the refreshed file-history snapshot points at the local command.
     */
    default void prepareManualCompactMetadata(String sessionId, String commandMessageId) {
        prepareManualCompactMetadata(sessionId);
    }

    /**
     * Queue the automatic-compaction metadata checkpoint before its boundary.
     * Unlike the manual transition, this keeps the current turn's prompt id
     * and does not create a slash-command prompt identity.
     */
    default void prepareAutoCompactMetadata(String sessionId, String currentPrompt) {
        recordPreCompactLastPrompt(sessionId, currentPrompt);
    }

    /**
     * Cache the latest interactive prompt without appending a JSONL row yet.
     */
    default void cacheLastPrompt(String sessionId, String prompt) {}

    /** Append the currently cached interactive {@code last-prompt}, if any. */
    default void flushCachedLastPrompt(String sessionId) {}

    /**
     * Append resumed-session mode metadata after {@code last-prompt}.
     * Implementations should preserve invocation order with other metadata writes.
     */
    default void recordMode(String sessionId, String mode) {}

    /**
     * Materialize the mode restored at process startup before recovery messages.
     * A subsequent compact metadata refresh must not duplicate this already-fresh row.
     */
    default void recordRestoredMode(String sessionId, String mode) {
        recordMode(sessionId, mode);
    }

    /** Append the generated first-turn session title metadata. */
    default void recordAiTitle(String sessionId, String title) {}

    /**
     * Append a session permission-mode transition. Interactive ExitPlanMode
     * writes this after {@code last-prompt} when approval changes plan → default
     * (or another selected execution mode).
     */
    default void recordPermissionMode(String sessionId, String permissionMode) {}

    /**
     * Cache the invocation's effective permission mode without appending a row.
     * Manual compact uses it when refreshing metadata after its pre-compact
     * last-prompt checkpoint.
     */
    default void cachePermissionMode(String sessionId, String permissionMode) {}

    /**
     * Persist message-level tool-result replacement decisions for resume and
     * prompt-cache stability. Implementations that do not persist metadata may
     * ignore this callback.
     */
    default void recordContentReplacements(String sessionId,
                                            List<ToolResultBudget.Replacement> replacements) {}

    /** Whether the resumed JSONL already contains a persisted session mode. */
    default boolean hasPersistedMode(String sessionId) { return false; }

    /** Whether the resumed JSONL already contains a persisted permission mode. */
    default boolean hasPersistedPermissionMode(String sessionId) { return false; }

    /**
     * Wait for already-enqueued transcript writes to become durable before a
     * short-lived headless process exits. Interactive callers normally keep
     * running and can retain the fire-and-forget behavior.
     *
     * @return {@code true} when the queue drained before the timeout
     */
    default boolean awaitPendingWrites(String sessionId, long timeoutMillis) { return true; }
}
