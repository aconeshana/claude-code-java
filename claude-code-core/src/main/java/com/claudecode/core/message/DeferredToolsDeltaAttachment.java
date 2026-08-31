package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Delta of deferred (ToolSearch-routed) tools announced to the model since the last turn.
 */
public record DeferredToolsDeltaAttachment(
    @JsonProperty("addedNames") List<String> addedNames,
    @JsonProperty("addedLines") List<String> addedLines,
    @JsonProperty("removedNames") List<String> removedNames
) implements AttachmentPayload {

    @JsonCreator
    public DeferredToolsDeltaAttachment {
    }
}
