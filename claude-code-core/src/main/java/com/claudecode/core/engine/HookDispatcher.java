package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.message.Message;

import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Optional;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;

/**
 * Dispatcher interface for lifecycle hooks invoked by the query engine.
 * Defined in core to avoid a circular dependency with the services module.
 * The concrete implementation lives in
 * {@code com.claudecode.services.hooks.HookEngine}.
 * <p>
 * Methods are no-throw: implementations should swallow and log internal
 * errors so that hook failures never poison the main query loop. The only
 * signal back to the engine is the boolean return on
 * {@link #dispatchPreToolUse}, which controls whether the tool call should
 * proceed.
 */
public interface HookDispatcher {

    /**
     * Marker for a parsed set of hooks contributed by one prompt/skill invocation.
     * The stable engine contract owns the lifecycle; concrete parsing and execution
     * remain in the services implementation.
     */
    interface InvocationHooks {
        boolean isEmpty();
    }

    /** Install hooks that live only for the current prompt invocation. */
    default void installInvocationHooks(InvocationHooks hooks, Path sourceRoot) { }

    /** Clear hooks installed through {@link #installInvocationHooks}. */
    default void clearInvocationHooks() { }


    record ActiveGoal(String condition, int iterations, long setAtMillis,
                      long tokensAtStart, String lastReason) { }

    enum GoalTransitionKind { PENDING, MET, FAILED }

    /** Validated event-specific JSON returned from {@code hookSpecificOutput}. */
    record HookSpecificOutput(String hookEventName, JsonNode fields) {
        public HookSpecificOutput {
            fields = fields == null ? null : fields.deepCopy();
        }
    }

    /** One Stop-evaluator transition waiting for QueryLoop to persist/render. */
    record GoalTransition(GoalTransitionKind kind, String condition, String reason,
                          int iterations, long durationMs, long tokens) { }

    /** Install/replace the session-scoped goal Stop prompt hook. */
    default boolean setGoal(String condition, long tokensAtStart) { return false; }

    /** Remove the goal hook, returning its condition, or {@code null}. */
    default String clearGoal() { return null; }

    /** Current goal state used by {@code /goal} status surfaces. */
    default Optional<ActiveGoal> activeGoal() { return Optional.empty(); }

    /** Restore the latest unresolved goal from a resumed transcript. */
    default void restoreGoalFromTranscript(List<Message> messages, long tokensAtStart) { }

    /** Consume the latest Stop-evaluator state change exactly once. */
    default Optional<GoalTransition> consumeGoalTransition() { return Optional.empty(); }

    /**
     * Consume transcript-only hook diagnostics accumulated during the latest
     * lifecycle dispatch. These messages are persisted and surfaced through
     * the SDK attachment channel, but attachment rendering keeps them out of
     * subsequent model requests.
     */
    default List<Message> consumeHookMessages() { return List.of(); }

