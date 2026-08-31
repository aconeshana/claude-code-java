package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UnreachableRuleDetectorTest {

    private static PermissionRule toolWide(String tool, PermissionBehavior behavior, RuleSource source) {
        return PermissionRule.of(tool, behavior, source);
    }

    private static PermissionRule specific(String tool, PermissionBehavior behavior, String pattern) {
        return PermissionRule.withPattern(tool, behavior, RuleSource.USER_SETTINGS, pattern);
    }

    @Test
    void toolWideDenyShadowsSpecificAllow() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule deny = toolWide("Bash", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);

        List<UnreachableRule> result = UnreachableRuleDetector.detect(List.of(allow, deny));

        assertEquals(1, result.size());
        assertEquals(allow, result.getFirst().rule());
        assertEquals(deny, result.getFirst().shadowedBy());
        assertEquals(UnreachableRule.ShadowType.DENY, result.getFirst().shadowType());
    }

    @Test
    void toolWideAskShadowsSpecificAllowWhenNoDenyPresent() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule ask = toolWide("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);

        List<UnreachableRule> result = UnreachableRuleDetector.detect(List.of(allow, ask));

        assertEquals(1, result.size());
        assertEquals(UnreachableRule.ShadowType.ASK, result.getFirst().shadowType());
    }

    @Test
    void denyWinsOverAskAndOnlyOneEntryReported() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule deny = toolWide("Bash", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);
        PermissionRule ask = toolWide("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);

        List<UnreachableRule> result = UnreachableRuleDetector.detect(List.of(allow, deny, ask));

        assertEquals(1, result.size());
        assertEquals(UnreachableRule.ShadowType.DENY, result.getFirst().shadowType());
    }

    @Test
    void toolWideAllowIsNeverFlagged() {
        PermissionRule allow = toolWide("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS);
        PermissionRule deny = toolWide("Bash", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);

        List<UnreachableRule> result = UnreachableRuleDetector.detect(List.of(allow, deny));

        assertTrue(result.isEmpty());
    }

    @Test
    void twoSpecificAllowRulesWithNoToolWideRuleAreNotFlaggedRegardlessOfOrder() {
        PermissionRule allowA = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule allowB = specific("Bash", PermissionBehavior.ALLOW, "npm *");

        assertTrue(UnreachableRuleDetector.detect(List.of(allowA, allowB)).isEmpty());
        assertTrue(UnreachableRuleDetector.detect(List.of(allowB, allowA)).isEmpty());
    }

    @Test
    void rulesForDifferentToolsDoNotCrossShadow() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule deny = toolWide("Write", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);

        assertTrue(UnreachableRuleDetector.detect(List.of(allow, deny)).isEmpty());
    }

    @Test
    void emptyRuleListReturnsEmpty() {
        assertTrue(UnreachableRuleDetector.detect(List.of()).isEmpty());
    }

    @Test
    void unreachableRuleRecordExposesReasonAndFixText() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule deny = toolWide("Bash", PermissionBehavior.DENY, RuleSource.PROJECT_SETTINGS);

        UnreachableRule result = UnreachableRuleDetector.detect(List.of(allow, deny)).getFirst();


        assertEquals("Bash(git *)", result.ruleDisplay());

// the renderer prepends ruleDisplay). Source shown via lowercase display name.
        assertEquals("Blocked by \"Bash\" deny rule (from shared project settings)", result.reason());

        assertEquals("Remove the \"Bash\" deny rule from shared project settings, "
            + "or remove the specific allow rule from user settings", result.fix());
        assertEquals(Optional.of("git *"), allow.pattern());
    }

    @Test
    void askShadowUsesTsShadowedByWording() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule ask = toolWide("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);

        UnreachableRule result = UnreachableRuleDetector.detect(List.of(allow, ask)).getFirst();

        assertEquals("Shadowed by \"Bash\" ask rule (from user settings)", result.reason());
        assertTrue(Strings.CS.contains(result.fix(), "ask rule"));
    }

    @Test
    void sandboxAutoAllowExemptsPersonalToolWideBashAsk() {
        PermissionRule allow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule ask = toolWide("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);

        assertTrue(UnreachableRuleDetector.detect(List.of(allow, ask), true).isEmpty());
        assertEquals(1, UnreachableRuleDetector.detect(List.of(allow, ask), false).size());
    }

    @Test
    void sandboxAutoAllowDoesNotExemptSharedBashAskOrOtherTools() {
        PermissionRule bashAllow = specific("Bash", PermissionBehavior.ALLOW, "git *");
        PermissionRule sharedAsk = toolWide(
            "Bash", PermissionBehavior.ASK, RuleSource.PROJECT_SETTINGS);
        PermissionRule readAllow = specific("Read", PermissionBehavior.ALLOW, "src/**");
        PermissionRule readAsk = toolWide(
            "Read", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);

        assertEquals(1,
            UnreachableRuleDetector.detect(List.of(bashAllow, sharedAsk), true).size());
        assertEquals(1,
            UnreachableRuleDetector.detect(List.of(readAllow, readAsk), true).size());
    }
}
