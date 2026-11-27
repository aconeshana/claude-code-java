package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a macOS idle-sleep assertion while LAN model sharing is active so closing or stepping away
 * from the Mac does not drop streaming requests from Windows/Linux peers.
 *
 * <p>Uses {@code /usr/bin/caffeinate -i -w <pid>}, which binds the assertion lifetime to the
 * current JVM PID in the kernel — if the JVM exits abruptly, {@code caffeinate} exits
 * immediately with zero orphan processes. Safe no-op on Windows and Linux.
 */
@Explanation("Prevents macOS idle sleep while serving LAN model gateway requests")
final class MacAwakeGuard implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MacAwakeGuard.class);
    private static final Path CAFFEINATE_BIN = Path.of("/usr/bin/caffeinate");

    private Process child;

    synchronized void sync(boolean shouldHoldAwake) {
        if (!shouldHoldAwake) {
            stopChild();
            return;
        }
        if (child != null && child.isAlive()) return;
        child = null;
        if (!Platform.IS_DARWIN || !Files.isExecutable(CAFFEINATE_BIN)) return;
        long pid = ProcessHandle.current().pid();
        try {
            child = new ProcessBuilder(List.of(
                    CAFFEINATE_BIN.toString(), "-i", "-w", Long.toString(pid)))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            log.debug("Started macOS awake guard (caffeinate -i -w {})", pid);
        } catch (Exception failure) {
            log.debug("Unable to start macOS awake guard: {}", failure.toString());
        }
    }

    synchronized boolean active() {
        return child != null && child.isAlive();
    }

    @Override
    public synchronized void close() {
        stopChild();
    }

    private void stopChild() {
        Process running = child;
        child = null;
        if (running != null && running.isAlive()) {
            running.destroy();
        }
    }
}
