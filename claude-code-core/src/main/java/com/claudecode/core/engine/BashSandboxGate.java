package com.claudecode.core.engine;



/**
 * Supplies whether a Bash command will run inside the native sandbox, so the permission engine can
 * auto-allow the Bash permission when {@code sandbox.autoAllowBashIfSandboxed} is enabled.
 */
@FunctionalInterface
public interface BashSandboxGate {

    /**
     * @param command                  the Bash command text
     * @param dangerouslyDisableSandbox the model's per-call opt-out flag
     * @return true when the command would actually run sandboxed (so its
     *         permission may be auto-allowed)
     */
    boolean shouldAutoAllow(String command, boolean dangerouslyDisableSandbox);
}
