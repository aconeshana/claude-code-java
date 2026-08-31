package com.claudecode.core.engine;

/**
 * Return type for a tool's {@code call} when it wants to hand back both the text shown to the model
 * and a structured payload for downstream consumers.
 */
public record StructuredToolOutput(String text, Object toolUseResult) {
}
