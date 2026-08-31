package com.claudecode.tools.agent;

import java.util.Optional;

/**
 * Failure/control result; execution statistics are intentionally absent.
 *
 * <ul>
 *   <li>failed agent execution is
 *       surfaced as an error result rather than a successful usage payload.</li>
 * </ul>
 */
public record AgentFailureResult(String message) implements SubAgentResult {

    @Override
    public String output() {
        return "Error: " + message;
    }

    @Override
    public Optional<String> error() {
        return Optional.ofNullable(message);
    }

    @Override
    public SubAgentTermination termination() {
        return SubAgentTermination.FAILED;
    }
}
