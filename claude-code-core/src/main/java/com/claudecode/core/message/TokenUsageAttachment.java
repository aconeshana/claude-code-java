package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Conversation token usage reminder.
 */
public record TokenUsageAttachment(
    @JsonProperty("used") long used,
    @JsonProperty("total") long total,
    @JsonProperty("remaining") long remaining
) implements AttachmentPayload {

    @JsonCreator
    public TokenUsageAttachment {
    }
}
