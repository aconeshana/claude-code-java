package com.claudecode.permissions;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class PermissionDecisionReuseTest {

    @Test
    void noPayloadDecisionsAreCanonical() {
        assertSame(PermissionDecision.allow(), PermissionDecision.allow());
        assertSame(PermissionDecision.ask(), PermissionDecision.ask());
        assertSame(PermissionDecision.deny(), PermissionDecision.deny());
    }
}
