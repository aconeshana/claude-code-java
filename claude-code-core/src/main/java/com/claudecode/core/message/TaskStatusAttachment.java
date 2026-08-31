package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status of a background agent task (still running, or finished but not yet retrieved by the
 * model), re-attached so a compaction doesn't orphan it.
 */
public record TaskStatusAttachment(
    @JsonProperty("taskId") String taskId,
    @JsonProperty("taskType") String taskType,
    @JsonProperty("status") String status,
    @JsonProperty("description") String description,
    @JsonProperty("deltaSummary") String deltaSummary,
    @JsonProperty("outputFilePath") String outputFilePath
) implements AttachmentPayload {

    @JsonCreator
    public TaskStatusAttachment {
    }
}
