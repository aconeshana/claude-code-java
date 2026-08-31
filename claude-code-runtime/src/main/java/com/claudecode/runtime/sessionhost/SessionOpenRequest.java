package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Request to create a new session or resume/attach an existing host session.
 */
@Explanation("Session Link command shape for local session activation")
public record SessionOpenRequest(String requestedSessionId, String workDir, String activationId) {
    public SessionOpenRequest(String requestedSessionId, String workDir) {
        this(requestedSessionId, workDir, "");
    }

    public SessionOpenRequest {
        requestedSessionId = requestedSessionId == null ? "" : requestedSessionId.strip();
        workDir = workDir == null ? "" : workDir.strip();
        activationId = activationId == null ? "" : activationId.strip();
        validateText("requested session ID", requestedSessionId, 1024, true);
        validateText("activation ID", activationId, 128, true);
        if (!activationId.isEmpty()
                && !activationId.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException(
                "activation ID contains unsupported characters");
        }
    }

    private static void validateText(String label, String value, int maxLength, boolean allowEmpty) {
        if ((!allowEmpty && value.isEmpty()) || value.length() > maxLength) {
            throw new IllegalArgumentException(
                label + " must contain " + (allowEmpty ? "0" : "1") + "-" + maxLength
                    + " characters");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " contains control characters");
        }
    }
}
