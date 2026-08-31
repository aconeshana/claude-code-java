package com.claudecode.ui.lanterna.components;

import com.claudecode.core.model.ModelNames;

/**
 * Converts raw model IDs (e.g., {@code "claude-sonnet-4-6"}) to human-readable display names (e.g.,
 * {@code "Sonnet 4.6"}).
 */
public final class ModelDisplayName {

    private ModelDisplayName() {}

    /**
     * Convert a raw model ID to a display name. Returns the input unchanged
     * if no mapping is found.
     */
    public static String render(String model) {
        return ModelNames.displayName(model);
    }
}
