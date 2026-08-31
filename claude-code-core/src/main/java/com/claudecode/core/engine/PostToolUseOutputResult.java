package com.claudecode.core.engine;

/** Result of validating, remapping, and reprocessing a PostToolUse output. */
public sealed interface PostToolUseOutputResult {
    record Applied(ToolResult result) implements PostToolUseOutputResult {}
    record Rejected(String reason) implements PostToolUseOutputResult {}
}
