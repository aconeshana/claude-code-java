package com.claudecode.runtime.shutdown;

/**
 * Application shutdown boundary used by interactive surfaces.
 */
@FunctionalInterface
public interface ShutdownPort {

    /**
     * Persist critical session state before the interactive surface releases its terminal.
     */
    default void prepare(String reason, int exitCode) {}

    void shutdown(String reason, int exitCode);

    static ShutdownPort noop() {
        return (_, _) -> {};
    }
}
