package com.claudecode.tools.agent;

/**
 * Structured terminal state of one sub-agent turn.
 *
 * <ul>
 *   <li>SDK result types for success, maximum
 *       budget, maximum turns, interruption, and execution failure.</li>
 *   <li>only a normal terminal
 *       result is mapped to the completed Agent tool-result variant.</li>
 * </ul>
 */
public enum SubAgentTermination {
    COMPLETED,
    MAX_BUDGET,
    MAX_TURNS,
    INTERRUPTED,
    FAILED
}
