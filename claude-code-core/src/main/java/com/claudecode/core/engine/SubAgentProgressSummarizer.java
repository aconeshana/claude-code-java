package com.claudecode.core.engine;

import com.claudecode.core.message.Message;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Engine-lifecycle hook for periodic background progress summarization of a running sub-agent.
 */
public interface SubAgentProgressSummarizer {

    /**
     * Begins periodic progress summarization for a running sub-agent.
     *
     * @param taskId             opaque id for the sub-agent task (surfaced to
     *                          {@code onSummary}).
     * @param transcriptSupplier supplies the sub-agent's live transcript each
     *                          tick.
     * @param onSummary         receives {@code (taskId, summaryText)} each tick.
     * @return a {@link Runnable} that stops summarization; safe to call once.
     *         Implementations may return a no-op when disabled.
     */
    Runnable startSummarization(String taskId,
                                Supplier<List<Message>> transcriptSupplier,
                                BiConsumer<String, String> onSummary);
}
