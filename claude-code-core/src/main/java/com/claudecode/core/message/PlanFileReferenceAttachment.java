package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The active plan-mode plan file's content, re-attached after compaction so the model can resume
 * it.
 */
public record PlanFileReferenceAttachment(
    @JsonProperty("planFilePath") String planFilePath,
    @JsonProperty("planContent") String planContent
) implements AttachmentPayload {

    @JsonCreator
    public PlanFileReferenceAttachment {
    }
}
