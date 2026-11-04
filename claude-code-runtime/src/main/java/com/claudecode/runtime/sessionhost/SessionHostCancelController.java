package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Stops the turn currently running on one application-owned session.
 *
 * <p>This is the session-scoped twin of a user interrupt: the same
 * {@code user-cancel} cancellation the TUI's Ctrl+C and the SDK
 * {@code control_request{interrupt}} drive, so a remote stop lands in the
 * transcript exactly like a local one (visible interruption row, interrupt
 * auto-restore of the submitted prompt). A no-op when the session is idle —
 * "nothing to stop" is not an error, matching the underlying abort.
 */
@Explanation("Session-scoped cancellation for semantic remote endpoints")
public interface SessionHostCancelController {

    /** Requests cancellation of the addressed session's active turn. */
    void cancel();

    static SessionHostCancelController unsupported() {
        return UnsupportedHolder.INSTANCE;
    }

    final class UnsupportedHolder {
        private static final SessionHostCancelController INSTANCE =
            () -> {
                throw new UnsupportedOperationException(
                    "session does not expose cancellation");
            };

        private UnsupportedHolder() {}
    }
}
