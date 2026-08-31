package com.claudecode.core.engine;

import com.claudecode.core.diff.DiffHunks;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.io.FileUtils;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Per-session code checkpoint / rewind backend — backs the {@code /rewind} "Restore code" and
 * "Restore code and conversation" options.
 */
public final class FileHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryManager.class);
    private static final int MAX_SNAPSHOTS = 100;
    private static final Executor SNAPSHOT_EXECUTOR = command ->
        Thread.ofVirtual().name("file-history-snapshot").start(command);


    public record Backup(String backupFileName, int version, Instant backupTime) {}


    public record Snapshot(String messageId, Map<String, Backup> trackedFileBackups, Instant timestamp) {
        public Snapshot {
            trackedFileBackups = Map.copyOf(trackedFileBackups);
        }

        Snapshot withBackup(String trackingPath, Backup backup) {
            Map<String, Backup> updated = new LinkedHashMap<>(trackedFileBackups);
            updated.put(trackingPath, backup);
            return new Snapshot(messageId, updated, timestamp);
        }
    }


    public record DiffStats(List<String> filesChanged, int insertions, int deletions) {
        public static final DiffStats EMPTY = new DiffStats(List.of(), 0, 0);
    }

    private final SessionIdentity sessionIdentity;
    private final Path workingDirectory;
    private final Path backupRoot;

    private final List<Snapshot> snapshots = new ArrayList<>();
    private final Set<String> trackedFiles = new LinkedHashSet<>();
    private final Set<String> seenExtensions = new LinkedHashSet<>();

    private CompletableFuture<Void> pendingSnapshots = CompletableFuture.completedFuture(null);
    private volatile FileHistorySnapshotSink snapshotSink;
