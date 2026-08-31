package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Output-token usage reminder (this turn / session).
 */
public record OutputTokenUsageAttachment(
    @JsonProperty("turn") long turn,
    @JsonProperty("budget") Long budget,
    @JsonProperty("session") long session
) implements AttachmentPayload {

    @JsonCreator
    public OutputTokenUsageAttachment {
    }
}
