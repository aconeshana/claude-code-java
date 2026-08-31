package com.claudecode.runtime.query;


/**
 * Terminal return value of the query loop, indicating why the loop ended.
 */
sealed interface Terminal permits
    Terminal.Normal,
    Terminal.MaxTurns,
    Terminal.MaxStructuredOutputRetries,
    Terminal.MaxBudget,
    Terminal.PromptTooLong,
    Terminal.StreamError,
    Terminal.StopHookPrevented,
    Terminal.HookStopped,
    Terminal.Aborted {

    /** Loop completed normally (model returned end_turn with no pending tool use). */
    record Normal() implements Terminal {}

    /** Loop stopped because {@code maxTurns} was reached. */
    record MaxTurns(int turns) implements Terminal {}

    /** Loop stopped because the structured-output retry limit was exceeded. */
    record MaxStructuredOutputRetries(int retries) implements Terminal {}

    /** Loop stopped because the USD budget was exhausted. */
    record MaxBudget(double costUsd) implements Terminal {}

    /**
     * Loop stopped before calling the model because the context window is nearly full and auto-compact
     * is off or circuit-broken (blocking-limit guard).
     */
    record PromptTooLong() implements Terminal {}

    /** Loop stopped because creating or consuming the model stream threw. */
    record StreamError(String message, Throwable cause) implements Terminal {}


    record StopHookPrevented(String stopReason) implements Terminal {}


    record HookStopped(String stopReason) implements Terminal {}

    /**
     * Loop stopped because the abort signal was triggered.
     */
    record Aborted(String reason) implements Terminal {}
}
