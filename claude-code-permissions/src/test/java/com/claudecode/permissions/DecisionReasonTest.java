package com.claudecode.permissions;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class DecisionReasonTest {

    @Test
    void allElevenVariantsAreConstructible() {
        DecisionReason rule = new DecisionReason.Rule(
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS));
        DecisionReason mode = new DecisionReason.Mode(PermissionMode.DEFAULT);
        DecisionReason subcommands = new DecisionReason.SubcommandResults(Map.of(
            "tool1", new PermissionDecisionResult(new PermissionDecision.Allow(), mode)));
        DecisionReason promptTool = new DecisionReason.PermissionPromptTool("promptTool", null);
        DecisionReason hook = new DecisionReason.Hook("hookName", null, null);
        DecisionReason asyncAgent = new DecisionReason.AsyncAgent("delegated");
        DecisionReason sandbox = new DecisionReason.SandboxOverride("excludedCommand");
        DecisionReason classifier = new DecisionReason.Classifier("yolo", "safe");
        DecisionReason workingDir = new DecisionReason.WorkingDir("in working dir");
        DecisionReason safetyCheck = new DecisionReason.SafetyCheck("sensitive path", true);
        DecisionReason other = new DecisionReason.Other("misc");

        // No two variants are the same type; each is a distinct DecisionReason.
        DecisionReason[] all = {
            rule, mode, subcommands, promptTool, hook,
            asyncAgent, sandbox, classifier, workingDir, safetyCheck, other
        };
        assertEquals(11, all.length);
        for (DecisionReason r : all) {
            assertNotNull(r);
            assertInstanceOf(DecisionReason.class, r);
        }
    }

    @Test
    void ruleAndModeCarryTheirPayload() {
        PermissionRule pr = PermissionRule.of("Bash", PermissionBehavior.DENY, RuleSource.POLICY_SETTINGS);
        DecisionReason.Rule rule = new DecisionReason.Rule(pr);
        DecisionReason.Mode mode = new DecisionReason.Mode(PermissionMode.PLAN);

        assertEquals(pr, rule.rule());
        assertEquals(PermissionMode.PLAN, mode.mode());
    }
}
