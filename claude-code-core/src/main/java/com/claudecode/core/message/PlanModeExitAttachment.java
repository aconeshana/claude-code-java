package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One-time notice that plan mode was just exited.
 */
public record PlanModeExitAttachment(
    @JsonProperty("planFilePath") String planFilePath,
    @JsonProperty("planExists") boolean planExists
) implements AttachmentPayload {

    @JsonCreator
    public PlanModeExitAttachment {
    }
}
