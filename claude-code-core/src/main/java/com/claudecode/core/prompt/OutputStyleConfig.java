package com.claudecode.core.prompt;

/**
 * Output style configuration.
 */
public record OutputStyleConfig(
    String name,
    String description,
    String prompt,
    boolean keepCodingInstructions,
    Source source,
    boolean forceForPlugin
) {

    /**
     * Origin of an {@link OutputStyleConfig}.
     */
    public enum Source {
        BUILT_IN,
        PROJECT_SETTINGS,
        USER_SETTINGS,
        POLICY_SETTINGS,
        PLUGIN
    }

    /**
     * Convenience factory for built-in styles (BUILT_IN source, non-forced).
     */
    public static OutputStyleConfig builtIn(
            String name, String description, String prompt, boolean keepCodingInstructions) {
        return new OutputStyleConfig(
            name, description, prompt, keepCodingInstructions, Source.BUILT_IN, false);
    }
}
