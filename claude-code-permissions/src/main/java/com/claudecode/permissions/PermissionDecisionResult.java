package com.claudecode.permissions;

/**
 * Result of {@link PermissionEngine#evaluateDetailed}: bundles the decision with an explanation of
 * why it was reached.
 */
public record PermissionDecisionResult(
    PermissionDecision decision,
    DecisionReason reason
) {}