/**
     * Fired (off the caller's thread is NOT guaranteed) when a file with a previously-unseen extension
     * is first tracked — backs the LSP plugin recommendation prompt.
     */
    private volatile Consumer<Path> newExtensionListener;

    /**
     * @param sessionIdentity shared, mutable session-id holder — read fresh on
     *                        every backup-path resolution rather than captured
     *                        once, because {@code /resume}/{@code /branch}
     *                        call {@link QueryEngine#switchToSession} on the
     *                        SAME engine (and therefore the same manager
     *                        instance) instead of constructing a new one.
     *                        matches the same requirement already documented
     *                        on {@link TranscriptSink#record}.
     */
    public FileHistoryManager(SessionIdentity sessionIdentity, Path workingDirectory, Path backupRoot) {
        this.sessionIdentity = sessionIdentity;
        this.workingDirectory = workingDirectory;
        this.backupRoot = backupRoot;
    }

    public void setSnapshotSink(FileHistorySnapshotSink sink) {
        this.snapshotSink = sink;
    }

    /** Registers the LSP-plugin-recommendation hook; invoked on first sight of a
     *  new file extension during {@link #trackEdit}. Pass null to clear. */
    public void setNewExtensionListener(Consumer<Path> listener) {
        this.newExtensionListener = listener;
    }

    // ── core operations ─────────────────────────────────────────────────────

    /**
     * Ensures the file about to be edited has a "before" backup in the most recent snapshot.
     */
    public void trackEdit(String absoluteFilePath) {
        trackEdit(absoluteFilePath, null);
    }

    /**
     * Tracks an edit and persists a retroactive snapshot update under the message that caused the
     * mutation.
     */
    public void trackEdit(String absoluteFilePath, String snapshotUpdateMessageId) {
// Submission does not await snapshot I/O, but every edit
        // awaits that pending checkpoint before mutating the file. This keeps
        // rewind correctness without putting backup work on the request path.
        awaitPendingSnapshots();
        String trackingPath = shorten(absoluteFilePath);

        synchronized (this) {
            Snapshot mostRecent = lastSnapshotOrNull();
            if (mostRecent == null) {
                // Shouldn't happen in normal operation — makeSnapshot always runs

                // recent snapshot') on this branch.
                log.warn("FileHistory: trackEdit called with no snapshot yet for {}", trackingPath);
                return;
            }
            if (mostRecent.trackedFileBackups().containsKey(trackingPath)) return;
        }

        Backup backup;
        try {
            backup = createBackup(absoluteFilePath, 1);
        } catch (IOException e) {
            log.warn("FileHistory: trackEdit backup failed for {}: {}", absoluteFilePath, e.getMessage());
            return;
        }

        Snapshot recorded;
        synchronized (this) {
            int lastIdx = snapshots.size() - 1;
            if (lastIdx < 0) return;
            Snapshot mostRecent = snapshots.get(lastIdx);
            if (mostRecent.trackedFileBackups().containsKey(trackingPath)) return; // raced with another trackEdit

            trackedFiles.add(trackingPath);
            recorded = mostRecent.withBackup(trackingPath, backup);
            snapshots.set(lastIdx, recorded);
        }
        notifySink(snapshotUpdateMessageId != null ? snapshotUpdateMessageId : recorded.messageId(),
            recorded, true);
        notifyNewExtension(absoluteFilePath);
    }

    /** If {@code absoluteFilePath}'s extension has never been seen, fire the
     *  new-extension listener (best-effort; a listener exception is swallowed). */
    private void notifyNewExtension(String absoluteFilePath) {
        Consumer<Path> listener = this.newExtensionListener;
        if (listener == null) {
            return;
        }
        String ext = extensionOf(absoluteFilePath);
        if (ext == null) {
            return;
        }
        boolean isNew;
        synchronized (this) {
            isNew = seenExtensions.add(ext);
        }
        if (!isNew) {
            return;
        }
        try {
            listener.accept(Path.of(absoluteFilePath));
        } catch (RuntimeException e) {
            log.warn("FileHistory: new-extension listener failed for {}: {}",
                absoluteFilePath, e.getMessage());
        }
    }

    private static String extensionOf(String absoluteFilePath) {
        String fileName = Path.of(absoluteFilePath).getFileName() == null
            ? "" : Path.of(absoluteFilePath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Adds a snapshot for {@code messageId}, backing up any changed tracked files. */
    public void makeSnapshot(String messageId) {
        List<String> filesToCheck;
        Snapshot lastSnapshot;
        synchronized (this) {
            filesToCheck = List.copyOf(trackedFiles);
            lastSnapshot = lastSnapshotOrNull();
        }

        Map<String, Backup> newBackups = new LinkedHashMap<>();
        for (String trackingPath : filesToCheck) {
            try {
                String absolutePath = expand(trackingPath);
                Backup latest = lastSnapshot != null ? lastSnapshot.trackedFileBackups().get(trackingPath) : null;
                int nextVersion = latest != null ? latest.version() + 1 : 1;

                if (!Files.exists(Path.of(absolutePath))) {
                    newBackups.put(trackingPath, new Backup(null, nextVersion, Instant.now()));
                    continue;
                }
                if (latest != null && latest.backupFileName() != null
                        && !checkOriginFileChanged(absolutePath, latest.backupFileName())) {
                    newBackups.put(trackingPath, latest); // unchanged since last version — reuse
                    continue;
                }
                newBackups.put(trackingPath, createBackup(absolutePath, nextVersion));
            } catch (IOException e) {
                log.warn("FileHistory: makeSnapshot backup failed for {}: {}", trackingPath, e.getMessage());
            }
        }

        Snapshot newSnapshot;
        synchronized (this) {
            // Inherit backups for files trackEdit added to the *previous* last
            // snapshot concurrently with this method's IO phase, so the new
            // snapshot covers every currently-tracked file.
            Snapshot currentLast = lastSnapshotOrNull();
            if (currentLast != null) {
                for (String trackingPath : trackedFiles) {
                    if (newBackups.containsKey(trackingPath)) continue;
                    Backup inherited = currentLast.trackedFileBackups().get(trackingPath);
                    if (inherited != null) newBackups.put(trackingPath, inherited);
                }
            }
            newSnapshot = new Snapshot(messageId, newBackups, Instant.now());
            snapshots.add(newSnapshot);
            if (snapshots.size() > MAX_SNAPSHOTS) {
                snapshots.removeFirst();
            }
        }
        notifySink(newSnapshot.messageId(), newSnapshot, false);
    }

    /**
     * Queues a snapshot without delaying prompt submission. Tasks are chained
     * per session so checkpoints retain user-message order even when backup I/O
     * takes longer than one turn's local preflight.
     */
    public void scheduleSnapshot(String messageId) {
        synchronized (this) {
            pendingSnapshots = pendingSnapshots
                .handle((_, _) -> null)
                .thenRunAsync(() -> makeSnapshot(messageId), SNAPSHOT_EXECUTOR);
        }
    }

    /**
     * Restores every tracked file to its version as of {@code messageId}'s
     * snapshot. Returns the absolute paths of files that were actually
     * changed on disk.
     *
     * @throws IllegalStateException if no snapshot matches {@code messageId}
     */
    public List<String> rewind(String messageId) {
        awaitPendingSnapshots();
        Snapshot target;
        List<String> filesToCheck;
        synchronized (this) {
            target = findLastByMessageId(messageId);
            if (target == null) {
                throw new IllegalStateException("The selected snapshot was not found");
            }
            filesToCheck = List.copyOf(trackedFiles);
        }

        List<String> changed = new ArrayList<>();
        for (String trackingPath : filesToCheck) {
            try {
                String absolutePath = expand(trackingPath);
                Backup resolved = resolveBackupForRewind(trackingPath, target);
                if (resolved == null) continue;

                if (resolved.backupFileName() == null) {
                    if (Files.deleteIfExists(Path.of(absolutePath))) {
                        changed.add(absolutePath);
                    }
                    continue;
                }

                if (checkOriginFileChanged(absolutePath, resolved.backupFileName())) {
                    restoreBackupFile(absolutePath, resolved.backupFileName());
                    changed.add(absolutePath);
                }
            } catch (IOException e) {
                log.warn("FileHistory: rewind restore failed for {}: {}", trackingPath, e.getMessage());
            }
        }
        return changed;
    }

    /** Preview of {@link #rewind}: no filesystem mutation, just a line-diff estimate. */
    public DiffStats getDiffStats(String messageId) {
        awaitPendingSnapshots();
        Snapshot target;
        List<String> filesToCheck;
        synchronized (this) {
            target = findLastByMessageId(messageId);
            if (target == null) return DiffStats.EMPTY;
            filesToCheck = List.copyOf(trackedFiles);
        }

        List<String> filesChanged = new ArrayList<>();
        int insertions = 0, deletions = 0;
        for (String trackingPath : filesToCheck) {
            try {
                String absolutePath = expand(trackingPath);
                Backup resolved = resolveBackupForRewind(trackingPath, target);
                if (resolved == null) continue;

                String currentContent = readIfExists(Path.of(absolutePath));
                String backupContent = resolved.backupFileName() != null
                        ? readIfExists(resolveBackupPath(resolved.backupFileName()))
                        : null;
                if (currentContent == null && backupContent == null) continue;

                List<StructuredPatchHunk> hunks = DiffHunks.compute(
                        currentContent != null ? currentContent : "",
                        backupContent != null ? backupContent : "");
                long[] counts = DiffHunks.countLinesChanged(hunks, null);
                if (counts[0] > 0 || counts[1] > 0) {
                    filesChanged.add(absolutePath);
                    insertions += (int) counts[0];
                    deletions += (int) counts[1];
                } else if (resolved.backupFileName() == null && Files.exists(Path.of(absolutePath))) {
                    // Zero-byte file created after the snapshot: diffLines reports
                    // 0/0 but it still counts as "changed" for rewind purposes.
                    filesChanged.add(absolutePath);
                }
            } catch (Exception e) {
                log.warn("FileHistory: getDiffStats failed for {}: {}", trackingPath, e.getMessage());
            }
        }
        return new DiffStats(filesChanged, insertions, deletions);
    }

    /**
     * Lightweight 2.1.197-compatible check used by Message Actions when it only needs to decide
     * whether the restore confirmation can be skipped. Unlike {@link #getDiffStats(String)}, this
     * never computes line diffs and stops at the first changed file.
     */
    public boolean hasAnyChanges(String messageId) {
        awaitPendingSnapshots();
        Snapshot target;
        List<String> filesToCheck;
        synchronized (this) {
            target = findLastByMessageId(messageId);
            if (target == null) return false;
            filesToCheck = List.copyOf(trackedFiles);
        }

        for (String trackingPath : filesToCheck) {
            try {
                String absolutePath = expand(trackingPath);
                Backup resolved = resolveBackupForRewind(trackingPath, target);
                if (resolved == null) continue;
                if (resolved.backupFileName() == null) {
                    if (Files.exists(Path.of(absolutePath))) return true;
                    continue;
                }
                if (checkOriginFileChanged(absolutePath, resolved.backupFileName())) return true;
            } catch (IOException | RuntimeException e) {
                log.warn("FileHistory: hasAnyChanges failed for {}: {}",
                    trackingPath, e.getMessage());
            }
        }
        return false;
    }

    /** Pure in-memory snapshot lookup used while rendering the rewind list. */
    public boolean canRestoreImmediately(String messageId) {
        synchronized (this) {
            return findLastByMessageId(messageId) != null;
        }
    }

    public boolean canRestore(String messageId) {
        awaitPendingSnapshots();
        return canRestoreImmediately(messageId);
    }


    public void clear() {
        awaitPendingSnapshots();
        synchronized (this) {
            snapshots.clear();
            trackedFiles.clear();
        }
    }

    /** Rebuilds in-memory state from snapshots read back from the session transcript (resume). */
    public void restoreFromSnapshots(List<Snapshot> restored) {
        awaitPendingSnapshots();
        synchronized (this) {
            snapshots.clear();
            snapshots.addAll(restored);
            trackedFiles.clear();
            for (Snapshot s : restored) {
                trackedFiles.addAll(s.trackedFileBackups().keySet());
            }
        }
    }

    public List<Snapshot> snapshotsView() {
        awaitPendingSnapshots();
        synchronized (this) {
            return List.copyOf(snapshots);
        }
    }

    public Set<String> trackedFilesView() {
        awaitPendingSnapshots();
        synchronized (this) {
            return Set.copyOf(trackedFiles);
        }
    }

    private void awaitPendingSnapshots() {
        CompletableFuture<Void> pending;
        synchronized (this) {
            pending = pendingSnapshots;
        }
        try {
            pending.join();
        } catch (RuntimeException e) {
            log.warn("FileHistory: pending snapshot failed: {}", e.getMessage());
        }
    }

    /**
     * Migrates backup files from a previous session's directory into the new
     * one — resume gets a fresh {@code sessionId}, but the on-disk backups
     * belong to the old directory. Hard-links when possible, falls back to a
     * copy. Silent no-op if the old directory doesn't exist.
     */
    public static void copyBackupsForResume(Path backupRoot, String oldSessionId, String newSessionId) {
        Path oldDir = backupRoot.resolve(oldSessionId);
        if (!Files.isDirectory(oldDir)) return;
        Path newDir = backupRoot.resolve(newSessionId);
        try {
            Files.createDirectories(newDir);
            try (var stream = Files.list(oldDir)) {
                for (Path src : stream.toList()) {
                    Path dest = newDir.resolve(src.getFileName());
                    if (Files.exists(dest)) continue;
                    try {
                        Files.createLink(dest, src);
                    } catch (IOException | UnsupportedOperationException _) {
                        Files.copy(src, dest);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("FileHistory: copyBackupsForResume failed ({} -> {}): {}", oldSessionId, newSessionId, e.getMessage());
        }
    }

    /**
     * Deletes session backup directories under {@code backupRoot} whose mtime is older than {@code
     * cleanupPeriodDays}.
     */
    public static void cleanupOldBackups(Path backupRoot, int cleanupPeriodDays) {
        if (!Files.isDirectory(backupRoot)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(cleanupPeriodDays));
        try (var stream = Files.list(backupRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                        FileUtils.deleteRecursively(dir);
                    }
                } catch (IOException e) {
                    log.warn("FileHistory: cleanupOldBackups failed for {}: {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("FileHistory: cleanupOldBackups failed to list {}: {}", backupRoot, e.getMessage());
            return;
        }
        try {
            Files.delete(backupRoot);
        } catch (IOException _) {

        }
    }

    // ── private: snapshot lookup ────────────────────────────────────────────

    /** Caller must hold the monitor. */
    private Snapshot lastSnapshotOrNull() {
        return snapshots.isEmpty() ? null : snapshots.getLast();
    }

/**
     * Caller must hold the monitor.
     */
    private Snapshot findLastByMessageId(String messageId) {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            if (snapshots.get(i).messageId().equals(messageId)) return snapshots.get(i);
        }
        return null;
    }

    /**
     * Resolves which backup a file should be restored to for {@code target}: the file's own record in
     * {@code target}, or (if it wasn't tracked yet at that point) its first-ever version.
     */
    private Backup resolveBackupForRewind(String trackingPath, Snapshot target) {
        Backup direct = target.trackedFileBackups().get(trackingPath);
        if (direct != null) return direct;
        return getBackupFirstVersion(trackingPath).orElse(null);
    }

    private Optional<Backup> getBackupFirstVersion(String trackingPath) {
        synchronized (this) {
            for (Snapshot s : snapshots) {
                Backup b = s.trackedFileBackups().get(trackingPath);
                if (b != null && b.version() == 1) return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private void notifySink(String entryMessageId, Snapshot snapshot, boolean isSnapshotUpdate) {
        FileHistorySnapshotSink sink = this.snapshotSink;
        if (sink == null) return;
        try {
            sink.record(sessionIdentity.get(), entryMessageId, snapshot, isSnapshotUpdate);
        } catch (Exception e) {
            log.warn("FileHistory: snapshot sink failed: {}", e.getMessage());
        }
    }

    // ── private: backup file IO ─────────────────────────────────────────────

    private Path backupDir() {
        return backupRoot.resolve(sessionIdentity.get());
    }

    private Path resolveBackupPath(String backupFileName) {
        return backupDir().resolve(backupFileName);
    }

    private static String backupFileName(String absoluteFilePath, int version) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(absoluteFilePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 16) + "@v" + version;
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("SHA-256 unavailable", e));
        }
    }

    /** Creates a backup of {@code absoluteFilePath} at {@code version}. Null backup if the source is missing. */
    private Backup createBackup(String absoluteFilePath, int version) throws IOException {
        Path source = Path.of(absoluteFilePath);
        if (!Files.exists(source)) {
            return new Backup(null, version, Instant.now());
        }
        String name = backupFileName(absoluteFilePath, version);
        Path dest = resolveBackupPath(name);
        FileUtils.copyFile(source, dest);
        preservePermissions(source, dest);
        return new Backup(name, version, Instant.now());
    }

    private void restoreBackupFile(String absoluteFilePath, String backupFileName) throws IOException {
        Path backupPath = resolveBackupPath(backupFileName);
        if (!Files.exists(backupPath)) {
            log.warn("FileHistory: [Rewind] backup file not found: {}", backupPath);
            return;
        }
        Path dest = Path.of(absoluteFilePath);
        FileUtils.copyFile(backupPath, dest);
        preservePermissions(backupPath, dest);
    }


    private static void preservePermissions(Path source, Path dest) {
        try {
            Files.setAttribute(dest, "unix:mode", Files.getAttribute(source, "unix:mode"));
            return;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException _) {
            // 'unix' view unavailable — fall back to the POSIX rwx bit set.
        }
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class);
            if (view == null) return;
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            Files.setPosixFilePermissions(dest, perms);
        } catch (IOException | UnsupportedOperationException _) {
            // Windows / non-POSIX FS — fall through with default ACLs.
        }
    }


    private boolean checkOriginFileChanged(String absoluteFilePath, String backupFileName) throws IOException {
        Path original = Path.of(absoluteFilePath);
        Path backup = resolveBackupPath(backupFileName);
        boolean originalExists = Files.exists(original);
        boolean backupExists = Files.exists(backup);
        if (originalExists != backupExists) return true;
        if (!originalExists) return false;

        if (Files.size(original) != Files.size(backup)) return true;
        if (modeDiffers(original, backup)) return true;
        Instant originalMtime = Files.getLastModifiedTime(original).toInstant();
        Instant backupMtime = Files.getLastModifiedTime(backup).toInstant();
        if (originalMtime.isBefore(backupMtime)) return false; // optimization: unmodified since backup

        return !Files.readString(original, StandardCharsets.UTF_8)
                .equals(Files.readString(backup, StandardCharsets.UTF_8));
    }


    private static boolean modeDiffers(Path a, Path b) {
        try {
            Object modeA = Files.getAttribute(a, "unix:mode");
            Object modeB = Files.getAttribute(b, "unix:mode");
            return !Objects.equals(modeA, modeB);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException _) {
            // 'unix' view unavailable — fall back to the POSIX rwx bit set.
        }
        try {
            return !Files.getPosixFilePermissions(a).equals(Files.getPosixFilePermissions(b));
        } catch (UnsupportedOperationException | IOException _) {
            return false; // non-POSIX filesystem — no permission signal to compare
        }
    }

    private static String readIfExists(Path path) {
        try {
            if (!Files.exists(path)) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException _) {
            return null;
        }
    }

    // ── private: path shortening (store relative paths to save space) ──────

    private String shorten(String absoluteOrRelative) {
        Path p = Path.of(absoluteOrRelative);
        if (!p.isAbsolute()) return absoluteOrRelative;
        if (p.startsWith(workingDirectory)) {
            return workingDirectory.relativize(p).toString();
        }
        return absoluteOrRelative;
    }

    private String expand(String trackingPath) {
        Path p = Path.of(trackingPath);
        if (p.isAbsolute()) return trackingPath;
        return workingDirectory.resolve(trackingPath).toString();
    }
}
