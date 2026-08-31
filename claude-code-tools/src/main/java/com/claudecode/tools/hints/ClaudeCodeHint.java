package com.claudecode.tools.hints;

/**
 * A single Claude Code hint parsed from tool output.
 */
public record ClaudeCodeHint(int v, String type, String value, String sourceCommand) {
}
