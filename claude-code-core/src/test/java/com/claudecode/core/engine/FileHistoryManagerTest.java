package com.claudecode.core.engine;

import com.claudecode.core.engine.FileHistoryManager.Backup;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileHistoryManagerTest {

    @TempDir Path tmp;
    @TempDir Path backupRoot;

    private Path cwd;
    private FileHistoryManager manager;

    @BeforeEach
    void setUp() throws IOException {
        cwd = tmp;
        manager = new FileHistoryManager(SessionIdentity.of("session-1"), cwd, backupRoot);
    }

    private Path file(String relativeName) {
        return cwd.resolve(relativeName);
    }

    private void write(Path p, String content) throws IOException {
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    // ── trackEdit ────────────────────────────────────────────────────────────

    @Test
    void trackEdit_noSnapshotYet_isNoOp() throws IOException {
        Path f = file("a.txt");
        write(f, "hello");
        manager.trackEdit(f.toString());
        assertTrue(manager.snapshotsView().isEmpty());
        assertTrue(manager.trackedFilesView().isEmpty());
    }

    @Test
    void trackEdit_firstCall_createsV1Backup() throws IOException {
        Path f = file("a.txt");
        write(f, "original content");
        manager.makeSnapshot("msg-1");

        manager.trackEdit(f.toString());

        assertTrue(manager.trackedFilesView().contains("a.txt"));
        FileHistoryManager.Snapshot snap = manager.snapshotsView().getFirst();
        FileHistoryManager.Backup backup = snap.trackedFileBackups().get("a.txt");
        assertNotNull(backup);
        assertEquals(1, backup.version());
        assertNotNull(backup.backupFileName());

        Path backupFile = backupRoot.resolve("session-1").resolve(backup.backupFileName());
        assertTrue(Files.exists(backupFile));
        assertEquals("original content", Files.readString(backupFile));
    }

    @Test
    void trackEdit_secondCallSameSnapshot_isIdempotent() throws IOException {
        Path f = file("a.txt");
        write(f, "v1 content");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        String firstBackupName = manager.snapshotsView().getFirst().trackedFileBackups().get("a.txt").backupFileName();

        // Simulate the file being edited after trackEdit already ran once
        // within the same snapshot — a second trackEdit call must NOT
        // overwrite the v1 backup with post-edit content.
        write(f, "edited content — should not land in v1 backup");
        manager.trackEdit(f.toString());

        String secondBackupName = manager.snapshotsView().getFirst().trackedFileBackups().get("a.txt").backupFileName();
        assertEquals(firstBackupName, secondBackupName);
        Path backupFile = backupRoot.resolve("session-1").resolve(firstBackupName);
        assertEquals("v1 content", Files.readString(backupFile));
    }

    @Test
    void trackEdit_newFile_recordsNullBackup() throws IOException {
        Path f = file("brand-new.txt"); // never created
        manager.makeSnapshot("msg-1");

        manager.trackEdit(f.toString());

        FileHistoryManager.Backup backup = manager.snapshotsView().getFirst().trackedFileBackups().get("brand-new.txt");
        assertNotNull(backup);
        assertNull(backup.backupFileName());
        assertEquals(1, backup.version());
    }

    @Test
    void trackEdit_snapshotUpdateUsesParentAssistantUuidWithoutChangingSnapshotOwner() throws Exception {
        Path f = file("assistant-owned-update.txt");
        write(f, "before");
        List<String> persistedMessageIds = new ArrayList<>();
        List<FileHistoryManager.Snapshot> persistedSnapshots = new ArrayList<>();
        List<Boolean> updateFlags = new ArrayList<>();
        manager.setSnapshotSink((_, messageId, snapshot, isSnapshotUpdate) -> {
            persistedMessageIds.add(messageId);
            persistedSnapshots.add(snapshot);
            updateFlags.add(isSnapshotUpdate);
        });

        manager.makeSnapshot("user-message-uuid");

        manager.trackEdit(f.toString(), "assistant-wrapper-uuid");

        assertEquals(List.of("user-message-uuid", "assistant-wrapper-uuid"),
            persistedMessageIds,
            "the retroactive JSONL snapshot update belongs to the assistant tool-use wrapper");
        assertEquals(List.of(false, true), updateFlags);
        assertEquals("user-message-uuid", persistedSnapshots.getLast().messageId(),
            "the updated snapshot still represents the original user-turn checkpoint");
    }

    // ── makeSnapshot ─────────────────────────────────────────────────────────

    @Test
    void scheduleSnapshot_returnsBeforePersistenceSinkCompletes() throws Exception {
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        manager.setSnapshotSink((_, _, _, _) -> {
            sinkEntered.countDown();
            try {
                releaseSink.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });

        assertTimeout(Duration.ofMillis(100), () -> manager.scheduleSnapshot("msg-async"));
        assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
        releaseSink.countDown();

        assertEquals("msg-async", manager.snapshotsView().getFirst().messageId());
    }

    @Test
    void makeSnapshot_unchangedFile_reusesVersion() throws IOException, InterruptedException {
        Path f = file("a.txt");
        write(f, "stable content");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        FileHistoryManager.Backup v1 = manager.snapshotsView().getFirst().trackedFileBackups().get("a.txt");

        Thread.sleep(5); // ensure a distinguishable mtime if a re-backup incorrectly happened
        manager.makeSnapshot("msg-2");

        FileHistoryManager.Backup reused = manager.snapshotsView().get(1).trackedFileBackups().get("a.txt");
        assertEquals(v1.backupFileName(), reused.backupFileName());
        assertEquals(v1.version(), reused.version());
    }

    @Test
    void makeSnapshot_changedFile_createsNewVersion() throws IOException {
        Path f = file("a.txt");
        write(f, "version one");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        FileHistoryManager.Backup v1 = manager.snapshotsView().getFirst().trackedFileBackups().get("a.txt");

        write(f, "version two — different size and content");
        manager.makeSnapshot("msg-2");

        FileHistoryManager.Backup v2 = manager.snapshotsView().get(1).trackedFileBackups().get("a.txt");
        assertEquals(2, v2.version());
        assertNotEquals(v1.backupFileName(), v2.backupFileName());
        Path v1File = backupRoot.resolve("session-1").resolve(v1.backupFileName());
        Path v2File = backupRoot.resolve("session-1").resolve(v2.backupFileName());
        assertEquals("version one", Files.readString(v1File));
        assertEquals("version two — different size and content", Files.readString(v2File));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void makeSnapshot_permissionOnlyChange_createsNewVersion() throws IOException {

        Path f = file("script.sh");
        write(f, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(f, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        FileHistoryManager.Backup v1 = manager.snapshotsView().getFirst().trackedFileBackups().get("script.sh");

        Files.setPosixFilePermissions(f, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        manager.makeSnapshot("msg-2");

        FileHistoryManager.Backup v2 = manager.snapshotsView().get(1).trackedFileBackups().get("script.sh");
        assertEquals(2, v2.version(), "permission-only change must still bump the version, not reuse v1");
        assertNotEquals(v1.backupFileName(), v2.backupFileName());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rewind_restoresPermissionsEvenWhenContentUnchanged() throws IOException {
        Path f = file("script.sh");
        write(f, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(f, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        // Content stays byte-identical; only permissions change.
        Files.setPosixFilePermissions(f, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        manager.makeSnapshot("msg-2");

        manager.rewind("msg-1");

        assertTrue(Files.getPosixFilePermissions(f).contains(PosixFilePermission.OWNER_EXECUTE),
            "rewind must restore the executable bit even though file content never changed");
    }

    /** setuid bit (0o4000), outside the 9 rwx bits — used to prove full-mode comparison. */
    private static final int SETUID = 04000;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void makeSnapshot_setuidOnlyChange_createsNewVersion() throws IOException {

        // compares the full fs.Stats.mode integer, so this counts as changed —
        // a prior rwx-only comparison would have silently reused v1.
        Path f = file("bin.sh");
        write(f, "#!/bin/sh\necho hi\n");
        Files.setAttribute(f, "unix:mode", 0755); // rwxr-xr-x, no setuid
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        FileHistoryManager.Backup v1 = manager.snapshotsView().getFirst().trackedFileBackups().get("bin.sh");

        Files.setAttribute(f, "unix:mode", 04755); // rwsr-xr-x — setuid added, rwx unchanged
        int actualMode = ((Number) Files.getAttribute(f, "unix:mode")).intValue();
        Assumptions.assumeTrue((actualMode & SETUID) != 0,
            "filesystem stripped the setuid bit — cannot exercise this scenario here");
        manager.makeSnapshot("msg-2");

        FileHistoryManager.Backup v2 = manager.snapshotsView().get(1).trackedFileBackups().get("bin.sh");
        assertEquals(2, v2.version(),
            "a setuid-only change (rwx bits unchanged) must be detected — matching TS's full-mode compare");
        assertNotEquals(v1.backupFileName(), v2.backupFileName());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rewind_restoresSetuidBit() throws IOException {

        // restore, so setuid survives a rewind, not just the rwx bits.
        Path f = file("bin.sh");
        write(f, "#!/bin/sh\necho hi\n");
        Files.setAttribute(f, "unix:mode", 04755); // setuid set
        int startMode = ((Number) Files.getAttribute(f, "unix:mode")).intValue();
        Assumptions.assumeTrue((startMode & SETUID) != 0,
            "filesystem stripped the setuid bit — cannot exercise this scenario here");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        Files.setAttribute(f, "unix:mode", 0755); // setuid cleared
        manager.makeSnapshot("msg-2");

        manager.rewind("msg-1");

        int restored = ((Number) Files.getAttribute(f, "unix:mode")).intValue();
        assertTrue((restored & SETUID) != 0,
            "rewind must restore the setuid bit — TS chmods the backup's full mode back");
    }

    @Test
    void makeSnapshot_deletedFile_recordsNullBackup() throws IOException {
        Path f = file("a.txt");
        write(f, "will be deleted");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        Files.delete(f);
        manager.makeSnapshot("msg-2");

        FileHistoryManager.Backup deleted = manager.snapshotsView().get(1).trackedFileBackups().get("a.txt");
        assertNull(deleted.backupFileName());
        assertEquals(2, deleted.version());
    }

    @Test
    void makeSnapshot_evictsOldSnapshotsBeyond100() {
        for (int i = 0; i < 105; i++) {
            manager.makeSnapshot("msg-" + i);
        }
        List<FileHistoryManager.Snapshot> snaps = manager.snapshotsView();
        assertEquals(100, snaps.size());
        assertEquals("msg-104", snaps.getLast().messageId());
        assertEquals("msg-5", snaps.getFirst().messageId());
    }

    // ── rewind ───────────────────────────────────────────────────────────────

    @Test
    void rewind_restoresModifiedFileToOriginalContent() throws IOException {
        Path f = file("a.txt");
        write(f, "original");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        write(f, "modified by the model");
        manager.makeSnapshot("msg-2");

        List<String> changed = manager.rewind("msg-1");

        assertEquals("original", Files.readString(f));
        assertTrue(changed.contains(f.toString()));
    }

    @Test
    void rewind_deletesFileCreatedAfterSnapshot() throws IOException {
        Path f = file("new-file.txt");
        manager.makeSnapshot("msg-1"); // no tracked files yet
        manager.trackEdit(f.toString()); // records null backup (file doesn't exist)

        write(f, "created after the snapshot");
        manager.makeSnapshot("msg-2");
        assertTrue(Files.exists(f));

        manager.rewind("msg-1");

        assertFalse(Files.exists(f));
    }

    @Test
    void rewind_restoresDeletedFile() throws IOException {
        Path f = file("a.txt");
        write(f, "will survive rewind");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        Files.delete(f);
        manager.makeSnapshot("msg-2");
        assertFalse(Files.exists(f));

        manager.rewind("msg-1");

        assertTrue(Files.exists(f));
        assertEquals("will survive rewind", Files.readString(f));
    }

    @Test
    void rewind_fallsBackToFirstVersionWhenFileNotInTargetSnapshot() throws IOException {
        // msg-1's snapshot predates file b.txt being tracked at all.
        manager.makeSnapshot("msg-1");

        Path b = file("b.txt");
        write(b, "b's very first content");
        manager.makeSnapshot("msg-2");
        manager.trackEdit(b.toString()); // b's v1 backup, recorded under msg-2's snapshot

        write(b, "b changed again");
        manager.makeSnapshot("msg-3");

        // Rewinding to msg-1 (before b existed as a tracked file) should fall
        // back to b's first version rather than leaving it untouched.
        manager.rewind("msg-1");

        assertEquals("b's very first content", Files.readString(b));
    }

    @Test
    void rewind_unknownMessageId_throws() {
        manager.makeSnapshot("msg-1");
        assertThrows(IllegalStateException.class, () -> manager.rewind("does-not-exist"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rewind_preservesFilePermissions() throws IOException {
        Path f = file("script.sh");
        write(f, "#!/bin/sh\necho hi\n");
        Files.setPosixFilePermissions(f, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        write(f, "#!/bin/sh\necho changed\n");
        Files.setPosixFilePermissions(f, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        manager.makeSnapshot("msg-2");

        manager.rewind("msg-1");

        assertTrue(Files.getPosixFilePermissions(f).contains(PosixFilePermission.OWNER_EXECUTE));
    }

    // ── getDiffStats ─────────────────────────────────────────────────────────

    @Test
    void getDiffStats_computesInsertionsAndDeletions() throws IOException {
        Path f = file("a.txt");
        write(f, "line1\nline2\nline3\n");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());

        write(f, "line1\nline2-changed\nline3\nline4\n");
        manager.makeSnapshot("msg-2");

        FileHistoryManager.DiffStats stats = manager.getDiffStats("msg-1");

        assertTrue(stats.filesChanged().contains(f.toString()));
        assertTrue(stats.insertions() > 0);
        assertTrue(stats.deletions() > 0);
    }

    @Test
    void getDiffStats_doesNotMutateFilesystem() throws IOException {
        Path f = file("a.txt");
        write(f, "original");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        write(f, "modified");
        manager.makeSnapshot("msg-2");

        manager.getDiffStats("msg-1");

        assertEquals("modified", Files.readString(f)); // untouched by the preview
    }

    @Test
    void getDiffStats_zeroByteFileCountsAsChanged() throws IOException {
        Path f = file("new-empty.txt");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString()); // null backup, file didn't exist

        Files.createFile(f); // zero-byte file created after the snapshot
        manager.makeSnapshot("msg-2");

        FileHistoryManager.DiffStats stats = manager.getDiffStats("msg-1");

        assertTrue(stats.filesChanged().contains(f.toString()));
    }

    @Test
    void getDiffStats_unknownMessageId_returnsEmpty() {
        manager.makeSnapshot("msg-1");
        FileHistoryManager.DiffStats stats = manager.getDiffStats("does-not-exist");
        assertEquals(FileHistoryManager.DiffStats.EMPTY, stats);
    }

    @Test
    void hasAnyChanges_usesTheLightweight197BooleanCheck() throws IOException {
        Path changed = file("changed.txt");
        write(changed, "before");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(changed.toString());

        assertFalse(manager.hasAnyChanges("msg-1"));

        write(changed, "after");

        assertTrue(manager.hasAnyChanges("msg-1"));
        assertFalse(manager.hasAnyChanges("does-not-exist"));
    }

    // ── canRestore ───────────────────────────────────────────────────────────

    @Test
    void canRestore_trueOnlyForKnownMessageId() {
        manager.makeSnapshot("msg-1");
        assertTrue(manager.canRestore("msg-1"));
        assertFalse(manager.canRestore("msg-2"));
    }

    @Test
    void canRestoreImmediately_readsTheCurrentInMemorySnapshotState() {
        manager.makeSnapshot("msg-1");

        assertTrue(manager.canRestoreImmediately("msg-1"));
        assertFalse(manager.canRestoreImmediately("msg-2"));
    }

    // ── restoreFromSnapshots (resume) ───────────────────────────────────────

    @Test
    void restoreFromSnapshots_rebuildsTrackedFilesAndSnapshots() {
        FileHistoryManager.Snapshot s1 = new FileHistoryManager.Snapshot("msg-1",
            Map_of("a.txt", new FileHistoryManager.Backup("abc@v1", 1, Instant.now())),
            Instant.now());

        manager.restoreFromSnapshots(List.of(s1));

        assertEquals(List.of(s1), manager.snapshotsView());
        assertEquals(Set.of("a.txt"), manager.trackedFilesView());
    }

    private static Map<String, FileHistoryManager.Backup> Map_of(String k, FileHistoryManager.Backup v) {
        Map<String, Backup> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // ── clear (/clear) ───────────────────────────────────────────────────────

    @Test
    void clear_dropsAllSnapshotsAndTrackedFiles() throws IOException {
        Path f = file("a.txt");
        write(f, "content");
        manager.makeSnapshot("msg-1");
        manager.trackEdit(f.toString());
        assertFalse(manager.snapshotsView().isEmpty());
        assertFalse(manager.trackedFilesView().isEmpty());

        manager.clear();

        assertTrue(manager.snapshotsView().isEmpty());
        assertTrue(manager.trackedFilesView().isEmpty());
        assertFalse(manager.canRestore("msg-1"));
    }

    // ── copyBackupsForResume ─────────────────────────────────────────────────

    @Test
    void copyBackupsForResume_migratesBackupFiles() throws IOException {
        Path oldDir = backupRoot.resolve("old-session");
        Files.createDirectories(oldDir);
        Files.writeString(oldDir.resolve("abc123@v1"), "backed up content");

        FileHistoryManager.copyBackupsForResume(backupRoot, "old-session", "new-session");

        Path migrated = backupRoot.resolve("new-session").resolve("abc123@v1");
        assertTrue(Files.exists(migrated));
        assertEquals("backed up content", Files.readString(migrated));
    }

    @Test
    void copyBackupsForResume_missingOldSession_isNoOp() {
        assertDoesNotThrow(() ->
            FileHistoryManager.copyBackupsForResume(backupRoot, "never-existed", "new-session"));
        assertFalse(Files.exists(backupRoot.resolve("new-session")));
    }

    // ── session id follows the shared SessionIdentity holder ───────────────

    @Test
    void backupPath_followsSessionIdentityMutation_afterResumeLikeSwitch() throws IOException {
        SessionIdentity identity = SessionIdentity.of("session-old");
        FileHistoryManager mgr = new FileHistoryManager(identity, cwd, backupRoot);
        Path f = file("a.txt");
        write(f, "before switch");
        mgr.makeSnapshot("msg-1");
        mgr.trackEdit(f.toString());
        assertTrue(Files.exists(backupRoot.resolve("session-old")));

// matches QueryEngine#switchToSession mutating the SAME SessionIdentity
        // in place (resume/branch do not construct a new QueryEngine/manager).
        identity.set("session-new");
        write(f, "after switch");
        mgr.makeSnapshot("msg-2");
        mgr.trackEdit(f.toString());

        assertTrue(Files.exists(backupRoot.resolve("session-new")),
            "backups written after a session-identity switch must land in the new session's directory");
    }

    // ── cleanupOldBackups ────────────────────────────────────────────────────

    private static void setMtimeDaysAgo(Path path, int daysAgo) throws IOException {
        Files.setLastModifiedTime(path,
            FileTime.from(Instant.now().minus(Duration.ofDays(daysAgo))));
    }

    @Test
    void cleanupOldBackups_deletesDirsOlderThanCutoff() throws IOException {
        Path oldSession = backupRoot.resolve("old-session");
        Files.createDirectories(oldSession);
        Files.writeString(oldSession.resolve("abc@v1"), "stale backup");
        setMtimeDaysAgo(oldSession, 45);

        FileHistoryManager.cleanupOldBackups(backupRoot, 30);

        assertFalse(Files.exists(oldSession), "a session dir older than the cutoff must be deleted");
    }

    @Test
    void cleanupOldBackups_keepsDirsNewerThanCutoff() throws IOException {
        Path recentSession = backupRoot.resolve("recent-session");
        Files.createDirectories(recentSession);
        Files.writeString(recentSession.resolve("abc@v1"), "fresh backup");
        setMtimeDaysAgo(recentSession, 5);

        FileHistoryManager.cleanupOldBackups(backupRoot, 30);

        assertTrue(Files.exists(recentSession), "a session dir newer than the cutoff must survive");
    }

    @Test
    void cleanupOldBackups_zeroPeriodDeletesEverything() throws IOException {
        Path session = backupRoot.resolve("any-session");
        Files.createDirectories(session);
        Files.writeString(session.resolve("abc@v1"), "content");

        FileHistoryManager.cleanupOldBackups(backupRoot, 0);

        assertFalse(Files.exists(session), "cleanupPeriodDays=0 means no grace period — everything is eligible");
    }

    @Test
    void cleanupOldBackups_missingBackupRoot_doesNotThrow() {
        Path missing = backupRoot.resolve("does-not-exist");
        assertDoesNotThrow(() -> FileHistoryManager.cleanupOldBackups(missing, 30));
    }

    @Test
    void cleanupOldBackups_removesBackupRootWhenItEndsUpEmpty() throws IOException {
        Path oldSession = backupRoot.resolve("old-session");
        Files.createDirectories(oldSession);
        setMtimeDaysAgo(oldSession, 45);

        FileHistoryManager.cleanupOldBackups(backupRoot, 30);

        assertFalse(Files.exists(backupRoot), "backupRoot itself should be removed once empty (mirrors TS tryRmdir)");
    }

    @Test
    void cleanupOldBackups_leavesBackupRootWhenNotEmpty() throws IOException {
        Path oldSession = backupRoot.resolve("old-session");
        Files.createDirectories(oldSession);
        setMtimeDaysAgo(oldSession, 45);
        Path recentSession = backupRoot.resolve("recent-session");
        Files.createDirectories(recentSession);
        setMtimeDaysAgo(recentSession, 5);

        FileHistoryManager.cleanupOldBackups(backupRoot, 30);

        assertTrue(Files.exists(backupRoot));
        assertTrue(Files.exists(recentSession));
        assertFalse(Files.exists(oldSession));
    }

    // ── concurrency ──────────────────────────────────────────────────────────

    @Test
    void concurrentTrackEdit_twoThreadsSameFile_noCorruption() throws Exception {
        Path f = file("shared.txt");
        write(f, "shared content");
        manager.makeSnapshot("msg-1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Exception> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    manager.trackEdit(f.toString());
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "unexpected errors: " + errors);

        // Exactly one v1 backup should have been produced, with the original content.
        FileHistoryManager.Backup backup = manager.snapshotsView().getFirst().trackedFileBackups().get("shared.txt");
        assertNotNull(backup);
        assertEquals(1, backup.version());
        Path backupFile = backupRoot.resolve("session-1").resolve(backup.backupFileName());
        assertEquals("shared content", Files.readString(backupFile));
    }
}
