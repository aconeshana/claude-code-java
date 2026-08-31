package com.claudecode.core.engine;



/**
 * Outcome of a sandbox-eligibility decision for a single bash command.
 */
public record SandboxDecision(DecisionKind kind, String rejectReason) {

    public enum DecisionKind {
        /** Wrap and run the command inside the native sandbox. */
        RUN_SANDBOXED,
        /** Run the command directly, without a sandbox wrapper. */
        RUN_UNSANDBOXED,
        /** Refuse to run: sandbox was required but the backend is unavailable. */
        REJECT
    }

    public static SandboxDecision sandbox() {
        return new SandboxDecision(DecisionKind.RUN_SANDBOXED, null);
    }

    public static SandboxDecision unsandboxed() {
        return new SandboxDecision(DecisionKind.RUN_UNSANDBOXED, null);
    }

    public static SandboxDecision reject(String reason) {
        return new SandboxDecision(DecisionKind.REJECT, reason);
    }

    public boolean isReject() {
        return kind() == DecisionKind.REJECT;
    }

    public boolean isSandboxed() {
        return kind() == DecisionKind.RUN_SANDBOXED;
    }
}
