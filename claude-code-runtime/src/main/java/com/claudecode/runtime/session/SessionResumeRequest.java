package com.claudecode.runtime.session;

import java.nio.file.Path;

/**
 * Front-end-neutral request to adopt an existing transcript as the active session.
 */
public record SessionResumeRequest(String sessionId, Path sessionFile, String projectPath) {
}
