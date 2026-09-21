package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;

/**
 * Reads and changes the live permission mode owned by one application session.
 */
@Explanation("Session-scoped permission-mode control for semantic remote endpoints")
public interface SessionHostPermissionController {

    SessionHostPermissionState get();

    SessionHostPermissionState set(String mode);

    static SessionHostPermissionController unsupported() {
        return UnsupportedHolder.INSTANCE;
    }

    final class UnsupportedHolder {
        private static final SessionHostPermissionController INSTANCE =
            new SessionHostPermissionController() {
                @Override public SessionHostPermissionState get() {
                    throw new UnsupportedOperationException(
                        "session does not expose permission-mode control");
                }

                @Override public SessionHostPermissionState set(String mode) {
                    throw new UnsupportedOperationException(
                        "session does not expose permission-mode control");
                }
            };

        private UnsupportedHolder() {}
    }
}
