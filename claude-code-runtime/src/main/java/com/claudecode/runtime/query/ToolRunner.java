package com.claudecode.runtime.query;


import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs the tool calls requested in one assistant turn.
 */
interface ToolRunner {

    /**
     * Runs every tool-use block from one assistant turn, in order.
     *
     * @param toolUseBlocks       the {@code ToolUseBlock} entries from the assistant message
     *                            (caller filters to just the tool-use blocks)
     * @param engine              the query engine (message list, hooks, permission callback, etc.)
     * @param structuredOutputMode reserved so a future runner can special-case
     *                            structured-output tool calls without changing this signature again
     * @param emit                sink for {@code SDKMessage}s the runner wants streamed to the caller
     * @return whether any tool errored, plus the last error and the turn it happened on —
     *         matches the three loop-local variables ({@code errorDuringExecution},
     *         {@code lastError}, {@code errorWatermarkTurn}) this replaces
     */
    /**
     * Runs every tool-use block from one assistant turn, in order.
     *
     * @param toolUseBlocks       the {@code ToolUseBlock} entries from the assistant message
     *                            (caller filters to just the tool-use blocks)
     * @param engine              the query engine (message list, hooks, permission callback, etc.)
     * @param structuredOutputMode reserved so a future runner can special-case
     *                            structured-output tool calls without changing this signature again
     * @param emit                sink for {@code SDKMessage}s the runner wants streamed to the caller
     * @return whether any tool errored, plus the last error and the turn it happened on —
     *         matches the three loop-local variables ({@code errorDuringExecution},
     *         {@code lastError}, {@code errorWatermarkTurn}) this replaces
     */
    default RunOutcome run(
        List<ContentBlock> toolUseBlocks,
        DefaultQuerySession engine,
        boolean structuredOutputMode,
        int currentTurn,
        Consumer<SDKMessage> emit
    ) {
        return run(toolUseBlocks, engine, structuredOutputMode, currentTurn, emit, null);
    }


    RunOutcome run(
        List<ContentBlock> toolUseBlocks,
        DefaultQuerySession engine,
        boolean structuredOutputMode,
        int currentTurn,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid
    );

    /**
     * Runs every tool-use block with mid-turn steer tracking: the tracker's
     * executing-tool set is driven from each tool's started/completed transitions.
     * A {@code null} tracker disables steer tracking without changing behavior.
     */
    default RunOutcome run(
        List<ContentBlock> toolUseBlocks,
        DefaultQuerySession engine,
        boolean structuredOutputMode,
        int currentTurn,
        Consumer<SDKMessage> emit,
        String sourceAssistantUuid,
        InterruptibleToolTracker tracker
    ) {
        // Trackers ride along transparently for runners that don't implement them.
        return run(toolUseBlocks, engine, structuredOutputMode, currentTurn, emit, sourceAssistantUuid);
    }


    static ToolRunner resolve() {
        return new ConcurrentToolRunner();
    }

    /**
     * Outcome of running one turn's tool calls.
     */
    record RunOutcome(boolean errorDuringExecution, Exception lastError, int errorWatermarkTurn,
                      boolean preventContinuation, String stopReason, JsonNode structuredOutput) {
        static final RunOutcome NO_ERROR = new RunOutcome(false, null, 0, false, null, null);

        /** Compat constructor predating {@code structuredOutput}. */
        RunOutcome(boolean errorDuringExecution, Exception lastError, int errorWatermarkTurn,
                   boolean preventContinuation, String stopReason) {
            this(errorDuringExecution, lastError, errorWatermarkTurn, preventContinuation, stopReason, null);
        }

        /** Compat constructor predating {@code preventContinuation}. */
        RunOutcome(boolean errorDuringExecution, Exception lastError, int errorWatermarkTurn) {
            this(errorDuringExecution, lastError, errorWatermarkTurn, false, null, null);
        }
    }
}
