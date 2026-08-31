package com.claudecode.tools.workflows;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowFeatureGateTest {

    @Test
    void hardDisableAndManagedDisableAlwaysWin() {
        assertFalse(WorkflowFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_DISABLE_WORKFLOWS", "1", "CLAUDE_CODE_WORKFLOWS", "1"),
            false, true, true, true));
        assertFalse(WorkflowFeatureGate.evaluate(Map.of(), true, true, true, true));
    }

    @Test
    void requiresEntitlementAndAvailableLaunchGate() {
        assertFalse(WorkflowFeatureGate.evaluate(Map.of(), false, null, false, true));
        assertFalse(WorkflowFeatureGate.evaluate(Map.of(), false, null, true, false));
        assertTrue(WorkflowFeatureGate.evaluate(Map.of(), false, null, true, true));
    }

    @Test
    void explicitEnvironmentAndSettingOverrideDefault() {
        assertTrue(WorkflowFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_WORKFLOWS", "true"), false, null, true, false));
        assertFalse(WorkflowFeatureGate.evaluate(
            Map.of("CLAUDE_CODE_WORKFLOWS", "false"), false, true, true, true));
        assertFalse(WorkflowFeatureGate.evaluate(Map.of(), false, false, true, true));
        assertTrue(WorkflowFeatureGate.evaluate(Map.of(), false, true, true, true));
    }
}
