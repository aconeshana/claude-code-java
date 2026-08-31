package com.claudecode.permissions;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionRuleDescriptionTest {

    @Test
    void bashWithColonStarPattern_describesAsPrefix() {
        PermissionRule rule = PermissionRule.withPattern("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS, "git:*");
        assertEquals(Optional.of("Any Bash command starting with git"), PermissionRuleDescription.describe(rule));
    }

    @Test
    void bashWithExactPattern_describesAsExactCommand() {
        PermissionRule rule = PermissionRule.withPattern("Bash", PermissionBehavior.DENY, RuleSource.LOCAL_SETTINGS, "rm -rf /");
        assertEquals(Optional.of("The Bash command rm -rf /"), PermissionRuleDescription.describe(rule));
    }

    @Test
    void bashWithNoPattern_describesAsAnyBashCommand() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);
        assertEquals(Optional.of("Any Bash command"), PermissionRuleDescription.describe(rule));
    }

    @Test
    void otherToolWithNoPattern_describesAsAnyUseOfTool() {
        PermissionRule rule = PermissionRule.of("WebFetch", PermissionBehavior.ALLOW, RuleSource.PROJECT_SETTINGS);
        assertEquals(Optional.of("Any use of the WebFetch tool"), PermissionRuleDescription.describe(rule));
    }

    @Test
    void otherToolWithPattern_hasNoDescription() {
        PermissionRule rule = PermissionRule.withPattern("Write", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS, "*.md");
        assertEquals(Optional.empty(), PermissionRuleDescription.describe(rule));
    }
}
