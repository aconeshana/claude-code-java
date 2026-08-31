package com.claudecode.core.message;

/**
 * A queued command surfaced as a system-reminder user message while the model is mid-turn — e.g.
 */
public record QueuedCommandAttachment(
    String text,
    String mode,
    String originKind,
    boolean isMeta
) implements AttachmentPayload {
}
