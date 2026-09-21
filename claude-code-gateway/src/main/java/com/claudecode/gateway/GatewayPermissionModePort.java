package com.claudecode.gateway;

import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import java.util.Optional;

/**
 * Consumer-owned boundary for one session's live permission-mode selection,
 * projected for the webui composer's permission-mode chip.
 *
 * <p>Addressing mirrors {@link GatewaySessionContextPort}: a blank session id
 * means the active TUI session, and a known headless id means that open
 * headless session. Unlike model/context, permission mode has no read-only
 * transcript projection — a closed session has no live {@code PermissionGate}
 * left to report, so any other id is rejected.
 */
public interface GatewayPermissionModePort {

    /** Result of one mode-change attempt: the refreshed state, or a rejection. */
    record SelectionResult(SessionHostPermissionState state, String error) {

        public static SelectionResult accepted(SessionHostPermissionState state) {
            return new SelectionResult(state, null);
        }

        public static SelectionResult rejected(String error) {
            return new SelectionResult(null, error);
        }

        public boolean accepted() {
            return error == null;
        }
    }

    /** The addressed session's current permission-mode state, if it has one. */
    default Optional<SessionHostPermissionState> state(String sessionId) {
        return Optional.empty();
    }

    /**
     * Applies {@code mode} to the addressed session. Implementations validate
     * against the same {@code PermissionMode} whitelist the TUI Shift+Tab
     * cycle enforces; a rejected mode returns a message suitable for display.
     */
    default SelectionResult selectMode(String sessionId, String mode) {
        return SelectionResult.rejected("permission mode selection is not configured");
    }
}
