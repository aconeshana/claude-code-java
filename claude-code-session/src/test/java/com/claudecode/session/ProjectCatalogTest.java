package com.claudecode.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ProjectCatalog} aggregation contract (Wake/197 semantics): project identity
 * comes from each transcript's content {@code cwd} ({@code relocatedCwd} wins), NOT
 * from the sanitized directory it is stored in; per-directory fingerprints
 * (file count + newest mtime) revalidate the cache so unchanged dirs are served
 * without re-reading transcripts; user prefs survive cache rebuilds.
 */
class ProjectCatalogTest {

    @TempDir
    Path base;

    private Path storePath() {
        return base.resolve("cache/project-index.json");
    }

    private ProjectCatalog catalog(String cwd) {
        // Same-thread persistence: production flushes the index on a background
        // virtual thread, which would make every "a fresh instance reads the
        // cache" assertion below a race.
        return new ProjectCatalog(new SessionManager(base, cwd),
            new FileProjectIndexStore(storePath()), null, Runnable::run);
    }

    private Path dirOf(String projectPath) {
        return new SessionManager(base, projectPath).projectDirectory();
    }

    /** Writes a minimal picker-visible transcript with full control over cwd/mtime. */
    private String writeRawSession(String storageProject, String id, String contentCwd,
                                   long mtimeMs) throws Exception {
        Path dir = dirOf(storageProject);
        Files.createDirectories(dir);
        String line = "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID() + "\","
            + "\"timestamp\":\"2026-07-01T00:00:00.000Z\",\"isSidechain\":false,"
            + (contentCwd != null ? "\"cwd\":\"" + contentCwd + "\"," : "")
            + "\"message\":{\"role\":\"user\",\"content\":\"hi " + id + "\"}}\n";
        Path file = dir.resolve(id + ".jsonl");
        Files.writeString(file, line);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMs));
        return id;
    }

    private static String uuid(int n) {
        return "00000000-0000-4000-8000-" + String.format("%012d", n);
    }

    @Test
    void aggregatesSessionsByContentCwdNotStorageDirectory() throws Exception {
        writeRawSession("/proj/a", uuid(1), "/proj/a", 1000);
        // stored under B's directory but its content says it belongs to /proj/a
        // (relocated-session shape: 197 keeps the file in the old dir)
        writeRawSession("/proj/b", uuid(2), "/proj/a", 2000);

        List<ProjectInfo> projects = catalog("/proj/a").listProjects();

        assertEquals(1, projects.size(), "both sessions group under their content cwd");
        ProjectInfo a = projects.getFirst();
        assertEquals("/proj/a", a.projectPath());
        assertEquals("a", a.projectName());
        assertEquals(2, a.sessionCount());
        assertEquals(uuid(2), a.sessions().getFirst().info().id(), "newest session first");
    }

    @Test
    void skipsForeignSessionWithoutCwdButKeepsOwn() throws Exception {
        writeRawSession("/proj/a", uuid(1), null, 1000);   // own dir: cwd fallback
        writeRawSession("/proj/b", uuid(2), null, 2000);   // foreign, no cwd → unusable

        List<ProjectInfo> projects = catalog("/proj/a").listProjects();

        assertEquals(1, projects.size());
        assertEquals(List.of(uuid(1)),
            projects.getFirst().sessions().stream().map(ref -> ref.info().id()).toList());
    }

    @Test
    void sortsProjectsByRecentActivity() throws Exception {
        writeRawSession("/proj/old", uuid(1), "/proj/old", 1000);
        writeRawSession("/proj/new", uuid(2), "/proj/new", 5000);

        List<ProjectInfo> projects = catalog("/proj/old").listProjects();

        assertEquals(List.of("/proj/new", "/proj/old"),
            projects.stream().map(ProjectInfo::projectPath).toList());
        assertEquals(5000, projects.getFirst().lastActivityMs());
    }

    @Test
    void servesUnchangedDirsFromCacheWithoutRereadingTranscripts() throws Exception {
        String victim = uuid(1);
        writeRawSession("/proj/a", victim, "/proj/a", 1000);
        List<ProjectInfo> first = catalog("/proj/a").listProjects();
        assertEquals(1, first.getFirst().sessionCount());

        // Corrupt the transcript content but keep count+mtime: a rescan would drop
        // it (unparseable), a cache hit must still serve it.
        Path file = dirOf("/proj/a").resolve(victim + ".jsonl");
        Files.writeString(file, "garbage!!\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000));

        // A fresh catalog instance proves the on-disk cache (not just memory) serves it.
        List<ProjectInfo> second = catalog("/proj/a").listProjects();
        assertEquals(1, second.size());
        assertEquals(victim, second.getFirst().sessions().getFirst().info().id(),
            "unchanged fingerprint must be served from the persisted cache");
    }

    @Test
    void rescansWhenFingerprintChanges() throws Exception {
        writeRawSession("/proj/a", uuid(1), "/proj/a", 1000);
        ProjectCatalog catalog = catalog("/proj/a");
        assertEquals(1, catalog.listProjects().getFirst().sessionCount());

        // added transcript → fileCount fingerprint changes
        writeRawSession("/proj/a", uuid(2), "/proj/a", 2000);
        assertEquals(2, catalog.listProjects().getFirst().sessionCount());

        // appended transcript → same count, maxMtime fingerprint changes
        Path file = dirOf("/proj/a").resolve(uuid(2) + ".jsonl");
        Files.writeString(file, Files.readString(file)
            + "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID() + "\","
            + "\"timestamp\":\"2026-07-02T00:00:00.000Z\",\"isSidechain\":false,"
            + "\"cwd\":\"/proj/a\",\"message\":{\"role\":\"user\",\"content\":\"more\"}}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(3000));
        assertEquals(3000, catalog.listProjects().getFirst().lastActivityMs());

        // deleted transcript → fileCount changes
        Files.delete(file);
        List<ProjectInfo> afterDelete = catalog.listProjects();
        assertEquals(1, afterDelete.getFirst().sessionCount());
        assertEquals(uuid(1), afterDelete.getFirst().sessions().getFirst().info().id());
    }

    @Test
    void preferencesSurviveCacheRebuildAndNewInstances() throws Exception {
        writeRawSession("/proj/a", uuid(1), "/proj/a", 1000);
        ProjectCatalog first = catalog("/proj/a");
        first.listProjects();
        first.updatePreferences(List.of("/proj/a"), Map.of("/proj/a", true));

        // force a rebuild by changing the fingerprint; prefs must carry over
        Path file = dirOf("/proj/a").resolve(uuid(1) + ".jsonl");
        Files.setLastModifiedTime(file, FileTime.fromMillis(9000));

        ProjectCatalog second = catalog("/proj/a");
        assertEquals(1, second.listProjects().size(), "rescan still finds the session");
        assertEquals(List.of("/proj/a"), second.preferences().pinnedProjects());
        assertEquals(Map.of("/proj/a", true), second.preferences().collapsedProjects());

        // the rebuilt snapshot on disk also keeps the prefs (third instance reads it)
        assertEquals(List.of("/proj/a"),
            catalog("/proj/a").preferences().pinnedProjects());
    }

    @Test
    void cachesDirectoriesThatFilterDownToNothing() throws Exception {
        // A sidechain is invisible to the picker, so /proj/a contributes no
        // sessions — but "no sessions" is a fact worth caching, otherwise the
        // directory is re-read on every open.
        Path dir = dirOf("/proj/a");
        Files.createDirectories(dir);
        Path file = dir.resolve(uuid(1) + ".jsonl");
        Files.writeString(file, "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID() + "\","
            + "\"timestamp\":\"2026-07-01T00:00:00.000Z\",\"isSidechain\":true,"
            + "\"cwd\":\"/proj/a\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000));

        assertEquals(List.of(), catalog("/proj/a").listProjects());

        String dirName = dir.getFileName().toString();
        assertEquals(List.of(dirName),
            new FileProjectIndexStore(storePath()).load().dirs().stream()
                .map(ProjectIndexSnapshot.CachedDir::dirName).toList(),
            "an empty result must still be persisted as a fingerprinted dir");
    }

    @Test
    void cachedProjectsServesTheIndexWithoutRevalidating() throws Exception {
        writeRawSession("/proj/a", uuid(1), "/proj/a", 1000);
        catalog("/proj/a").listProjects();          // populates the on-disk index

        // A second session on disk that the cache knows nothing about: the
        // cached view must ignore it, the revalidated one must pick it up.
        writeRawSession("/proj/a", uuid(2), "/proj/a", 2000);

        ProjectCatalog fresh = catalog("/proj/a");
        assertEquals(1, fresh.cachedProjects().getFirst().sessionCount(),
            "cachedProjects must not stat or re-read");
        assertEquals(2, fresh.listProjects().getFirst().sessionCount());
        assertEquals(2, fresh.cachedProjects().getFirst().sessionCount(),
            "the refreshed state is what later cached reads serve");
    }

    @Test
    void warmUpMakesTheNextCachedReadComplete() throws Exception {
        writeRawSession("/proj/a", uuid(1), "/proj/a", 1000);

        ProjectCatalog cold = catalog("/proj/a");
        assertEquals(List.of(), cold.cachedProjects(), "nothing indexed yet");
        cold.warmUp();

        assertEquals(1, catalog("/proj/a").cachedProjects().size(),
            "warm-up alone must leave a usable index behind");
    }
}
