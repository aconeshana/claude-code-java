package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Active output-style reminder.
 */
public record OutputStyleAttachment(
    @JsonProperty("style") String style
) implements AttachmentPayload {

    @JsonCreator
    public OutputStyleAttachment {
    }
}
