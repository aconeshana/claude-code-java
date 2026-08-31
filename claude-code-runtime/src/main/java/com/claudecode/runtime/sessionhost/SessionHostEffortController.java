package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Reads and changes reasoning effort for one live application session.
 */
@Explanation("Session-scoped effort control for semantic remote endpoints")
public interface SessionHostEffortController {

    SessionHostEffortState get();

    SessionHostEffortState set(String effort);

    static SessionHostEffortController unsupported() {
        return UnsupportedHolder.INSTANCE;
    }

    final class UnsupportedHolder {
        private static final SessionHostEffortController INSTANCE = new SessionHostEffortController() {
            @Override public SessionHostEffortState get() {
                throw new UnsupportedOperationException("session does not expose effort control");
            }

            @Override public SessionHostEffortState set(String effort) {
                throw new UnsupportedOperationException("session does not expose effort control");
            }
        };

        private UnsupportedHolder() {}
    }
}
