package com.claudecode.core.message;

/**
 * Per-turn signal that plan mode was just exited this session, feeding the {@code plan_mode_exit}
 * attachment.
 */
public record PlanModeExitInfo(String planFilePath, boolean planExists) {
}
