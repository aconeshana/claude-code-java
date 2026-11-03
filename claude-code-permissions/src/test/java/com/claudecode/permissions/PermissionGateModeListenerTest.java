package com.claudecode.permissions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


/**
 * The shared-holder broadcast: a gate is read and written by more than one
 * surface (the TUI composer and a gateway-hosted web session), each keeping
 * its own cached mode copy. Before {@link PermissionGate#addModeListener}
 * existed there was no path from a mode change to the other surface, so the
 * two drifted — a web session opened silently resetting the TUI's bypass
 * mode was the visible symptom.
 */
class PermissionGateModeListenerTest {

    @Test
    void listenerReceivesTheNewModeOnChange() {
        PermissionGate gate = new PermissionGate();
        List<PermissionMode> seen = new ArrayList<>();
        gate.addModeListener(seen::add);

        gate.setMode(PermissionMode.PLAN);

        assertEquals(List.of(PermissionMode.PLAN), seen);
    }

    @Test
    void settingTheSameModeDoesNotNotify() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.PLAN);
        List<PermissionMode> seen = new ArrayList<>();
        gate.addModeListener(seen::add);

        gate.setMode(PermissionMode.PLAN);

        assertTrue(seen.isEmpty(), "a same-value set must not broadcast");
    }

    @Test
    void bypassUnavailableReportsTheEffectiveFallbackMode() {
        PermissionGate gate = new PermissionGate();
        gate.setBypassPermissionsModeAvailable(false);
        gate.setMode(PermissionMode.PLAN);
        List<PermissionMode> seen = new ArrayList<>();
        gate.addModeListener(seen::add);

        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);

        assertEquals(List.of(PermissionMode.DEFAULT), seen,
            "listeners observe the effective mode, not the refused request");
    }

    @Test
    void losingBypassAvailabilityPullsBypassBackAndNotifies() {
        PermissionGate gate = new PermissionGate();
        gate.setBypassPermissionsModeAvailable(true);
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        List<PermissionMode> seen = new ArrayList<>();
        gate.addModeListener(seen::add);

        gate.setBypassPermissionsModeDisabled(true);

        assertEquals(List.of(PermissionMode.DEFAULT), seen);
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void aThrowingListenerDoesNotBreakTheSetterOrItsSiblings() {
        PermissionGate gate = new PermissionGate();
        List<PermissionMode> seen = new ArrayList<>();
        gate.addModeListener(_ -> { throw new IllegalStateException("boom"); });
        gate.addModeListener(seen::add);

        assertDoesNotThrow(() -> gate.setMode(PermissionMode.ACCEPT_EDITS));

        assertEquals(List.of(PermissionMode.ACCEPT_EDITS), seen);
        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode());
    }

    @Test
    void closingTheHandleUnsubscribes() {
        PermissionGate gate = new PermissionGate();
        List<PermissionMode> seen = new ArrayList<>();
        Runnable handle = gate.addModeListener(seen::add);

        handle.run();
        gate.setMode(PermissionMode.PLAN);

        assertTrue(seen.isEmpty());
    }
}