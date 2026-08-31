package com.claudecode.core.engine;

/**
 * One completed tool call — name plus its input/output, both kept as opaque JSON-serializable
 * values (never parsed, only stringified for a prompt).
 */
public record ToolCallInfo(String name, Object input, Object output) {}
