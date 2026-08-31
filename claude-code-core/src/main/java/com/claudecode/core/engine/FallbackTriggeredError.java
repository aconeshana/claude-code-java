package com.claudecode.core.engine;

/**
 * Thrown by an API client (or streaming iterator) when the primary model should be switched to a
 * fallback model.
 */
public class FallbackTriggeredError extends RuntimeException {

    private final String originalModel;
    private final String fallbackModel;

    public FallbackTriggeredError(String originalModel, String fallbackModel) {
        super("Falling back from " + originalModel + " to " + fallbackModel);
        this.originalModel = originalModel;
        this.fallbackModel = fallbackModel;
    }

    public String originalModel() { return originalModel; }
    public String fallbackModel() { return fallbackModel; }
}
