package com.claudecode.core.memdir;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class MemoryManifestScannerTest {

    @Test
    void scansAndParsesDescriptionAndType(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("feedback_testing.md"), """
            ---
            name: feedback-testing
            description: prefer real DB in integration tests
            metadata:
              type: feedback
            ---

            body content here
            """);

        List<MemoryManifestScanner.MemoryHeader> headers = MemoryManifestScanner.scan(dir);

        assertEquals(1, headers.size());
        assertEquals("feedback_testing.md", headers.getFirst().filename());
        assertEquals("prefer real DB in integration tests", headers.getFirst().description());
        assertEquals("feedback", headers.getFirst().type());
    }

    @Test
    void excludesEntrypointFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(AutoMemoryPrompt.ENTRYPOINT_NAME), "- [a](a.md) — hook");
        Files.writeString(dir.resolve("a.md"), "---\ndescription: x\n---\nbody");

        List<MemoryManifestScanner.MemoryHeader> headers = MemoryManifestScanner.scan(dir);

        assertEquals(1, headers.size());
        assertEquals("a.md", headers.getFirst().filename());
    }

    @Test
    void sortsNewestFirst(@TempDir Path dir) throws IOException {
        Path older = dir.resolve("older.md");
        Path newer = dir.resolve("newer.md");
        Files.writeString(older, "old content");
        Files.writeString(newer, "new content");
        Files.setLastModifiedTime(older, FileTime.from(Instant.now().minusSeconds(3600)));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.now()));

        List<MemoryManifestScanner.MemoryHeader> headers = MemoryManifestScanner.scan(dir);

        assertEquals(2, headers.size());
        assertEquals("newer.md", headers.getFirst().filename());
        assertEquals("older.md", headers.get(1).filename());
    }

    @Test
    void returnsEmptyForMissingDirectory(@TempDir Path dir) {
        List<MemoryManifestScanner.MemoryHeader> headers = MemoryManifestScanner.scan(dir.resolve("nonexistent"));
        assertTrue(headers.isEmpty());
    }

    @Test
    void formatManifestListsDescriptionAndTypeTag() {
        var header = new MemoryManifestScanner.MemoryHeader(
            "user_role.md", Path.of("/tmp/user_role.md"), 0L, "backend engineer", "user");

        String manifest = MemoryManifestScanner.formatManifest(List.of(header));

        assertTrue(Strings.CS.contains(manifest, "[user] user_role.md"));
        assertTrue(Strings.CS.contains(manifest, "backend engineer"));
    }

    @Test
    void formatManifestOmitsMissingDescriptionAndType() {
        var header = new MemoryManifestScanner.MemoryHeader(
            "note.md", Path.of("/tmp/note.md"), 0L, null, null);

        String manifest = MemoryManifestScanner.formatManifest(List.of(header));

        assertFalse(Strings.CS.contains(manifest, "[null]"));
        assertFalse(Strings.CS.contains(manifest, ": null"));
        assertTrue(Strings.CS.contains(manifest, "note.md"));
    }

    @Test
    void formatManifestEmptyForNoHeaders() {
        assertEquals("", MemoryManifestScanner.formatManifest(List.of()));
    }
}
