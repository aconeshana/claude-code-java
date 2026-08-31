package com.claudecode.services.dream;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.process.ProcessUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * Lock file whose mtime IS lastConsolidatedAt; body is the holder's PID.
 */
public final class DreamLock {

    private static final String LOCK_FILE = ".consolidate-lock";

    /** Stale past this even if the PID is live (PID-reuse guard). */
    private static final long HOLDER_STALE_MS = 60 * 60 * 1000L;

    private final Path lockPath;

    public DreamLock(Path memoryRoot) {
        this.lockPath = memoryRoot.resolve(LOCK_FILE);
    }

    /**
     * mtime of the lock file = lastConsolidatedAt. 0 if absent.
     * Per-turn cost: one stat.
     */
    public long readLastConsolidatedAt() {
        try {
            return FileUtils.modificationTimeMillis(lockPath);
        } catch (IOException _) {
            return 0L;
        }
    }

    /**
     * Acquire: write PID → mtime = now. Returns the pre-acquire mtime (for
     * rollback), or {@code -1} if blocked by a live holder or a lost race.
     *
     * <ul>
     *   <li>Success → do nothing; mtime stays at now.</li>
     *   <li>Failure → caller rewinds via {@link #rollback(long)}.</li>
     *   <li>Crash   → mtime stuck, dead PID → next process reclaims.</li>
     * </ul>
     */
    public long tryAcquire() {
        long mtimeMs = -1;
        long holderPid = -1;
        if (Files.exists(lockPath)) {
            try {
                mtimeMs = FileUtils.modificationTimeMillis(lockPath);
                String raw = Files.readString(lockPath).trim();
                if (!raw.isEmpty()) {
                    try {
                        holderPid = Long.parseLong(raw);
                    } catch (NumberFormatException _) {
                        // unparseable body → treat as reclaimable
                    }
                }
            } catch (IOException _) {
                // stat/read failure → treat as no prior lock
            }
        }

        long now = System.currentTimeMillis();
        if (mtimeMs >= 0 && now - mtimeMs < HOLDER_STALE_MS) {
            if (holderPid > 0 && ProcessUtils.isProcessRunning(holderPid)) {
                return -1; // held by a live process
            }
            // dead PID or unparseable body → reclaim
        }

        long prior = (mtimeMs >= 0) ? mtimeMs : 0L;
        try {
            Files.createDirectories(lockPath.getParent());
            Files.writeString(lockPath, String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException _) {
            return -1;
        }

        // Two reclaimers both write → last wins the PID. Loser bails on re-read.
        try {
            String verify = Files.readString(lockPath).trim();
            long verifyPid = Long.parseLong(verify);
            if (verifyPid != ProcessHandle.current().pid()) {
                return -1;
            }
        } catch (IOException | NumberFormatException _) {
            return -1;
        }
        return prior;
    }

    /**
     * Rewind mtime to pre-acquire after a failed fork. Clears the PID body so
     * our still-running process doesn't look like it's holding. prior {@code <= 0}
     * → unlink (restore no-file).
     */
    public void rollback(long priorMtime) {
        try {
            if (priorMtime <= 0) {
                Files.deleteIfExists(lockPath);
                return;
            }
            Files.writeString(lockPath, "");
            FileTime t = FileTime.fromMillis(priorMtime);
            Files.setLastModifiedTime(lockPath, t);
        } catch (IOException _) {
            // next trigger delayed to minHours
        }
    }

    /**
     * Stamp from manual /dream. Optimistic — fires at prompt-build time, no
     * post-skill completion hook. Best-effort.
     */
    public void recordConsolidation() {
        try {
            Files.createDirectories(lockPath.getParent());
            Files.writeString(lockPath, String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException _) {
            // best-effort
        }
    }

}
