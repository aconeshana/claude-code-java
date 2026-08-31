package com.claudecode.permissions;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class PermissionGateSyncTest {

    @Test
    void planModeDoesNotRetainBypassSemanticsFromPrePlanMode() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);

        PermissionDecisionResult result = gate.checkDetailed(
            "Bash", JsonNodeFactory.instance.objectNode().put("command", "npm test"));

        assertInstanceOf(PermissionDecision.Ask.class, result.decision());
        assertEquals(PermissionMode.PLAN, gate.currentMode(),
            "permission evaluation must not visibly leave plan mode");
    }

    @Test
    void syncFromDisk_replacesDiskRules_preservesSession() {
        PermissionGate gate = new PermissionGate();
        // Existing state: 2 disk rules + 1 session rule
        PermissionRule userDisk =
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        PermissionRule projectDisk =
            PermissionRule.of("Read", PermissionBehavior.ALLOW, RuleSource.PROJECT_SETTINGS);
        PermissionRule session =
            PermissionRule.of("Edit", PermissionBehavior.ALLOW, RuleSource.SESSION);
        gate.addRules(List.of(userDisk, projectDisk, session));

        // New disk snapshot: a single deny rule replaces both prior disk rules
        PermissionRule newDisk =
            PermissionRule.of("Write", PermissionBehavior.DENY, RuleSource.USER_SETTINGS);
        gate.syncFromDisk(List.of(newDisk));

        List<PermissionRule> after = gate.currentContext().rules();
        // The two prior disk rules are gone; the new disk rule and the session rule remain.
        assertEquals(2, after.size(), "should be 1 new disk + 1 preserved session");
        assertTrue(after.contains(newDisk), "new disk rule should be applied");
        assertTrue(after.contains(session), "session rule must survive disk sync");
        assertFalse(after.contains(userDisk), "old USER_SETTINGS rule must be dropped");
        assertFalse(after.contains(projectDisk), "old PROJECT_SETTINGS rule must be dropped");
    }

    @Test
    void syncFromDisk_emptyNewRules_clearsAllDiskSources() {
        PermissionGate gate = new PermissionGate();
        PermissionRule userDisk =
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        PermissionRule localDisk =
            PermissionRule.of("Read", PermissionBehavior.ALLOW, RuleSource.LOCAL_SETTINGS);
        PermissionRule cliArg =
            PermissionRule.of("Grep", PermissionBehavior.ALLOW, RuleSource.CLI_ARG);
        gate.addRules(List.of(userDisk, localDisk, cliArg));


        gate.syncFromDisk(List.of());

        List<PermissionRule> after = gate.currentContext().rules();
        assertEquals(1, after.size(), "only CLI_ARG rule should remain");
        assertTrue(after.contains(cliArg));
    }

    @Test
    void syncFromDisk_appliesAllDiskSources() {
        PermissionGate gate = new PermissionGate();
        PermissionRule fromUser =
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        PermissionRule fromProject =
            PermissionRule.of("Read", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);
        PermissionRule fromLocal =
            PermissionRule.of("Edit", PermissionBehavior.ASK, RuleSource.LOCAL_SETTINGS);

        gate.syncFromDisk(List.of(fromUser, fromProject, fromLocal));

        List<PermissionRule> after = gate.currentContext().rules();
        assertEquals(3, after.size());
        assertTrue(after.contains(fromUser));
        assertTrue(after.contains(fromProject));
        assertTrue(after.contains(fromLocal));
    }

    @Test
    void syncFromDisk_preservesSkillAndCommandSources() {
        PermissionGate gate = new PermissionGate();
        PermissionRule cmd =
            PermissionRule.of("Write", PermissionBehavior.ALLOW, RuleSource.COMMAND);
        PermissionRule skill =
            PermissionRule.of("Grep", PermissionBehavior.ALLOW, RuleSource.SKILL);
        gate.addRules(List.of(cmd, skill));

        // Fresh disk snapshot doesn't touch command / skill rules.
        gate.syncFromDisk(List.of(
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS)));

        List<PermissionRule> after = gate.currentContext().rules();
        assertTrue(after.contains(cmd), "COMMAND rule must survive");
        assertTrue(after.contains(skill), "SKILL rule must survive");
    }

    // ── H9: managed mode (allowManagedPermissionRulesOnly) strips non-policy sources ──

    @Test
    void syncFromDisk_managedModeStripsSessionAndCliArg() {
        PermissionGate gate = new PermissionGate();
        PermissionRule session =
            PermissionRule.of("Edit", PermissionBehavior.ALLOW, RuleSource.SESSION);
        PermissionRule cliArg =
            PermissionRule.of("Grep", PermissionBehavior.ALLOW, RuleSource.CLI_ARG);
        PermissionRule policy =
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS);
        gate.addRules(List.of(session, cliArg, policy));

        // Managed mode ON with no new disk rules → non-policy sources cleared.
        gate.syncFromDisk(List.of(), true);

        List<PermissionRule> after = gate.currentContext().rules();
        assertFalse(after.contains(session), "SESSION rule must be cleared under managed mode");
        assertFalse(after.contains(cliArg), "CLI_ARG rule must be cleared under managed mode");
        assertTrue(after.contains(policy), "POLICY_SETTINGS rule survives managed mode");
    }

    @Test
    void syncFromDisk_nonManagedModeKeepsSession() {
        PermissionGate gate = new PermissionGate();
        PermissionRule session =
            PermissionRule.of("Edit", PermissionBehavior.ALLOW, RuleSource.SESSION);
        gate.addRules(List.of(session));

        gate.syncFromDisk(List.of(), false);

        assertTrue(gate.currentContext().rules().contains(session),
            "SESSION rule survives non-managed sync");
    }

    @Test
    void syncFromDisk_preservesMissingFlagAndPolicyGroupsLikeTs() {
        PermissionGate gate = new PermissionGate();
        PermissionRule flagAllow =
            PermissionRule.of("Read", PermissionBehavior.ALLOW, RuleSource.FLAG_SETTINGS);
        PermissionRule flagDeny =
            PermissionRule.of("Write", PermissionBehavior.DENY, RuleSource.FLAG_SETTINGS);
        PermissionRule policyAllow =
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS);
        PermissionRule policyDeny =
            PermissionRule.of("Edit", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS);
        gate.addRules(List.of(flagAllow, flagDeny, policyAllow, policyDeny));


        // groups, but does not emit empty replacements for missing DENY groups.
        PermissionRule newFlagAllow =
            PermissionRule.of("Grep", PermissionBehavior.ALLOW, RuleSource.FLAG_SETTINGS);
        PermissionRule newPolicyAllow =
            PermissionRule.of("WebFetch", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS);
        gate.syncFromDisk(List.of(newFlagAllow, newPolicyAllow));

        List<PermissionRule> after = gate.currentContext().rules();
        assertFalse(after.contains(flagAllow));
        assertFalse(after.contains(policyAllow));
        assertTrue(after.contains(newFlagAllow));
        assertTrue(after.contains(newPolicyAllow));
        assertTrue(after.contains(flagDeny), "missing FLAG_SETTINGS deny group survives like TS");
        assertTrue(after.contains(policyDeny), "missing POLICY_SETTINGS deny group survives like TS");
    }

    @Test
    void bypassAvailabilityBlocksEntryAndLeavesActiveMode() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.currentMode());

        gate.setBypassPermissionsModeAvailable(false);
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
        assertFalse(gate.trySetMode(PermissionMode.BYPASS_PERMISSIONS));
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void permissionUpdateModeMatchesTsAndMayEnterBypassMode() {
        PermissionGate gate = new PermissionGate();
        gate.setBypassPermissionsModeAvailable(false);

        gate.applyUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.BYPASS_PERMISSIONS,
            PermissionUpdate.Destination.SESSION)));


        // availability check belongs to explicit set_permission_mode only.
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.currentMode());
        assertFalse(gate.trySetMode(PermissionMode.BYPASS_PERMISSIONS));
    }

    @Test
    void planTransitionRestoresTheModeActiveBeforePermissionUpdate() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.ACCEPT_EDITS);

        gate.applyUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.PLAN, PermissionUpdate.Destination.SESSION)));

        assertEquals(PermissionMode.PLAN, gate.currentMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, gate.finishPlanMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, gate.currentMode());
    }

    @Test
    void explicitApprovalModeWinsOverPrePlanRestoration() {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setMode(PermissionMode.PLAN);
        gate.applyUpdates(List.of(new PermissionUpdate.SetMode(
            PermissionModeKind.DEFAULT, PermissionUpdate.Destination.SESSION)));

        assertEquals(PermissionMode.DEFAULT, gate.finishPlanMode());
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
    }

    @Test
    void bypassKillSwitchRemainsDisabledAfterSettingIsRemoved() {
        PermissionGate gate = new PermissionGate();
        gate.configureBypassPermissionsMode(true, false);
        assertTrue(gate.isBypassPermissionsModeAvailable());

        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        gate.setBypassPermissionsModeDisabled(true);
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());
        assertFalse(gate.isBypassPermissionsModeAvailable());

        gate.setBypassPermissionsModeDisabled(false);
        assertFalse(gate.isBypassPermissionsModeAvailable());
        assertFalse(gate.trySetMode(PermissionMode.BYPASS_PERMISSIONS));
    }

    @Test
    void managedSyncReplacesPolicyRulesPerBehaviorWithoutDuplicates() {
        PermissionGate gate = new PermissionGate();
        gate.addRules(List.of(PermissionRule.of(
            "Bash", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS)));

        gate.syncFromDisk(List.of(PermissionRule.of(
            "Read", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS)), true);

        List<PermissionRule> policy = gate.currentContext().rules().stream()
            .filter(rule -> rule.source() == RuleSource.POLICY_SETTINGS)
            .toList();
        assertEquals(1, policy.size());
        assertEquals("Read", policy.getFirst().toolName());
    }
}
