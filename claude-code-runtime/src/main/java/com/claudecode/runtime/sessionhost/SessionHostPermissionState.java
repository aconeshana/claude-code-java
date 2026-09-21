package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.List;

/**
 * Current permission-mode selection and the choices valid for one host
 * session, plus the bypassPermissions availability the picker greys out on.
 */
@Explanation("Session-scoped permission-mode state for semantic remote endpoints")
public record SessionHostPermissionState(
        String current,
        List<SessionHostPermissionMode> modes,
        boolean bypassPermissionsAvailable,
        boolean bypassPermissionsDisabledByPolicy) {

    public SessionHostPermissionState {
        current = current == null ? "" : current.trim();
        modes = List.copyOf(modes == null ? List.of() : modes);
    }
}
