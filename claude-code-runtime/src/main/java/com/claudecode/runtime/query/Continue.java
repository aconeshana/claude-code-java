package com.claudecode.runtime.query;


/**
 * Records why the previous loop iteration triggered another iteration.
 */
sealed interface Continue permits
    Continue.ToolUse,
    Continue.Compact,
    Continue.MaxOutputTokensEscalate,
    Continue.MaxOutputTokensRecovery,
    Continue.PromptTooLongRecovery,
    Continue.StopHookReentry {

    /** Loop continued because the model emitted tool_use blocks. */
    record ToolUse(int toolCount) implements Continue {}

    /** Loop continued after a compact operation condensed the context. */
    record Compact() implements Continue {}

    /**
     * Loop continued after a first-time proactive escalation of
     * {@code max_output_tokens} (e.g. 8k to 64k), before any premature stop
     * has actually been observed. Distinct from {@link MaxOutputTokensRecovery},
     * which retries after the escalated limit was itself hit — the two have
     * different reset semantics (escalate happens once; recovery has a retry
     * counter used to bail out after repeated failures).
     */
    record MaxOutputTokensEscalate() implements Continue {}


    record MaxOutputTokensRecovery(int recoveryAttempt) implements Continue {}

    /**
     * Loop continued after a prompt_too_long recovery (context collapse or reactive compact).
     */
    record PromptTooLongRecovery() implements Continue {}

    /**
     * Loop re-entered after stop hooks requested continuation.
     */
    record StopHookReentry() implements Continue {}
}
