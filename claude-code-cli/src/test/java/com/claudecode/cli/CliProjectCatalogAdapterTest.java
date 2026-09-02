package com.claudecode.cli;

import com.claudecode.session.FileProjectIndexStore;
import com.claudecode.session.ProjectCatalog;
import com.claudecode.session.SessionManager;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CliProjectCatalogAdapter} mapping contract: the session module's
 * {@link ProjectCatalog} aggregates surface through the UI-owned
 * {@link ProjectCatalogPort} as flat view records (project rows with physical
 * transcript paths, preferences round-trip).
 */
class CliProjectCatalogAdapterTest {

    @TempDir
    Path base;

    private CliProjectCatalogAdapter adapter() {
        ProjectCatalog catalog = new ProjectCatalog(new SessionManager(base, "/proj/a"),
            new FileProjectIndexStore(base.resolve("cache/project-index.json")));
        return new CliProjectCatalogAdapter(catalog);
    }

    private String writeSession(String storageProject, String id, String contentCwd,
                                long mtimeMs) throws Exception {
        Path dir = new SessionManager(base, storageProject).getSessionFile(id).getParent();
        Files.createDirectories(dir);
        String line = "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID() + "\","
            + "\"timestamp\":\"2026-07-01T00:00:00.000Z\",\"isSidechain\":false,"
            + "\"cwd\":\"" + contentCwd + "\","
            + "\"message\":{\"role\":\"user\",\"content\":\"hi " + id + "\"}}\n";
        Path file = dir.resolve(id + ".jsonl");
        Files.writeString(file, line);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtimeMs));
        return id;
    }

    @Test
    void mapsProjectsAndSessionsToPortEntries() throws Exception {
        String older = writeSession("/proj/a", "00000000-0000-4000-8000-000000000001", "/proj/a", 1000);
        String newer = writeSession("/proj/a", "00000000-0000-4000-8000-000000000002", "/proj/a", 2000);
        writeSession("/proj/b", "00000000-0000-4000-8000-000000000003", "/proj/b", 3000);

        List<ProjectCatalogPort.ProjectEntry> projects = adapter().listProjects();

        assertEquals(2, projects.size());
        ProjectCatalogPort.ProjectEntry b = projects.getFirst();
        assertEquals("/proj/b", b.projectPath());
        assertEquals("b", b.projectName());
        assertEquals(1, b.sessionCount());
        assertEquals(3000, b.lastActivityMs());

        ProjectCatalogPort.ProjectEntry a = projects.get(1);
        assertEquals(2, a.sessionCount());
        assertEquals(2, a.sessions().size(), "aggregate count matches listed sessions");
        ProjectCatalogPort.ProjectSessionEntry first = a.sessions().getFirst();
        assertEquals(newer, first.id(), "sessions newest-first");
        assertEquals("/proj/a", first.cwd());
        assertNotNull(first.transcriptPath(), "resume needs the physical transcript path");
        assertTrue(Files.exists(first.transcriptPath()));
        assertEquals(older, a.sessions().get(1).id());
    }

    @Test
    void preferencesRoundTripThroughPort() throws Exception {
        writeSession("/proj/a", "00000000-0000-4000-8000-000000000001", "/proj/a", 1000);
        CliProjectCatalogAdapter adapter = adapter();

        assertEquals(ProjectCatalogPort.ProjectPreferences.empty(), adapter.projectPreferences());

        adapter.updateProjectPreferences(List.of("/proj/a"), Map.of("/proj/a", true));

        ProjectCatalogPort.ProjectPreferences prefs = adapter.projectPreferences();
        assertEquals(List.of("/proj/a"), prefs.pinnedProjects());
        assertEquals(Map.of("/proj/a", true), prefs.collapsedProjects());
    }

    @Test
    void emptyStoreYieldsEmptyListing() {
        CliProjectCatalogAdapter adapter = adapter();
        assertEquals(List.of(), adapter.listProjects());
        assertEquals(ProjectCatalogPort.ProjectPreferences.empty(), adapter.projectPreferences());
    }
}
