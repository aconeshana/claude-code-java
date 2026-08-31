package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Durable diagnostic emitted when a hook evaluator fails without blocking the main query loop.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookNonBlockingErrorAttachment(
    String hookName,
    String stderr,
    String stdout,
    int exitCode,
    String toolUseID,
    String hookEvent,
    String command,
    Long durationMs
) implements AttachmentPayload {
}
