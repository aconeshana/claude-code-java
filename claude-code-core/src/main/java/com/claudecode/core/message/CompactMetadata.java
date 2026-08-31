package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Metadata persisted on a {@code compact_boundary} system message.
 */
public record CompactMetadata(
    @JsonProperty("trigger") String trigger,
    @JsonProperty("preTokens") Long preTokens,
    @JsonProperty("durationMs") Long durationMs,
    @JsonProperty("preservedSegment") PreservedSegment preservedSegment,
    @JsonProperty("preservedMessages") PreservedMessages preservedMessages,
    @JsonProperty("postTokens") Long postTokens,
    @JsonProperty("cumulativeDroppedTokens") Long cumulativeDroppedTokens,
    @JsonProperty("userContext") String userContext,
    @JsonProperty("messagesSummarized") Integer messagesSummarized,
    @JsonProperty("precomputed") Boolean precomputed,
    @JsonProperty("preCompactDiscoveredTools") List<String> preCompactDiscoveredTools
) {
    /** Compatibility constructor for older Java callers that only had relink data. */
    public CompactMetadata(PreservedSegment preservedSegment) {
        this(null, null, null, preservedSegment, null, null, null,
            null, null, null, null);
    }

    /** Minimal native boundary metadata available before summarization finishes. */
    public CompactMetadata(String trigger, long preTokens) {
        this(trigger, preTokens, null, null, null, null, null,
            null, null, null, null);
    }


    public CompactMetadata withCompletion(long durationMs, long postTokens,
                                           long cumulativeDroppedTokens) {
        return new CompactMetadata(trigger, preTokens, durationMs,
            preservedSegment, preservedMessages, postTokens, cumulativeDroppedTokens,
            userContext, messagesSummarized, precomputed, preCompactDiscoveredTools);
    }

    /** Returns a copy with the exact preserved segment/message UUID metadata. */
    public CompactMetadata withPreserved(PreservedSegment segment, PreservedMessages messages) {
        return new CompactMetadata(trigger, preTokens, durationMs,
            segment, messages, postTokens, cumulativeDroppedTokens,
            userContext, messagesSummarized, precomputed, preCompactDiscoveredTools);
    }

    /** Returns a copy carrying the message-selector partial-compaction context. */
    public CompactMetadata withPartialContext(String context, int summarizedCount) {
        return new CompactMetadata(trigger, preTokens, durationMs,
            preservedSegment, preservedMessages, postTokens, cumulativeDroppedTokens,
            context, summarizedCount, precomputed, preCompactDiscoveredTools);
    }

    /** Returns a copy carrying tool-reference discoveries made before compaction. */
    public CompactMetadata withPreCompactDiscoveredTools(List<String> discoveredTools) {
        return new CompactMetadata(trigger, preTokens, durationMs,
            preservedSegment, preservedMessages, postTokens, cumulativeDroppedTokens,
            userContext, messagesSummarized, precomputed,
            discoveredTools == null ? null : List.copyOf(discoveredTools));
    }

    /**
     * Returns a copy with counts recomputed after caller-side preserved messages are attached.
     */
    public CompactMetadata withTokenCounts(long newPreTokens, long newPostTokens) {
        Long newCumulative = cumulativeDroppedTokens;
        if (cumulativeDroppedTokens != null && preTokens != null && postTokens != null) {
            long previousCumulative = cumulativeDroppedTokens
                - Math.max(0L, preTokens - postTokens);
            newCumulative = Math.max(0L, previousCumulative)
                + Math.max(0L, newPreTokens - newPostTokens);
        }
        return new CompactMetadata(trigger, newPreTokens, durationMs,
            preservedSegment, preservedMessages, newPostTokens, newCumulative,
            userContext, messagesSummarized, precomputed, preCompactDiscoveredTools);
    }
}
