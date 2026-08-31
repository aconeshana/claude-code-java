package com.claudecode.core.engine;



/**
 * A completed background (output-driven / config-async) hook's deferred result, surfaced on a later
 * turn as an {@code async_hook_response} attachment.
 */
public record AsyncHookResponse(

    String processId,
    /** First non-async JSON line of the hook's stdout, as a raw JSON string. */
    String responseJson,

    String hookName,
    /** Hook event ({@code PreToolUse}, {@code Stop}, ...). */
    String hookEvent,
    /** Tool the hook fired for, when applicable. */
    String toolName,
    /** Originating plugin id, when applicable. */
    String pluginId,
    /** Full stdout captured after the async handshake line. */
    String stdout,
    /** Full stderr captured from the hook. */
    String stderr,
    /** Process exit code. */
    int exitCode
) {
}
