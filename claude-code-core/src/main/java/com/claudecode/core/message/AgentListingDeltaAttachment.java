package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Delta of the available Agent-tool agent types since the last turn.
 */
public record AgentListingDeltaAttachment(
    @JsonProperty("addedTypes") List<String> addedTypes,
    @JsonProperty("addedLines") List<String> addedLines,
    @JsonProperty("removedTypes") List<String> removedTypes,
    @JsonProperty("isInitial") boolean isInitial,
    @JsonProperty("showConcurrencyNote") boolean showConcurrencyNote
) implements AttachmentPayload {

    @JsonCreator
    public AgentListingDeltaAttachment {
    }
}
