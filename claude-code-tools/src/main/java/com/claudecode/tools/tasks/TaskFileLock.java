package com.claudecode.tools.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task-specific cross-process lock compatible with the released proper-lockfile protocol.
 */
final class TaskFileLock {

    private static final int MAX_RETRIES = 30;
    private static final Duration MIN_RETRY_DELAY = Duration.ofMillis(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration STALE_AFTER = Duration.ofSeconds(10);
    private static final Duration UPDATE_INTERVAL = Duration.ofSeconds(5);

    private TaskFileLock() {}

    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

    static <T> T withLock(Path target, Operation<T> operation) throws IOException {
        Path realTarget = target.toRealPath();
        Path companion = realTarget.resolveSibling(realTarget.getFileName() + ".lock");
        try (LockHandle ignored = acquire(companion)) {
            return operation.run();
        }
    }

    private static LockHandle acquire(Path companion) throws IOException {
        int retries = 0;
        Duration delay = MIN_RETRY_DELAY;
        while (true) {
            try {
                Files.createDirectory(companion);
                BasicFileAttributes attributes = attributes(companion);
                return new LockHandle(companion, attributes.fileKey());
            } catch (FileAlreadyExistsException e) {
                if (removeUnlockedLegacyFile(companion) || removeStaleDirectory(companion)) {
                    continue;
                }
                if (retries >= MAX_RETRIES) {
                    throw new IOException("Lock file is already being held", e);
                }
                retries++;
                sleep(delay);
                delay = delay.multipliedBy(2).compareTo(MAX_RETRY_DELAY) > 0
                    ? MAX_RETRY_DELAY : delay.multipliedBy(2);
            }
        }
    }

    /** Migrates the regular lock files left by the previous Java-only FileChannel protocol. */
    private static boolean removeUnlockedLegacyFile(Path companion) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = attributes(companion);
        } catch (NoSuchFileException _) {
            return true;
        }
        if (!attributes.isRegularFile()) return false;

        boolean deleteAfterClose = false;
        try (FileChannel channel = FileChannel.open(companion, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException _) {
                return false;
            }
            if (lock == null) return false;
            try (lock) {
                try {
                    return Files.deleteIfExists(companion);
                } catch (AccessDeniedException _) {
                    // Windows may require the channel to close before deleting the old artifact.
                    deleteAfterClose = true;
                }
            }
        } catch (NoSuchFileException _) {
            return true;
        }
        if (!deleteAfterClose) return false;
        try {
            return Files.deleteIfExists(companion);
        } catch (AccessDeniedException _) {
            return false;
        }
    }

    private static boolean removeStaleDirectory(Path companion) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = attributes(companion);
        } catch (NoSuchFileException _) {
            return true;
        }
        if (!attributes.isDirectory()) return false;
        Instant staleBefore = Instant.now().minus(STALE_AFTER);
        if (!attributes.lastModifiedTime().toInstant().isBefore(staleBefore)) return false;
        try {
            return Files.deleteIfExists(companion);
        } catch (NoSuchFileException _) {
            return true;
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void sleep(Duration delay) throws IOException {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for task lock");
        }
    }

    private static final class LockHandle implements AutoCloseable {
        private final Path companion;
        private final Object fileKey;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread heartbeat;

        private LockHandle(Path companion, Object fileKey) {
            this.companion = companion;
            this.fileKey = fileKey;
            heartbeat = Thread.ofVirtual()
                .name("task-file-lock-heartbeat")
                .start(this::heartbeatLoop);
        }

        private void heartbeatLoop() {
            try {
                while (!closed.get()) {
                    Thread.sleep(UPDATE_INTERVAL);
                    if (closed.get() || !ownsCurrentDirectory()) return;
                    Files.setLastModifiedTime(companion, FileTime.from(Instant.now()));
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } catch (IOException _) {
                // A removed or replaced lock is treated as compromised; never touch its successor.
            }
        }

        private boolean ownsCurrentDirectory() throws IOException {
            BasicFileAttributes current;
            try {
                current = attributes(companion);
            } catch (NoSuchFileException _) {
                return false;
            }
            return current.isDirectory()
                && (fileKey == null || Objects.equals(fileKey, current.fileKey()));
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            heartbeat.interrupt();
            if (ownsCurrentDirectory()) {
                Files.deleteIfExists(companion);
            }
        }
    }
}
