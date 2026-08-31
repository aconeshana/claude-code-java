package com.claudecode.core.prompt;

/**
 * Lightweight input record for {@link SystemPromptSections#getMcpInstructionsSection}.
 */
public record McpInstructionEntry(String name, String instructions) {}
