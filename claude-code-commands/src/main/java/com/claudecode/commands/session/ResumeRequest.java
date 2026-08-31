package com.claudecode.commands.session;

import java.nio.file.Path;

/**
 * Handoff from {@code /resume <arg>} resolution to the host-owned session switch pipeline.
 */
public record ResumeRequest(
    String sessionId,
    Path sessionFile,
    String projectPath,
    Entrypoint entrypoint
) {
    public enum Entrypoint {
        SLASH_COMMAND_SESSION_ID,
        SLASH_COMMAND_TITLE,
        SLASH_COMMAND_PICKER,
        REWIND_PREVIOUS_SESSION
    }
}
