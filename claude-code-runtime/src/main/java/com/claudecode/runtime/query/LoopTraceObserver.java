package com.claudecode.runtime.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lightweight, package-private observation seam for {@link QueryLoop} control-flow transitions.
 *
 * <p>In production {@link #NOOP} is used with {@link #isEnabled()} returning {@code false},
 * avoiding any snapshot allocation. In conformance tests, a recording observer captures each
 * transition as JSONL so the Lean 4 {@code trace-verifier} executable can validate that the
 * JVM runtime refines {@code formal/AgentLoop/Step.lean}.
 */
interface LoopTraceObserver {

    LoopTraceObserver NOOP = new LoopTraceObserver() {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void onTransition(ConfigSnapshot config,
                                 StateSnapshot before,
                                 EventSnapshot event,
                                 OutcomeSnapshot actual) {
            // no-op
        }
    };

    default boolean isEnabled() {
        return true;
    }

    void onTransition(ConfigSnapshot config,
                      StateSnapshot before,
                      EventSnapshot event,
                      OutcomeSnapshot actual);

    record ConfigSnapshot(
        int maxTurns,
        int stopHookBlockCap,
        int maxOutputTokensLimit,
        boolean allowMaxTokensEscalation,
        boolean requireStructuredOutput,
        int maxStructuredOutputRetries
    ) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("maxTurns", Math.max(0, maxTurns));
            n.put("stopHookBlockCap", Math.max(0, stopHookBlockCap));
            n.put("maxOutputTokensLimit", Math.max(0, maxOutputTokensLimit));
            n.put("allowMaxTokensEscalation", allowMaxTokensEscalation);
            n.put("requireStructuredOutput", requireStructuredOutput);
            n.put("maxStructuredOutputRetries", Math.max(0, maxStructuredOutputRetries));
            return n;
        }
    }

    record StateSnapshot(
        int loopTurns,
        int recoveryCount,
        boolean maxTokensOverrideSet,
        boolean stopHookActive,
        int stopHookBlockingCount,
        boolean hasStructuredOutput,
        int structuredOutputCalls,
        boolean hasAttemptedReactiveCompact,
        Continue lastContinue
    ) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("loopTurns", loopTurns);
            n.put("recoveryCount", recoveryCount);
            n.put("maxTokensOverrideSet", maxTokensOverrideSet);
            n.put("stopHookActive", stopHookActive);
            n.put("stopHookBlockingCount", stopHookBlockingCount);
            n.put("hasStructuredOutput", hasStructuredOutput);
            n.put("structuredOutputCalls", structuredOutputCalls);
            n.put("hasAttemptedReactiveCompact", hasAttemptedReactiveCompact);
            if (lastContinue == null) {
                n.putNull("lastContinue");
            } else {
                n.set("lastContinue", continueToJson(mapper, lastContinue));
            }
            return n;
        }
    }

    sealed interface EventSnapshot {
        record Aborted() implements EventSnapshot {}
        record BudgetExceeded() implements EventSnapshot {}
        record BlockingLimitReached() implements EventSnapshot {}
        record StreamError() implements EventSnapshot {}
        record ReactiveCompactAttempt(boolean succeeded) implements EventSnapshot {}
        record ApiMaxOutputTokens() implements EventSnapshot {}
        record ToolUse(int count, boolean hookPrevented, boolean producedStructuredOutput,
                       int structuredOutputCallsThisQuery) implements EventSnapshot {}
        record NaturalEndTurn(boolean stopHookPrevent, boolean stopHookBlocking) implements EventSnapshot {}

        default ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            switch (this) {
                case Aborted _ -> n.put("kind", "aborted");
                case BudgetExceeded _ -> n.put("kind", "budget_exceeded");
                case BlockingLimitReached _ -> n.put("kind", "blocking_limit_reached");
                case StreamError _ -> n.put("kind", "stream_error");
                case ReactiveCompactAttempt(boolean succeeded) -> {
                    n.put("kind", "reactive_compact_attempt");
                    n.put("succeeded", succeeded);
                }
                case ApiMaxOutputTokens _ -> n.put("kind", "api_max_output_tokens");
                case ToolUse(int count, boolean hookPrevented, boolean producedSO, int soCalls) -> {
                    n.put("kind", "tool_use");
                    n.put("count", count);
                    n.put("hookPrevented", hookPrevented);
                    n.put("producedStructuredOutput", producedSO);
                    n.put("structuredOutputCallsThisQuery", soCalls);
                }
                case NaturalEndTurn(boolean prevent, boolean blocking) -> {
                    n.put("kind", "natural_end_turn");
                    n.put("stopHookPrevent", prevent);
                    n.put("stopHookBlocking", blocking);
                }
            }
            return n;
        }
    }

    sealed interface OutcomeSnapshot {
        record Next(StateSnapshot state, Continue reason) implements OutcomeSnapshot {}
        record Done(Terminal terminal) implements OutcomeSnapshot {}

        default ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            switch (this) {
                case Next(StateSnapshot state, Continue reason) -> {
                    n.put("kind", "next");
                    n.set("state", state.toJson(mapper));
                    n.set("reason", continueToJson(mapper, reason));
                }
                case Done(Terminal terminal) -> {
                    n.put("kind", "done");
                    n.set("terminal", terminalToJson(mapper, terminal));
                }
            }
            return n;
        }
    }

    static ObjectNode continueToJson(ObjectMapper mapper, Continue reason) {
        ObjectNode n = mapper.createObjectNode();
        switch (reason) {
            case Continue.ToolUse(int toolCount) -> {
                n.put("kind", "tool_use");
                n.put("toolCount", toolCount);
            }
            case Continue.Compact _ -> n.put("kind", "compact");
            case Continue.MaxOutputTokensEscalate _ -> n.put("kind", "max_output_tokens_escalate");
            case Continue.MaxOutputTokensRecovery(int attempt) -> {
                n.put("kind", "max_output_tokens_recovery");
                n.put("attempt", attempt);
            }
            case Continue.PromptTooLongRecovery _ -> n.put("kind", "prompt_too_long_recovery");
            case Continue.StopHookReentry _ -> n.put("kind", "stop_hook_reentry");
        }
        return n;
    }

    static ObjectNode terminalToJson(ObjectMapper mapper, Terminal terminal) {
        ObjectNode n = mapper.createObjectNode();
        switch (terminal) {
            case Terminal.Normal _ -> n.put("kind", "normal");
            case Terminal.MaxTurns(int turns) -> {
                n.put("kind", "max_turns");
                n.put("turns", turns);
            }
            case Terminal.MaxStructuredOutputRetries(int retries) -> {
                n.put("kind", "max_structured_output_retries");
                n.put("retries", retries);
            }
            case Terminal.MaxBudget _ -> n.put("kind", "max_budget");
            case Terminal.PromptTooLong _ -> n.put("kind", "prompt_too_long");
            case Terminal.StreamError _ -> n.put("kind", "stream_error");
            case Terminal.StopHookPrevented _ -> n.put("kind", "stop_hook_prevented");
            case Terminal.HookStopped _ -> n.put("kind", "hook_stopped");
            case Terminal.Aborted _ -> n.put("kind", "aborted");
        }
        return n;
    }
}
