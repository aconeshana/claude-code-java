package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects permission rules that can never fire because a broader, tool-wide rule shadows them.
 */
public final class UnreachableRuleDetector {

    private UnreachableRuleDetector() {}

    public static List<UnreachableRule> detect(List<PermissionRule> rules) {
        return detect(rules, false);
    }

    public static List<UnreachableRule> detect(
            List<PermissionRule> rules, boolean sandboxAutoAllowEnabled) {
        List<PermissionRule> toolWideDeny = toolWideRules(rules, PermissionBehavior.DENY);
        List<PermissionRule> toolWideAsk = toolWideRules(rules, PermissionBehavior.ASK);

        List<UnreachableRule> out = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule.behavior() != PermissionBehavior.ALLOW || rule.pattern().isEmpty()) {
                continue;
            }
            PermissionRule denyHit = findToolWide(toolWideDeny, rule.toolName());
            if (denyHit != null) {
                out.add(build(rule, denyHit, UnreachableRule.ShadowType.DENY));
                continue;
            }
            PermissionRule askHit = findToolWide(toolWideAsk, rule.toolName());
            if (askHit != null) {
                if (sandboxAutoAllowEnabled
                        && Strings.CS.equals("Bash", rule.toolName())
                        && !isSharedSource(askHit.source())) {
                    continue;
                }
                out.add(build(rule, askHit, UnreachableRule.ShadowType.ASK));
            }
        }
        return out;
    }

    private static boolean isSharedSource(RuleSource source) {
        return source == RuleSource.PROJECT_SETTINGS
            || source == RuleSource.POLICY_SETTINGS
            || source == RuleSource.COMMAND;
    }

    private static List<PermissionRule> toolWideRules(List<PermissionRule> rules, PermissionBehavior behavior) {
        List<PermissionRule> out = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule.behavior() == behavior && rule.pattern().isEmpty()) {
                out.add(rule);
            }
        }
        return out;
    }

    private static PermissionRule findToolWide(List<PermissionRule> toolWide, String toolName) {
        for (PermissionRule rule : toolWide) {
            if (rule.toolName().equals(toolName)) {
                return rule;
            }
        }
        return null;
    }

    private static UnreachableRule build(PermissionRule rule, PermissionRule shadowedBy,
                                          UnreachableRule.ShadowType type) {
        boolean deny = type == UnreachableRule.ShadowType.DENY;
        String kind = deny ? "deny" : "ask";

        // shadowing (tool-wide) rule; both rules share the same tool, so it also names
        // the shadowed rule's tool.
        String reason = (deny ? "Blocked by \"" : "Shadowed by \"")
            + shadowedBy.toolName() + "\" " + kind + " rule (from "
            + shadowedBy.source().displayName() + ")";

        String fix = "Remove the \"" + shadowedBy.toolName() + "\" " + kind + " rule from "
            + shadowedBy.source().displayName()
            + ", or remove the specific allow rule from " + rule.source().displayName();
        return new UnreachableRule(rule, shadowedBy, type, reason, fix);
    }
}
