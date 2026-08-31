package com.claudecode.core.message;



/**
 * Attachment surfacing a completed background (output-driven / config-async) hook's deferred result
 * on a later turn.
 */
public record AsyncHookResponseAttachment(

    String processId,
    /** Hook name / command label. */
    String hookName,
    /** Hook event ({@code PreToolUse}, {@code Stop}, ...). */
    String hookEvent,
    /** Tool the hook fired for, when applicable. */
    String toolName,
    /** First non-async JSON line of the hook's stdout, as a raw JSON string. */
    String responseJson,
    /** Full stdout captured after the async handshake line. */
    String stdout,
    /** Full stderr captured from the hook. */
    String stderr,
    /** Process exit code. */
    int exitCode
) implements AttachmentPayload {
}
