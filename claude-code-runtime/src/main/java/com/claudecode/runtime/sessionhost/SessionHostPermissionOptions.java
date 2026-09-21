package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the permission-mode choices projected by Session Host endpoints.
 *
 * <p>The selectable set mirrors the TUI Shift+Tab cycle
 * ({@code PermissionModeCycle}) plus {@code dontAsk}, minus {@code auto}:
 * auto is the internal classifier's own state and never appears in
 * {@code SdkControlBroker#validExternalPermissionMode}'s whitelist, so no
 * remote control surface — including this one — can request it.
 */
@Explanation("Projects PermissionMode onto semantic remote endpoints")
public final class SessionHostPermissionOptions {

    private static final List<PermissionMode> SELECTABLE = List.of(
        PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS,
        PermissionMode.BYPASS_PERMISSIONS, PermissionMode.DONT_ASK);

    private SessionHostPermissionOptions() {}

    /** The full state for {@code gate}'s owning session. */
    public static SessionHostPermissionState build(PermissionGate gate) {
        boolean bypassAvailable = gate.isBypassPermissionsModeAvailable();
        List<SessionHostPermissionMode> options = new ArrayList<>();
        for (PermissionMode mode : SELECTABLE) {
            boolean available = mode != PermissionMode.BYPASS_PERMISSIONS || bypassAvailable;
            options.add(new SessionHostPermissionMode(mode.external(), mode.title(),
                mode.shortTitle(), mode.symbol(), mode.colorKey().name(), available));
        }
        return new SessionHostPermissionState(gate.currentMode().external(),
            List.copyOf(options), bypassAvailable, gate.isBypassPermissionsModeDisabledByPolicy());
    }

    /** True when {@code selected} names one of the selectable modes. */
    public static boolean isSelectable(String selected) {
        return SELECTABLE.stream().anyMatch(mode -> mode.external().equals(selected));
    }
}
