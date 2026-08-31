package com.claudecode.core.message;

/**
 * User-visible, model-invisible message emitted by a configured hook.
 */
public record HookSystemMessageAttachment(
    String content,
    String hookName,
    String toolUseID,
    String hookEvent
) implements AttachmentPayload {
}
