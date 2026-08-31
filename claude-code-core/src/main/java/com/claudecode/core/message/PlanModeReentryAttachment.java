package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

/** One-time guidance emitted when a session re-enters plan mode with an existing plan. */
public record PlanModeReentryAttachment(
    @JsonProperty("planFilePath") String planFilePath
) implements AttachmentPayload {

    @JsonCreator
    public PlanModeReentryAttachment {
        if (StringUtils.isBlank(planFilePath)) {
            throw new IllegalArgumentException("planFilePath must not be blank");
        }
    }
}
