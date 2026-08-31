package com.claudecode.permissions;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanModeLifecycleTest {

    @Test
    void planAutoAvailabilityTracksTheReleasedModelAllowlistDynamically() {
        PermissionGate gate = new PermissionGate();
        gate.configurePlanAutoMode(() -> true, () -> true, () -> true);
        gate.configureAutoModeModelSupport(
            PermissionGate::supportsReleasedExternalAutoModeModel);

        gate.setAutoModeCurrentModel("claude-sonnet-4-6-20260801");
        assertTrue(gate.isPlanAutoModeAvailable());

        gate.setAutoModeCurrentModel("claude-haiku-4-5-20251001");
        assertFalse(gate.isPlanAutoModeAvailable());
    }

    @Test
    void planAutoModeRequiresOptInSettingAndNonBypassEntryMode() {
        PermissionGate gate = new PermissionGate();
        gate.configurePlanAutoMode(() -> true, () -> true, () -> true);

        gate.setMode(PermissionMode.PLAN);
        assertTrue(gate.isPlanAutoModeActive());
        assertTrue(gate.isPlanAutoModeAvailable());

        gate.finishPlanMode();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);
        assertFalse(gate.isPlanAutoModeActive());

        gate.finishPlanMode();
        gate.configurePlanAutoMode(() -> true, () -> false, () -> true);
        gate.setMode(PermissionMode.PLAN);
        assertFalse(gate.isPlanAutoModeActive());
    }

    @Test
    void enteringPlanFromBypassRestoresBypassOnlyAfterPlanEnds() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);

        assertInstanceOf(PermissionDecision.Ask.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "touch changed"))
                .decision());

        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.finishPlanMode());
    }

    @Test
    void planAutoTemporarilyStripsDangerousAllowRulesAndRestoresThemWhenDisabled() {
        AtomicBoolean useDuringPlan = new AtomicBoolean(true);
        PermissionRule dangerousBash = PermissionRule.withPattern(
            "Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS, "python:*");
        PermissionRule dangerousPowerShell = PermissionRule.withPattern(
            "PowerShell", PermissionBehavior.ALLOW, RuleSource.LOCAL_SETTINGS, "iex:*");
        PermissionRule dangerousAgent = PermissionRule.withPattern(
            "Agent", PermissionBehavior.ALLOW, RuleSource.SESSION, "Explore");
        PermissionRule safeBash = PermissionRule.withPattern(
            "Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS, "git status:*");
        PermissionRule deny = PermissionRule.of(
            "Write", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS);
        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(dangerousBash, dangerousPowerShell, dangerousAgent, safeBash, deny));
        gate.configurePlanAutoMode(() -> true, useDuringPlan::get, () -> true);

        gate.setMode(PermissionMode.PLAN);

        assertEquals(List.of(safeBash, deny), gate.currentContext().rules());
        assertInstanceOf(PermissionDecision.Ask.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "python -c 'print(1)'"))
                .decision());

        useDuringPlan.set(false);
        assertEquals(List.of(dangerousBash, dangerousPowerShell, dangerousAgent, safeBash, deny),
            gate.currentContext().rules());

        useDuringPlan.set(true);
        assertEquals(List.of(safeBash, deny), gate.currentContext().rules());
        assertEquals(PermissionMode.DEFAULT, gate.finishPlanMode());
        assertEquals(List.of(dangerousBash, dangerousPowerShell, dangerousAgent, safeBash, deny),
            gate.currentContext().rules());
    }

    @Test
    void planExitFallsBackToDefaultWhenAutoGateTurnsOff() {
        AtomicBoolean gateEnabled = new AtomicBoolean(true);
        PermissionGate gate = new PermissionGate();
        gate.configurePlanAutoMode(() -> true, () -> true, gateEnabled::get);
        gate.setMode(PermissionMode.AUTO);
        gate.setMode(PermissionMode.PLAN);

        gateEnabled.set(false);

        assertEquals(PermissionMode.DEFAULT, gate.finishPlanMode());
    }

    @Test
    void permissionUpdateEnteringAutoStripsDangerousRulesImmediately() {
        PermissionRule dangerous = PermissionRule.withPattern(
            "Bash", PermissionBehavior.ALLOW, RuleSource.SESSION, "node:*");
        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(dangerous));
        gate.configurePlanAutoMode(() -> true, () -> true, () -> true);

        gate.applyUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.AUTO, PermissionUpdate.Destination.SESSION)));

        assertEquals(List.of(), gate.currentContext().rules());
    }

    @Test
    void duplicateDangerousRulesSurviveAutoModeRoundTrip() {
        PermissionRule dangerous = PermissionRule.withPattern(
            "Bash", PermissionBehavior.ALLOW, RuleSource.SESSION, "node:*");
        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(dangerous, dangerous));
        gate.configurePlanAutoMode(() -> true, () -> true, () -> true);

        gate.setMode(PermissionMode.AUTO);
        gate.setMode(PermissionMode.DEFAULT);

        assertEquals(List.of(dangerous, dangerous), gate.currentContext().rules());
    }

    @Test
    void stalePlanApprovalFallsBackWhenAutoGateTurnsOff() {
        AtomicBoolean gateEnabled = new AtomicBoolean(true);
        PermissionGate gate = new PermissionGate();
        gate.configurePlanAutoMode(() -> true, () -> true, gateEnabled::get);
        gate.setMode(PermissionMode.PLAN);
        gateEnabled.set(false);

        gate.applyUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.AUTO, PermissionUpdate.Destination.SESSION)));

        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void approvedExitProducesOneReentrySignalOnceAnExistingPlanIsAvailable() {
        PermissionGate gate = new PermissionGate();

        gate.markPlanModeExited();

        assertFalse(gate.consumePlanModeReentry(false));
        assertTrue(gate.consumePlanModeReentry(true));
        assertFalse(gate.consumePlanModeReentry(true));
    }
}
