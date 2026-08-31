package com.claudecode.services.hooks;

import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of executing a hook command.
 */
public sealed interface HookResult
    permits HookResult.Allow, HookResult.Block, HookResult.Message,
            HookResult.PreventContinuation, HookResult.Skip,
            HookResult.Structured, HookResult.Decorated,
            HookResult.ConditionMet, HookResult.ConditionNotMet,
            HookResult.ConditionImpossible {

    /** Canonical allow result with no additional context. */
    static Allow allow() {
        return HookResultCache.ALLOW;
    }

    /** Canonical result for a hook that did not run or did not match. */
    static Skip skip() {
        return HookResultCache.SKIP;
    }

    /** Allow the operation to continue, optionally with additional context. */
    record Allow(Optional<String> additionalContext) implements HookResult {
        public Allow() { this(Optional.empty()); }
        public Allow(String context) { this(Optional.of(context)); }
    }

    /** Block the operation with a reason. */
    record Block(String reason, Optional<String> hookName) implements HookResult {
        public Block(String reason) { this(reason, Optional.empty()); }
    }

    /** Produce a message to inject into conversation history. */
    record Message(String content) implements HookResult {}

    /**
     * Hook returned {@code continue: false} — the query loop must terminate immediately, optionally
     * with a hook-supplied {@code stopReason} shown to the user.
     */
    record PreventContinuation(Optional<String> stopReason) implements HookResult {
        public PreventContinuation(String stopReason) { this(Optional.ofNullable(stopReason)); }
    }

    /** Validated event-specific output retained for the lifecycle consumer. */
    record Structured(JsonNode output, Optional<String> additionalContext) implements HookResult {
        public Structured {
            output = output == null ? null : output.deepCopy();
            additionalContext = additionalContext == null ? Optional.empty() : additionalContext;
        }
    }

    /** Generic JSON fields that accompany, but do not replace, the hook decision. */
    record Effects(Optional<String> systemMessage,
                   Optional<String> terminalSequence,
                   Optional<String> successOutput,
                   boolean suppressOutput,
                   String validationError) {
        public Effects {
            systemMessage = systemMessage == null ? Optional.empty() : systemMessage;
            terminalSequence = terminalSequence == null ? Optional.empty() : terminalSequence;
            successOutput = successOutput == null ? Optional.empty() : successOutput;
        }

        boolean isEmpty() {
            return systemMessage.isEmpty() && terminalSequence.isEmpty() && successOutput.isEmpty()
                && !suppressOutput && validationError == null;
        }
    }

    /** A normal hook decision decorated with generic user/terminal effects. */
    record Decorated(HookResult result, Effects effects) implements HookResult {
        public Decorated {
            if (result instanceof Decorated) {
                throw new IllegalArgumentException("nested hook decorations are not supported");
            }
            if (effects == null) {
                effects = new Effects(Optional.empty(), Optional.empty(), Optional.empty(),
                    false, null);
            }
        }
    }

    /** Skip — hook didn't match or produced no output. */
    record Skip() implements HookResult {}

    /** Stop/SubagentStop prompt condition was satisfied. */
    record ConditionMet(String reason) implements HookResult { }

    /** Stop/SubagentStop prompt condition was not satisfied and must re-enter. */
    record ConditionNotMet(String condition, String reason) implements HookResult { }

    /** Stop/SubagentStop prompt condition can never be satisfied this session. */
    record ConditionImpossible(String reason) implements HookResult { }
}

final class HookResultCache {
    static final HookResult.Allow ALLOW = new HookResult.Allow();
    static final HookResult.Skip SKIP = new HookResult.Skip();

    private HookResultCache() {}
}
