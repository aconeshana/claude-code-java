package com.claudecode.core.message;

/**
 * Marks a turn whose prompt mentioned the {@code ultracode} keyword, opting it into
 * multi-agent orchestration.
 */
public record WorkflowKeywordAttachment() implements AttachmentPayload {}
