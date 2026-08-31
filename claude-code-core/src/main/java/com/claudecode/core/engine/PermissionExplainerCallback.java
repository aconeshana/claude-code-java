package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generates a {@link PermissionExplanation} by calling the LLM (Haiku) with the tool name, input,
 * and optional description.
 */
@FunctionalInterface
public interface PermissionExplainerCallback {
    /**
     * Generate an explanation for the given tool use. Blocking — runs the
     * LLM call synchronously; must be called from a Virtual Thread.
     *
     * @param toolName    the tool being invoked
     * @param input       the tool's input JSON (may be null)
     * @param description the tool-supplied description (may be null)
     * @return explanation, or null if the feature is disabled or request failed
     */
    PermissionExplanation explain(String toolName, JsonNode input, String description);
}
