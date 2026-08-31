package com.claudecode.runtime.hooks;

import java.util.List;

/**
 * Headless application port for loading and observing the resolved hook configuration.
 */
public interface HookConfigurationPort {

    HookConfigurationSnapshot snapshot(String workingDirectory, List<String> toolNames);

    default AutoCloseable subscribeReload(Runnable listener) {
        return () -> { };
    }

    /** Clears hooks registered dynamically by an invoked skill. */
    default void clearSessionHooks() {}
}
