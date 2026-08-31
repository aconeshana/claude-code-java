package com.claudecode.core.prompt;

import java.util.function.Supplier;

/**
 * A single named, memoizable slice of the system prompt.
 */
public record SystemPromptSection(
    String name,
    Supplier<String> compute,
    boolean cacheBreak
) {

    /**
     * Factory for a cached section.
     */
    public static SystemPromptSection cached(String name, Supplier<String> compute) {
        return new SystemPromptSection(name, compute, false);
    }

    /**
     * Factory for an uncached section.
     */
    @SuppressWarnings("unused")
    public static SystemPromptSection uncached(
            String name, Supplier<String> compute, String reason) {
        return new SystemPromptSection(name, compute, true);
    }
}
