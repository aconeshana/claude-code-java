package com.claudecode.services.compact;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import java.util.List;

/**
 * Interface for summarizing conversation messages during compaction.
 * Implementations call an LLM to generate a summary of the conversation.
 */
public interface CompactSummarizer {

    /**
     * Summarize the given messages using the provided compact prompt.
     *
     * @param messages     the messages to summarize
     * @param compactPrompt the prompt instructing the LLM how to summarize
     * @return the summary text, or a string starting with "prompt is too long" if the prompt exceeds limits
     */
    String summarize(List<Message> messages, String compactPrompt);

    /** Summary text plus the real token usage of the summarization API call, if known. */
    record SummaryResult(String text, Usage usage) {}

    /** Clears state inherited from the preceding turn before a manual compact starts. */
    default void prepareManualCompact() {
        // Standalone summarizers have no session-scoped cancellation state.
    }

    default SummaryResult summarizeWithUsage(List<Message> messages, String compactPrompt) {
        return new SummaryResult(summarize(messages, compactPrompt), Usage.EMPTY);
    }
}
