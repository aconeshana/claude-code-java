package com.claudecode.permissions;


/**
 * A permission rule that can never fire because a broader, tool-wide rule evaluates first and
 * shadows it.
 */
public record UnreachableRule(
    PermissionRule rule,
    PermissionRule shadowedBy,
    ShadowType shadowType,
    String reason,
    String fix
) {
    public enum ShadowType { DENY, ASK }

    /**
     * The shadowed rule's value as displayed to the user — {@code "Tool"} or {@code "Tool(pattern)"}.
     */
    public String ruleDisplay() {
        return rule.pattern().map(p -> rule.toolName() + "(" + p + ")").orElse(rule.toolName());
    }
}
