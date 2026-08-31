package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A file that was read before compaction but is too large to re-attach in full — only a pointer
 * note is re-injected.
 */
public record CompactFileReferenceAttachment(
    @JsonProperty("filename") String filename
) implements AttachmentPayload {

    @JsonCreator
    public CompactFileReferenceAttachment {
    }
}
