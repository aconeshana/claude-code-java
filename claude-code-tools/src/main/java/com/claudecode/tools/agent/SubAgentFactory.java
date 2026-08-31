package com.claudecode.tools.agent;

/**
 * Factory interface for creating and running sub-agent instances.
 * Implementations create a new QuerySession with restricted tool set
 * and independent message history.
 *
 * <ul>
 *   <li>the detached async
 *       lifecycle starts synchronously through the child stream's first await;
 *       {@link #supportsFirstModelRequestSignal} lets the Java launcher retain
 *       that startup ordering without waiting for the child to complete.</li>
 * </ul>
 */
public interface SubAgentFactory {

    /**
     * Runs a sub-agent with the given request parameters.
     *
     * @param request the sub-agent configuration and prompt
     * @return the result of the sub-agent execution
     */
    SubAgentResult runSubAgent(SubAgentRequest request);

/** Whether this factory invokes {@link SubAgentRequest#beforeFirstModelRequest}. */
    default boolean supportsFirstModelRequestSignal() {
        return false;
    }
}
