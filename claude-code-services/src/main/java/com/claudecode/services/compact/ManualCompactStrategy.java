package com.claudecode.services.compact;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Performs a full or partial compaction by calling an LLM summarizer.
 */
interface ManualCompactStrategy {

    /** Maximum prompt-too-long retries before giving up. */
    int MAX_PTL_RETRIES = 3;

    /**
     * Compact the conversation by summarizing messages via LLM.
     *
     * @param messages       the conversation messages to compact
     * @param compactSummarizer the summarizer to use
     * @param isAutoCompact  true if triggered automatically, false if manual
     * @param customInstructions optional custom instructions for the compact prompt
     * @param attachmentContext live process state for post-compact attachment producers
     * @return the compaction result
     * @throws CompactException if compaction fails
     */
    MessageCompactor.CompactionResult compact(List<Message> messages, CompactSummarizer compactSummarizer,
                                               boolean isAutoCompact, String customInstructions,
                                               CompactAttachmentContext attachmentContext,
                                               String model);

    /**
     * Compact only a portion of the conversation messages.
     *
     * @throws CompactException if compaction fails
     */
    PartialCompactResult partialCompact(List<Message> messages, int pivotIndex, String direction,
                                         String feedback, String customInstructions,
                                         CompactSummarizer compactSummarizer,
                                         CompactAttachmentContext attachmentContext);
}
