package com.claudecode.cli;

/**
 * Short-circuits a launch phase after it has already emitted a CLI-compatible
 * diagnostic.
 *
 * <ul>
 *   <li>startup validation returns a normal process
 *       exit status without entering the top-level fatal-error handler.</li>
 * </ul>
 */
final class CliLaunchAbort extends RuntimeException {

    private final int exitCode;

    CliLaunchAbort(int exitCode) {
        super(null, null, false, false);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
