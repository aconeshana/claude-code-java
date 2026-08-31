package com.claudecode.tools.mcp;

/**
 * Lifecycle-owning MCP runtime.
 *
 * <p>Only the composition root should hold this type in a
 * try-with-resources block. It exposes the non-closing {@link McpRuntime}
 * contract to every borrowed consumer.
 */
public interface ManagedMcpRuntime extends McpRuntime, AutoCloseable {
}
