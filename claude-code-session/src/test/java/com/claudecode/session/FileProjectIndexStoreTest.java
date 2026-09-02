package com.claudecode.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FileProjectIndexStore} persistence contract: verbatim round-trip, tolerant
 * load (missing/corrupt/foreign cache never throws), atomic save without temp
 * residue, and per-directory invalidation. The store is a pure cache — the session
 * jsonl files stay the source of truth.
 */
class FileProjectIndexStoreTest {

    @TempDir
    Path tempDir;

    private FileProjectIndexStore store() {
        return new FileProjectIndexStore(tempDir.resolve("cache/project-index.json"));
    }

    private static ProjectIndexSnapshot.CachedSession session(String id, long mtime) {
        return new ProjectIndexSnapshot.CachedSession(
            id, "/p/x", mtime, mtime - 1000, 12, "summary of " + id, "main", null,
            "custom " + id, "first prompt " + id, 4096);
    }

    private static ProjectIndexSnapshot.CachedDir dir(String dirName) {
        return new ProjectIndexSnapshot.CachedDir(dirName, 2, 3000L,
            List.of(session("aaaaaaa1-0000-0000-0000-000000000001", 3000L),
                    session("aaaaaaa2-0000-0000-0000-000000000002", 2000L)));
    }

    @Test
    void loadReturnsEmptyWhenFileMissing() {
        ProjectIndexSnapshot loaded = store().load();
        assertEquals(ProjectIndexSnapshot.empty(), loaded);
    }

    @Test
    void saveThenLoadRoundTripsVerbatim() {
        FileProjectIndexStore store = store();
        ProjectIndexSnapshot snapshot = new ProjectIndexSnapshot(
            ProjectIndexSnapshot.CURRENT_VERSION,
            List.of(dir("-p-a"), dir("-p-b")),
            List.of("/p/a"),
            Map.of("/p/b", true));

        store.save(snapshot);
        ProjectIndexSnapshot loaded = store().load();

        assertEquals(snapshot, loaded, "save→load must round-trip verbatim");
    }

    @Test
    void loadReturnsEmptyOnCorruptJson() throws Exception {
        Path path = tempDir.resolve("cache/project-index.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ not json !!");
        assertEquals(ProjectIndexSnapshot.empty(), store().load());
    }

    @Test
    void loadReturnsEmptyOnForeignVersion() throws Exception {
        Path path = tempDir.resolve("cache/project-index.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":99,\"dirs\":[]}");
        assertEquals(ProjectIndexSnapshot.empty(), store().load());
    }

    @Test
    void nullCollectionsNormalizeToEmpty() throws Exception {
        Path path = tempDir.resolve("cache/project-index.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":1}");
        ProjectIndexSnapshot loaded = store().load();
        assertEquals(List.of(), loaded.dirs());
        assertEquals(List.of(), loaded.pinnedProjects());
        assertEquals(Map.of(), loaded.collapsedProjects());
    }

    @Test
    void saveLeavesNoTempFileBehind() throws Exception {
        FileProjectIndexStore store = store();
        store.save(new ProjectIndexSnapshot(ProjectIndexSnapshot.CURRENT_VERSION,
            List.of(dir("-p-a")), List.of(), Map.of()));
        try (var files = Files.list(tempDir.resolve("cache"))) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("project-index.json"), names,
                "atomic save must not leave temp files behind");
        }
    }

    @Test
    void invalidateDropsOnlyTheNamedDirectory() {
        FileProjectIndexStore store = store();
        ProjectIndexSnapshot.CachedDir keep = dir("-p-keep");
        ProjectIndexSnapshot.CachedDir drop = dir("-p-drop");
        store.save(new ProjectIndexSnapshot(ProjectIndexSnapshot.CURRENT_VERSION,
            List.of(keep, drop), List.of("/p/keep"), Map.of()));

        store.invalidate("-p-drop");
        ProjectIndexSnapshot loaded = store().load();

        assertEquals(List.of(keep), loaded.dirs());
        assertEquals(List.of("/p/keep"), loaded.pinnedProjects(),
            "invalidate must not clobber user prefs");
    }

    @Test
    void invalidateOnMissingCacheIsANoOp() {
        assertDoesNotThrow(() -> store().invalidate("-p-nothing"));
        assertEquals(ProjectIndexSnapshot.empty(), store().load());
    }
}
