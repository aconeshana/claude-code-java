package com.claudecode.core.process;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Best-effort descendant-first termination for subprocess lifecycle boundaries.
 */
public final class ProcessTreeTerminator {

    private static final long POLL_NANOS = Duration.ofMillis(10).toNanos();

    private ProcessTreeTerminator() {}

    public static void terminate(Process process, Duration gracePeriod) {
        if (process == null) return;
        ProcessHandle root = process.toHandle();
        List<ProcessHandle> descendants = root.descendants()
            .sorted(Comparator.comparingInt(ProcessTreeTerminator::depth).reversed())
            .toList();

        descendants.forEach(ProcessTreeTerminator::destroy);
        destroy(root);
        awaitExit(root, descendants, gracePeriod);

        descendants.forEach(ProcessTreeTerminator::destroyForcibly);
        destroyForcibly(root);
        awaitExit(root, descendants, Duration.ofMillis(250));
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        ProcessHandle current = handle;
        while (current.parent().isPresent() && depth < 1_024) {
            current = current.parent().orElseThrow();
            depth++;
        }
        return depth;
    }

    private static void awaitExit(
            ProcessHandle root, List<ProcessHandle> descendants, Duration gracePeriod) {
        long waitNanos = gracePeriod == null ? 0L : Math.max(0L, gracePeriod.toNanos());
        long deadline = System.nanoTime() + waitNanos;
        while (anyAlive(root, descendants) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Math.min(POLL_NANOS, deadline - System.nanoTime()));
            if (Thread.currentThread().isInterrupted()) return;
        }
    }

    private static boolean anyAlive(ProcessHandle root, List<ProcessHandle> descendants) {
        if (root.isAlive()) return true;
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) return true;
        }
        return false;
    }

    private static void destroy(ProcessHandle handle) {
        try {
            if (handle.isAlive()) handle.destroy();
        } catch (RuntimeException _) {
            // Best effort; the forcible pass follows after the grace period.
        }
    }

    private static void destroyForcibly(ProcessHandle handle) {
        try {
            if (handle.isAlive()) handle.destroyForcibly();
        } catch (RuntimeException _) {
            // The process may have exited or become inaccessible concurrently.
        }
    }
}
