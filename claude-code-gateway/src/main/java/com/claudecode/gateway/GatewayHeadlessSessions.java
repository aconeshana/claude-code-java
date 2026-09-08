package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application boundary for the gateway's in-process parallel headless sessions.
 *
 * <p>Headless sessions run beside the interactive TUI session without going
 * through {@code SessionHostRegistry} activation: the TUI keeps its own
 * session and progress, while each {@code metadata.session_id} routes to its
 * own headless {@code QuerySession}. The CLI composition root implements the
 * assembly; the gateway only sees runtime session types.
 */
@Explanation("Third-endpoint parallel sessions the CLI assembles behind the gateway")
public interface GatewayHeadlessSessions {

    /** One open request: a client-chosen session id and a project directory. */
    record OpenRequest(String sessionId, String projectPath) {}

    /** One open result: the live session plus its project and resume state. */
    record Opened(SessionHostSession session, String projectPath, boolean resumed) {}

    /** One listing row: the open headless sessions the client can route to. */
    record SessionListing(String sessionId, String projectPath, Instant openedAt) {}

    /** The live headless session for {@code sessionId}, when one is open. */
    default Optional<SessionHostSession> find(String sessionId) {
        return Optional.empty();
    }

    /** The currently open headless sessions. */
    default List<SessionListing> list() {
        return List.of();
    }

    /** Whether {@code sessionId} names a currently open headless session. */
    default boolean isOpen(String sessionId) {
        return find(sessionId).isPresent();
    }

    /**
     * Opens (or resumes) one headless session. An empty session id mints a
     * fresh one; an id already open is returned as-is; an id found on disk is
     * resumed message-level.
     */
    default Opened open(OpenRequest request) {
        throw new UnsupportedOperationException("headless sessions are not configured");
    }

    /** Closes the headless session for {@code sessionId}; false when absent. */
    default boolean close(String sessionId) {
        return false;
    }

    /** Closes every open headless session (gateway shutdown path). */
    default void closeAll() {}
}
