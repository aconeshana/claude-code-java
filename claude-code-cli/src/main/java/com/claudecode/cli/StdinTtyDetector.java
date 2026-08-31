package com.claudecode.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects whether file descriptor zero itself is the controlling terminal.
 */
final class StdinTtyDetector {

    private static final Path DEV_STDIN = Path.of("/dev/stdin");
    private static final Path DEV_TTY = Path.of("/dev/tty");

    private StdinTtyDetector() {}

    static boolean isStdinTty() {
        if (File.separatorChar != '/') {
            return System.console() != null;
        }
        try {
            // Files.isSameFile follows /dev/stdin's fd 0 symlink and compares
            // the underlying device, so pipes, regular files, and /dev/null do
            // not pass merely because they are character/FIFO devices.
            return Files.isSameFile(DEV_STDIN, DEV_TTY);
        } catch (IOException | SecurityException | UnsupportedOperationException _) {
            // Fall back only when the POSIX device view is unavailable. This
            // keeps non-POSIX filesystems usable without reintroducing the
            // character-device false positive on normal Unix systems.
            return System.console() != null;
        } catch (LinkageError _) {
            return System.console() != null;
        }
    }
}
