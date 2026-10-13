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

    /**
     * Plan mode suspends the edit-oriented modes but not bypass: a session launched with
     * {@code --dangerously-skip-permissions} keeps auto-allowing inside the plan, so no prompt
     * appears. Authority: 2.1.197 and 2.1.236 both compute the effective mode as
     * {@code mode==="bypassPermissions" || mode==="auto" || mode==="plan" && isBypassPermissionsModeAvailable===!0}
     * (236 names it {@code pZa}); the flag is still restored as the exit mode.
     */
    @Test
    void enteringPlanFromBypassKeepsAutoAllowingUntilPlanEnds() {
        PermissionGate gate = new PermissionGate();
        gate.configureBypassPermissionsMode(true, false);
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);

        assertInstanceOf(PermissionDecision.Allow.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "touch changed"))
                .decision());

        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.finishPlanMode());
    }

    /**
     * The carry-over into plan mode is gated on the launch capability, not assumed: a session
     * that may not enter bypass must still be prompted from inside the plan.
     */
    @Test
    void planModeStillAsksWhenBypassWasNotAvailableAtLaunch() {
        PermissionGate gate = new PermissionGate();
        gate.setBypassPermissionsModeAvailable(false);
        gate.setMode(PermissionMode.PLAN);

        assertInstanceOf(PermissionDecision.Ask.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "touch changed"))
                .decision());
    }

    @Test
    void bypassModeSwallowsAToolLevelAskThatIsNotBypassImmune() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);

        PermissionDecision toolAsk = new PermissionDecision.Ask(
            null, null, "Comment contains syntax that can desynchronise permission parsing",
            null, null);

        assertInstanceOf(PermissionDecision.Allow.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "true"), toolAsk)
                .decision());
    }

    @Test
    void planCarriedBypassAlsoSwallowsAToolLevelAsk() {
        PermissionGate gate = new PermissionGate();
        gate.configureBypassPermissionsMode(true, false);
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);

        PermissionDecision toolAsk = PermissionDecision.ask();

        assertInstanceOf(PermissionDecision.Allow.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "true"), toolAsk)
                .decision());
    }

    /**
     * Dangerous removal is the deliberate exception: it stays bypass-immune so {@code rm -rf /}
     * still reaches the user even under {@code --dangerously-skip-permissions}. 2.1.197 matches
     * the {@code "Dangerous rm operation"} reason prefix; 2.1.236 models it as the
     * {@code dangerousRemoval} circuit breaker with {@code bypassImmune:true}.
     */
    @Test
    void bypassModeDoesNotSwallowTheDangerousRemovalAsk() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);

        PermissionDecision dangerous = new PermissionDecision.Ask(
            "/", null, null, null, null, List.of(),
            PermissionDecision.Ask.AskBreaker.DANGEROUS_REMOVAL);

        assertInstanceOf(PermissionDecision.Ask.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "rm -rf /"), dangerous)
                .decision());
    }

    @Test
    void defaultModeStillSurfacesAToolLevelAsk() {
        PermissionGate gate = new PermissionGate();

        PermissionDecision toolAsk = PermissionDecision.ask();

        assertInstanceOf(PermissionDecision.Ask.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "true"), toolAsk)
                .decision());
    }

    /**
     * Guards the wiring, not the rule: the flag has to survive every context wither, or the
     * engine silently loses it again and plan mode reverts to prompting.
     */
    @Test
    void bypassAvailabilitySurvivesContextWithers() {
        PermissionGate gate = new PermissionGate();
        gate.configureBypassPermissionsMode(true, false);
        gate.setMode(PermissionMode.PLAN);

        gate.addRules(List.of(PermissionRule.of(
            "Bash", PermissionBehavior.ALLOW, RuleSource.SESSION)));

        assertTrue(gate.currentContext().bypassPermissionsModeAvailable(),
            "rule mutation dropped the launch capability");

        assertInstanceOf(PermissionDecision.Allow.class,
            gate.checkDetailed("Bash",
                JsonUtils.getMapper().createObjectNode().put("command", "touch changed"))
                .decision());
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
