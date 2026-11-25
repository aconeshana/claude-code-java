package com.claudecode.core.engine;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Presentation-neutral filter governing progressive (session-scoped + persistent default)
 * enablement of Built-in Tools, Skills, and MCP servers/tools without mutating a live
 * session's cached {@code tools[]} or {@code system[]} prefix.
 */
public interface SessionCapabilityFilter {

    /** Tools required as the immutable progressive-loading kernel when progressive mode is on. */
    Set<String> KERNEL_TOOLS = Set.of("ToolSearch", "ToolInvoke", "Skill", "StructuredOutput");

    /** True when unified progressive tool discovery/invocation (Path B) is active. */
    default boolean isProgressiveModeEnabled() {
        return true;
    }

    /** Whether a built-in (non-MCP) tool is currently enabled in {@code sessionId}. */
    boolean isBuiltInToolEnabled(String sessionId, String toolName);

    /** Whether an MCP server is currently enabled in {@code sessionId}. */
    boolean isMcpServerEnabled(String sessionId, String serverName);

    /** Whether a specific MCP tool ({@code mcp__<server>__<tool>}) is enabled in {@code sessionId}. */
    boolean isMcpToolEnabled(String sessionId, String fullToolName);

    /** Whether a skill is currently enabled in {@code sessionId}. */
    boolean isSkillEnabled(String sessionId, String skillName);

    /**
     * Returns the frozen Turn-1 eager built-in tool name set for {@code sessionId}, computing and
     * latching it on first request so mid-session toggles never mutate wire {@code tools[]}.
     */
    Set<String> frozenInitialEagerTools(String sessionId, Supplier<Set<String>> initialSupplier);
}
