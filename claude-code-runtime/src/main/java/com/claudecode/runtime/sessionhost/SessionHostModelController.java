package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Reads and changes the live model owned by one application session.
 */
@Explanation("Session-scoped model control for semantic remote endpoints")
public interface SessionHostModelController {

    SessionHostModelState get();

    SessionHostModelState set(String model);

    static SessionHostModelController unsupported() {
        return UnsupportedHolder.INSTANCE;
    }

    final class UnsupportedHolder {
        private static final SessionHostModelController INSTANCE = new SessionHostModelController() {
            @Override public SessionHostModelState get() {
                throw new UnsupportedOperationException("session does not expose model control");
            }

            @Override public SessionHostModelState set(String model) {
                throw new UnsupportedOperationException("session does not expose model control");
            }
        };

        private UnsupportedHolder() {}
    }
}
