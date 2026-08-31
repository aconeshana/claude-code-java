package com.claudecode.runtime.query;


import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;

import java.util.Iterator;
import java.util.List;

/**
 * I/O dependencies for the query loop, injectable for testing.
 */
interface QueryDeps {

    /** Calls the model and returns the streaming event iterator. */
    Iterator<StreamingClient.StreamingEvent> callModel(StreamingClient.StreamRequest request);

    /** Truncates long tool outputs (microcompact). */
    MessageCompactor.MicrocompactResult microcompact(List<Message> messages);

    /** Returns whether auto-compaction should be triggered for the given messages. */
    boolean shouldAutoCompact(List<Message> messages, String model, String querySource);

    /**
     * Runs one auto-compaction attempt, applying the consecutive-failure circuit breaker.
     */
    AutoCompactResult autocompact(List<Message> messages, String model, String querySource,
                                  AutoCompactTrackingState tracking, String customInstructions,
                                  long snipTokensFreed);

    /**
     * Compacts after the provider has rejected the actual request as
     * {@code prompt_too_long}. Unlike proactive autocompact this must not repeat
     * the local token-estimate threshold check: provider-side tokenization and
     * protocol overhead are authoritative and may exceed the window while the
     * local estimate remains below it.
     */
    default AutoCompactResult reactiveCompact(List<Message> messages, String model,
                                               String querySource,
                                               AutoCompactTrackingState tracking,
                                               String customInstructions) {
        return autocompact(messages, model, querySource, tracking,
            customInstructions, 0);
    }

    /** Returns a new random UUID string. */
    String uuid();

    /**
     * Returns the tool runner used to execute the assistant's tool_use blocks (defaults to {@link
     * ConcurrentToolRunner}, which.
     */
    default ToolRunner toolRunner() {
        return ToolRunner.resolve();
    }

    /**
     * Result of an {@link #autocompact} attempt.
     */
    record AutoCompactResult(MessageCompactor.CompactionResult compactionResult,
                             Integer consecutiveFailures,
                             String compactError,
                             Usage compactionUsage) {
        public AutoCompactResult(MessageCompactor.CompactionResult compactionResult,
                                 Integer consecutiveFailures,
                                 String compactError) {
            this(compactionResult, consecutiveFailures, compactError, Usage.EMPTY);
        }

        public AutoCompactResult(MessageCompactor.CompactionResult compactionResult,
                                 Integer consecutiveFailures) {
            this(compactionResult, consecutiveFailures, null, Usage.EMPTY);
        }
    }
}
