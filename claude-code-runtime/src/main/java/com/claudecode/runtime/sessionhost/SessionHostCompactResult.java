package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Display result returned after a manual host-session compaction completes.
 */
@Explanation("Wire-safe result for a session-scoped compact operation")
public record SessionHostCompactResult(String message) {
    public SessionHostCompactResult {
        message = message == null ? "" : message;
    }
}
