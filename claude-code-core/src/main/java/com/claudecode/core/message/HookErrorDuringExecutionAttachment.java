package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Diagnostic emitted when a hook-provided replacement cannot be validated or mapped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookErrorDuringExecutionAttachment(
    String content,
    String hookName,
    String toolUseID,
    String hookEvent,
    String command,
    Long durationMs
) implements AttachmentPayload {
    public HookErrorDuringExecutionAttachment(
            String content, String hookName, String toolUseID, String hookEvent) {
        this(content, hookName, toolUseID, hookEvent, null, null);
    }
}
