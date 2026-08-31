package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PermissionModeCycleTest {

    @Test
    void followsTheExternalUserCycleWhenBypassIsAvailable() {
        assertEquals("acceptEdits", PermissionModeCycle.next("default", true));
        assertEquals("plan", PermissionModeCycle.next("acceptEdits", true));
        assertEquals("bypassPermissions", PermissionModeCycle.next("plan", true));
        assertEquals("default", PermissionModeCycle.next("bypassPermissions", true));
    }

    @Test
    void planSkipsBypassWhenTheSessionPolicyDisallowsIt() {
        assertEquals("default", PermissionModeCycle.next("plan", false));
    }

    @Test
    void nonCycledModesReturnToDefault() {
        assertEquals("default", PermissionModeCycle.next("dontAsk", true));
        assertEquals("default", PermissionModeCycle.next("auto", true));
        assertEquals("default", PermissionModeCycle.next("future-mode", true));
    }
}
