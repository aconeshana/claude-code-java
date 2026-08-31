package com.claudecode.tools;

import com.claudecode.core.engine.ToolResult;

/**
 * The two result channels produced by one tool invocation.
 *
 * @param rawResult the tool's public Java return value
 * @param mappedResult the optional model-facing result; {@code null} requests default mapping
 */
public record ToolCallResult<O>(O rawResult, ToolResult mappedResult) {}
