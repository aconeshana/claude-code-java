package com.claudecode.core.message;

/**
 * Announces that {@code ultracode} effort has been switched off, so the standing
 * workflow-orchestration instruction no longer applies.
 */
public record UltraEffortExitAttachment() implements AttachmentPayload {}