    /**
     * Outcome surfaced by hook execution.
     */
    record HookOutcome(boolean proceed,
                       String additionalContext,
                       List<String> blockingErrors,
                       boolean preventContinuation,
                       String stopReason,
                       String userDisplayMessage,
                       List<String> additionalContexts,
                       List<HookSpecificOutput> specificOutputs) {

        public static final HookOutcome PROCEED = new HookOutcome(true, null, List.of());
        public static final HookOutcome BLOCK   = new HookOutcome(false, null, List.of());

        public HookOutcome {
            if (blockingErrors == null) blockingErrors = List.of();
            if (additionalContexts == null) {
                additionalContexts = StringUtils.isBlank(additionalContext)
                    ? List.of() : List.of(additionalContext);
            } else {
                additionalContexts = List.copyOf(additionalContexts);
            }
            if ((StringUtils.isBlank(additionalContext))
                    && !additionalContexts.isEmpty()) {
                additionalContext = String.join("\n", additionalContexts);
            }
            specificOutputs = specificOutputs == null
                ? List.of() : List.copyOf(specificOutputs);
        }

        /** Compat constructor predating {@code preventContinuation}/{@code stopReason}. */
        public HookOutcome(boolean proceed, String additionalContext, List<String> blockingErrors) {
            this(proceed, additionalContext, blockingErrors, false, null, null, null, null);
        }

        /** Compat constructor predating {@code userDisplayMessage}. */
        public HookOutcome(boolean proceed, String additionalContext, List<String> blockingErrors,
                           boolean preventContinuation, String stopReason) {
            this(proceed, additionalContext, blockingErrors,
                preventContinuation, stopReason, null, null, null);
        }

        /** Compat constructor predating per-hook {@code additionalContexts}. */
        public HookOutcome(boolean proceed, String additionalContext, List<String> blockingErrors,
                           boolean preventContinuation, String stopReason,
                           String userDisplayMessage) {
            this(proceed, additionalContext, blockingErrors,
                preventContinuation, stopReason, userDisplayMessage, null, null);
        }

        /** Compat constructor predating event-specific structured output. */
        public HookOutcome(boolean proceed, String additionalContext, List<String> blockingErrors,
                           boolean preventContinuation, String stopReason,
                           String userDisplayMessage, List<String> additionalContexts) {
            this(proceed, additionalContext, blockingErrors, preventContinuation,
                stopReason, userDisplayMessage, additionalContexts, null);
        }

        public boolean hasAdditionalContext() {
            return StringUtils.isNotBlank(additionalContext);
        }

        public boolean hasBlockingErrors() {
            return !blockingErrors.isEmpty();
        }

        public boolean hasUserDisplayMessage() {
            return StringUtils.isNotBlank(userDisplayMessage);
        }

        public Optional<JsonNode> specificOutput(String eventName) {
            for (int i = specificOutputs.size() - 1; i >= 0; i--) {
                HookSpecificOutput output = specificOutputs.get(i);
                if (Strings.CS.equals(eventName, output.hookEventName())) {
                    return Optional.ofNullable(output.fields()).map(JsonNode::deepCopy);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Fired right before a tool is invoked. Implementations may run user-
     * configured hooks (Bash, HTTP, prompt-based) and decide whether to
     * block the call.
     *
     * @return {@code true} if the call may proceed, {@code false} if a hook
     *         vetoed it. When false, the engine returns an error result for
     *         the tool call and does NOT execute the tool.
     */
    boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId);

    /**
     * Rich variant of {@link #dispatchPreToolUse}: returns both the
     * proceed/block decision and any {@code additionalContext} the hooks
     * want injected as a {@code <system-reminder>} user message before the
     * next API call. Default delegates to {@link #dispatchPreToolUse} and
     * surfaces no extra context, preserving backward compatibility for
     * implementations that don't override.
     */
    default HookOutcome dispatchPreToolUseWithOutcome(String toolName, JsonNode input, String toolUseId) {
        return dispatchPreToolUse(toolName, input, toolUseId) ? HookOutcome.PROCEED : HookOutcome.BLOCK;
    }

    /**
     * Fired immediately after a tool finishes (success or failure). Output
     * may be null for non-JSON results.
     */
    void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId);

    /**
     * Rich variant of {@link #dispatchPostToolUse}: returns any
     * {@code additionalContext} for system-reminder injection.
     */
    default HookOutcome dispatchPostToolUseWithOutcome(String toolName, JsonNode input, JsonNode output, String toolUseId) {
        dispatchPostToolUse(toolName, input, output, toolUseId);
        return HookOutcome.PROCEED;
    }

    /**
     * Fired after a tool fails, instead of {@link #dispatchPostToolUseWithOutcome}.
     */
    default HookOutcome dispatchPostToolUseFailureWithOutcome(
            String toolName, JsonNode input, String toolUseId,
            String error, boolean isInterrupt) {
        return HookOutcome.PROCEED;
    }

    /** Fired exactly once after every tool call in the assistant batch resolves. */
    default HookOutcome dispatchPostToolBatchWithOutcome(JsonNode toolCalls) {
        return HookOutcome.PROCEED;
    }

    /** Fired before an ASK-level permission dialog is shown. */
    default HookOutcome dispatchPermissionRequestWithOutcome(
            String toolName, JsonNode input, String toolUseId) {
        return HookOutcome.PROCEED;
    }

    /** Fired after a permission request is denied. */
    default HookOutcome dispatchPermissionDeniedWithOutcome(
            String toolName, JsonNode input, String toolUseId, String reason) {
        return HookOutcome.PROCEED;
    }

    /** Fired when an interactive/user-attention notification is emitted. */
    default void dispatchNotification(String message, String title, String notificationType) { }

    /** Fired when the user submits a prompt at the REPL. */
    void dispatchUserPromptSubmit(String prompt);


    default HookOutcome dispatchUserPromptSubmitWithOutcome(String prompt) {
        dispatchUserPromptSubmit(prompt);
        return HookOutcome.PROCEED;
    }

    /** Fired after a typed slash/MCP prompt command has expanded, before submission. */
    default HookOutcome dispatchUserPromptExpansionWithOutcome(
            String expansionType, String commandName, String commandArgs,
            String commandSource, String originalPrompt) {
        return HookOutcome.PROCEED;
    }

    /** Fired by setup flows such as {@code /init}; blocking results are advisory. */
    default HookOutcome dispatchSetupWithOutcome(String trigger) {
        return HookOutcome.PROCEED;
    }

    /** Display-only transformation of a streamed assistant text delta. */
    default HookOutcome dispatchMessageDisplayWithOutcome(
            String turnId, String messageId, int index, boolean finalDelta, String delta) {
        return HookOutcome.PROCEED;
    }

    /** Fired once when the session starts (engine construction time). */
    void dispatchSessionStart(String trigger);

    /** Fired after the main session's foreground shell changes its physical cwd. */
    default void dispatchCwdChanged(String oldCwd, String newCwd) {
        dispatchCwdChangedWithOutcome(oldCwd, newCwd);
    }

    /** Rich CwdChanged result, including dynamic FileChanged watch paths. */
    default HookOutcome dispatchCwdChangedWithOutcome(String oldCwd, String newCwd) {
        return HookOutcome.PROCEED;
    }

    /** Fired by the session-scoped filesystem watcher after a stable file event. */
    default HookOutcome dispatchFileChangedWithOutcome(String filePath, String fileEvent) {
        return HookOutcome.PROCEED;
    }

    /** Fired after a teammate's Stop/TaskCompleted hooks pass, before it reports idle. */
    default HookOutcome dispatchTeammateIdleWithOutcome(
            String teammateName, String teamName) {
        return HookOutcome.PROCEED;
    }

    /** Rich TaskCompleted boundary used by in-process teammate idle processing. */
    default HookOutcome dispatchTaskCompletedWithOutcome(
            String taskId, String subject, String description) {
        return HookOutcome.PROCEED;
    }

    /**
     * Rich variant of {@link #dispatchSessionStart}: returns any {@code additionalContext} hooks want
     * injected into the fresh conversation.
     */
    default HookOutcome dispatchSessionStartWithOutcome(String trigger) {
        dispatchSessionStart(trigger);
        return HookOutcome.PROCEED;
    }

    /**
     * Fired before a child Agent query starts. The returned contexts are
     * prepended to that child's initial user turn as a SubagentStart hook
     * reminder. Default no-op keeps older dispatcher implementations source
     * compatible.
     */
    default HookOutcome dispatchSubAgentStartWithOutcome(String agentId, String agentType) {
        return HookOutcome.PROCEED;
    }

    /** Fired right before Claude concludes its response for the current turn. */
    void dispatchStop(String reason);

    /**
     * Rich variant of {@link #dispatchStop}: returns blocking errors and additional context.
     */
    default HookOutcome dispatchStopWithOutcome(String reason) {
        dispatchStop(reason);
        return HookOutcome.PROCEED;
    }

    /**
     * Stop dispatch carrying the {@code stop_hook_active} flag.
     */
    default HookOutcome dispatchStopWithOutcome(String reason, boolean stopHookActive) {
        return dispatchStopWithOutcome(reason);
    }

    /**
     * Fired INSTEAD of Stop when the turn ended on an API/loop failure (stream error, prompt-too-long,
     * blocking limit).
     */
    default void dispatchStopFailure(String reason) {}

    /**
     * Fired once when the session actually terminates — {@code /clear}, {@code /logout}, process exit,
     * or {@code /resume} switching away from the outgoing session.
     */
    default void dispatchSessionEnd(String reason) {
        dispatchStop(reason);
    }

    /**
     * Fired just before compact hooks run and before LLM summarisation.
     */
    default void dispatchPreCompact(String trigger, String customInstructions, long preTokenCount) {}

    /**
     * Rich variant of {@link #dispatchPreCompact}: returns any {@code additionalContext} hooks want
     * merged into the summarization instructions.
     */
    default HookOutcome dispatchPreCompactWithOutcome(String trigger, String customInstructions, long preTokenCount) {
        dispatchPreCompact(trigger, customInstructions, preTokenCount);
        return HookOutcome.PROCEED;
    }

    /**
     * Fired after the compacted conversation has been written back.
     */
    default void dispatchPostCompact(String trigger, String compactSummary, long postTokenCount) {}


    default HookOutcome dispatchPostCompactWithOutcome(String trigger, String compactSummary, long postTokenCount) {
        dispatchPostCompact(trigger, compactSummary, postTokenCount);
        return HookOutcome.PROCEED;
    }

    /**
     * Fired once per memory file loaded into the system-prompt tail block.
     */
    default void dispatchInstructionsLoaded(String filePath, String memoryType,
                                             String loadReason, List<String> globs) {}

    /**
     * Polls the engine's {@code AsyncHookRegistry} for background (output-driven / config-async) hooks
     * that have since completed, returning each one's deferred sync response.
     */
    default List<AsyncHookResponse> checkForAsyncHookResponses() {
        return List.of();
    }

    /**
     * Removes already-delivered async hook entries from the registry by {@code processId} (called by
     * the attachment provider after it has surfaced them).
     */
    default void removeDeliveredAsyncHooks(List<String> processIds) {}

    /**
     * Flushes all pending async hooks on shutdown: completed ones are finalized, still-running ones are
     * force-killed.
     */
    default void finalizePendingAsyncHooks() {}

    /**
     * When true, async hooks are NOT backgrounded — they run synchronously so the caller blocks until
     * completion (used on the exit path to avoid orphaning in-flight hook processes).
     */
    default void setForceSyncExecution(boolean value) {}
}
