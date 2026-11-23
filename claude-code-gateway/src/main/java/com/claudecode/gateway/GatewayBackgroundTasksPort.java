package com.claudecode.gateway;

import java.util.List;
import java.util.Optional;

/**
 * Consumer-owned boundary for background shell tasks and sub-agent continuation
 * / interruption controls.
 *
 * <p>The gateway module must not depend on {@code claude-code-tools} (where
 * {@code TaskRegistry}, {@code LocalShellTask}, {@code LocalAgentTask}, and
 * {@code AgentContinuationService} live), so this port speaks plain immutable
 * records and strings. The CLI composition root wires it against the live
 * task registry and session engines.
 */
public interface GatewayBackgroundTasksPort {

    /**
     * Summary row for one background shell task in the header switcher menu.
     * {@code status} is one of {@code "running"}, {@code "completed"},
     * {@code "failed"}, {@code "killed"}.
     */
    record ShellEntry(
        String id,
        String command,
        String description,
        String status,
        long startTimeMs,
        Long endTimeMs,
        Integer exitCode) {}

    /**
     * Full detail payload for one background shell task, including its captured
     * stdout/stderr tail.
     */
    record ShellOutput(
        String id,
        String command,
        String description,
        String status,
        long startTimeMs,
        Long endTimeMs,
        Integer exitCode,
        String output,
        boolean truncated) {}

    /**
     * Outcome of submitting a follow-up prompt to a sub-agent.
     * {@code status} is {@code "queued"} (delivered to a running agent),
     * {@code "resumed"} (restarted from its persisted sidechain transcript), or
     * {@code "unavailable"} / {@code "error"}.
     */
    record AgentPromptResult(
        boolean ok,
        String status,
        String message) {

        public static AgentPromptResult queued() {
            return new AgentPromptResult(true, "queued", null);
        }

        public static AgentPromptResult resumed() {
            return new AgentPromptResult(true, "resumed", null);
        }

        public static AgentPromptResult unavailable(String message) {
            return new AgentPromptResult(false, "unavailable", message);
        }

        public static AgentPromptResult error(String message) {
            return new AgentPromptResult(false, "error", message);
        }
    }

    /** Every known background shell task, newest first. */
    default List<ShellEntry> listShells() {
        return List.of();
    }

    /** Detail and captured output for one background shell task, or empty if unknown. */
    default Optional<ShellOutput> shellOutput(String taskId) {
        return Optional.empty();
    }

    /** Terminates a running background shell task; returns true if a live task was stopped. */
    default boolean stopShell(String taskId) {
        return false;
    }

    /**
     * Sends a user follow-up prompt to a sub-agent: queues it when the agent is
     * currently running, or resumes the agent from its sidechain transcript when
     * idle/completed.
     */
    default AgentPromptResult promptAgent(String sessionId, String agentId, String prompt) {
        return AgentPromptResult.unavailable("sub-agent continuation is not configured");
    }

    /** Stops a currently running sub-agent; returns true if a live agent was stopped. */
    default boolean stopAgent(String sessionId, String agentId) {
        return false;
    }

    /** True when the sub-agent currently has an active in-process run. */
    default boolean isAgentRunning(String agentId) {
        return false;
    }
}
