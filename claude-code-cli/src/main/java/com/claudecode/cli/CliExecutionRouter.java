package com.claudecode.cli;

import java.util.function.IntSupplier;

/**
 * Selects the CLI execution mode after a session has been fully assembled.
 *
 * <ul>
 *   <li>routes structured SDK input before ordinary
 *       print/no-interactive handling.</li>
 *   <li>runs a finite headless turn when a prompt
 *       is present and exits cleanly for no-interactive launches without one.</li>
 *   <li>starts the interactive terminal only
 *       after every non-interactive route has been excluded.</li>
 * </ul>
 */
final class CliExecutionRouter {

    private CliExecutionRouter() {}

    /**
     * The runners are injected as explicit launch-time actions so selection is
     * deterministic and does not reach back into Picocli fields or resources.
     */
    record Request(
            boolean sdkStreamJson,
            boolean printMode,
            boolean noInteractive,
            boolean hasInitialPrompt,
            IntSupplier sdkRunner,
            IntSupplier headlessRunner,
            IntSupplier interactiveRunner) {}

    static int route(Request request) {
        if (request.sdkStreamJson()) return request.sdkRunner().getAsInt();
        if (request.printMode() && request.hasInitialPrompt()) {
            return request.headlessRunner().getAsInt();
        }
        if (request.noInteractive() && request.hasInitialPrompt()) {
            return request.headlessRunner().getAsInt();
        }
        if (request.noInteractive()) return 0;
        return request.interactiveRunner().getAsInt();
    }

    static boolean shouldHideInteractiveTools(boolean promptNonInteractive, String inputFormat) {
        return false;
    }
}
