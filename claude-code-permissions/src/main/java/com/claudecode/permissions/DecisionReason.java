package com.claudecode.permissions;

import java.util.Map;

/**
 * Explains why a {@link PermissionDecision} was reached.
 */
public sealed interface DecisionReason
        permits DecisionReason.Rule,
                DecisionReason.Mode,
                DecisionReason.SubcommandResults,
                DecisionReason.PermissionPromptTool,
                DecisionReason.Hook,
                DecisionReason.AsyncAgent,
                DecisionReason.SandboxOverride,
                DecisionReason.Classifier,
                DecisionReason.WorkingDir,
                DecisionReason.SafetyCheck,
                DecisionReason.Other {

/**
     * An explicit rule matched.
     */
    record Rule(PermissionRule rule) implements DecisionReason {}

/**
     * No rule matched — decided by the current permission mode.
     */
    record Mode(PermissionMode mode) implements DecisionReason {}

/**
     * Per-subcommand decisions from a batch tool.
     */
    record SubcommandResults(Map<String, PermissionDecisionResult> reasons) implements DecisionReason {}

/**
     * Decision came from a permission-prompt tool.
     */
    record PermissionPromptTool(String permissionPromptToolName, Object toolResult) implements DecisionReason {}

/**
     * Decision forced by a Pre/PostToolUse or PermissionRequest hook.
     */
    record Hook(String hookName, String hookSource, String reason) implements DecisionReason {}

/**
     * Decision delegated to an async agent.
     */
    record AsyncAgent(String reason) implements DecisionReason {}

/**
     * Sandbox disabled for the command.
     */
    record SandboxOverride(String reason) implements DecisionReason {}

/**
     * Decision made by the (port-unimplemented) allow classifier.
     */
    record Classifier(String classifier, String reason) implements DecisionReason {}

/**
     * Decision based on the working directory.
     */
    record WorkingDir(String reason) implements DecisionReason {}


    record SafetyCheck(String reason, boolean classifierApprovable) implements DecisionReason {}

/**
     * Catch-all / other reason.
     */
    record Other(String reason) implements DecisionReason {}
}
