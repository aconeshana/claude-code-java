package com.claudecode.runtime.statusline;

import java.util.Optional;

/**
 * Executes the configured status-line command without exposing settings or process-management
 * implementations to the UI module.
 */
@FunctionalInterface
public interface StatusLinePort {

    record Output(String text, int padding) {}

    Optional<Output> render(String inputJson);

    static StatusLinePort disabled() {
        return _ -> Optional.empty();
    }
}
