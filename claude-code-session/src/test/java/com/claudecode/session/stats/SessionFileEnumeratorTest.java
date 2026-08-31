package com.claudecode.session.stats;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SessionFileEnumerator} against the supported on-disk layout:
 * cross-project main-transcript + subagent enumeration (mains before
 * subagents per project) and the 4&nbsp;KB {@code readSessionStartDate} peek
 * with its non-transcript-prefix / sidechain / nested-snapshot traps.
 */
class SessionFileEnumeratorTest {

    @TempDir Path tmp;

    private Path projectsDir;

    @BeforeEach
    void setUp() throws IOException {
        projectsDir = tmp.resolve("projects");
        Files.createDirectories(projectsDir);
    }

    private Path mkProject(String name) throws IOException {
        Path dir = projectsDir.resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void listsMainAndSubagentFilesAcrossProjects() throws IOException {
        Path p1 = mkProject("-Users-x-a");
        Files.writeString(p1.resolve("s1.jsonl"), "{}");
        Files.writeString(p1.resolve("s2.jsonl"), "{}");
        Path sub = p1.resolve("s1").resolve("subagents");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("agent-abc.jsonl"), "{}");
        Files.writeString(sub.resolve("not-agent.jsonl"), "{}");   // wrong prefix — excluded
        Files.writeString(sub.resolve("agent-x.txt"), "");         // wrong suffix — excluded

        Path p2 = mkProject("-Users-x-b");
        Files.writeString(p2.resolve("s3.jsonl"), "{}");
        Files.writeString(p2.resolve("readme.md"), "");            // non-jsonl — excluded

        List<Path> files = new SessionFileEnumerator(projectsDir).listAllSessionFiles();

        assertEquals(4, files.size());
        assertTrue(files.stream().anyMatch(f -> Strings.CS.equals(f.getFileName().toString(), "agent-abc.jsonl")));
        assertTrue(files.stream().noneMatch(f -> Strings.CS.equals(f.getFileName().toString(), "not-agent.jsonl")));
        // Mains precede subagents within a project (aggregator ordering invariant).
        int mainIdx = files.indexOf(p1.resolve("s1.jsonl"));
        int subIdx = files.indexOf(sub.resolve("agent-abc.jsonl"));
        assertTrue(mainIdx >= 0 && subIdx > mainIdx, "main files must come before subagent files");
    }

    @Test
    void missingProjectsDirYieldsEmpty() {
        assertTrue(new SessionFileEnumerator(tmp.resolve("nope")).listAllSessionFiles().isEmpty());
    }

    @Test
    void isSubagentFile() {
        assertTrue(SessionFileEnumerator.isSubagentFile(
            Path.of("/x/projects/p/s1/subagents/agent-a.jsonl")));
        assertFalse(SessionFileEnumerator.isSubagentFile(
            Path.of("/x/projects/p/s1.jsonl")));
    }

    // ── readSessionStartDate ────────────────────────────────────────────────

    @Test
    void peekSkipsNonTranscriptPrefixEntries() throws IOException {
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, String.join("\n",
            "{\"type\":\"last-prompt\",\"lastPrompt\":\"x\"}",
            "{\"type\":\"mode\",\"mode\":\"normal\"}",
            "{\"type\":\"permission-mode\",\"permissionMode\":\"default\"}",
            "{\"type\":\"user\",\"timestamp\":\"2026-07-08T03:13:13.984Z\",\"isSidechain\":false}") + "\n");
        assertEquals("2026-07-08", SessionFileEnumerator.readSessionStartDate(f));
    }

    @Test
    void peekIsNotFooledByNestedSnapshotTimestamp() throws IOException {
        // file-history-snapshot embeds the PREVIOUS session's timestamp — a naive
        // string search would return 2026-01-01; JSON-aware peek must skip it.
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, String.join("\n",
            "{\"type\":\"file-history-snapshot\",\"snapshot\":{\"timestamp\":\"2026-01-01T00:00:00.000Z\"}}",
            "{\"type\":\"user\",\"timestamp\":\"2026-07-08T03:13:13.984Z\",\"isSidechain\":false}") + "\n");
        assertEquals("2026-07-08", SessionFileEnumerator.readSessionStartDate(f));
    }

    @Test
    void peekSkipsSidechainMessages() throws IOException {
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, String.join("\n",
            "{\"type\":\"user\",\"timestamp\":\"2026-01-01T00:00:00.000Z\",\"isSidechain\":true}",
            "{\"type\":\"user\",\"timestamp\":\"2026-07-08T03:13:13.984Z\",\"isSidechain\":false}") + "\n");
        assertEquals("2026-07-08", SessionFileEnumerator.readSessionStartDate(f));
    }

    @Test
    void peekReturnsNullWhenNoCompleteLine() throws IOException {
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, "{\"type\":\"user\",\"timestamp\":\"2026-07-08T03:13:13.984Z\"");  // no newline
        assertNull(SessionFileEnumerator.readSessionStartDate(f));
    }

    @Test
    void peekReturnsNullOnTranscriptMessageWithoutTimestamp() throws IOException {

        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, "{\"type\":\"user\",\"isSidechain\":false}\n");
        assertNull(SessionFileEnumerator.readSessionStartDate(f));
    }

    @Test
    void peekReturnsNullForMissingOrEmptyFile() throws IOException {
        assertNull(SessionFileEnumerator.readSessionStartDate(tmp.resolve("missing.jsonl")));
        Path empty = tmp.resolve("empty.jsonl");
        Files.writeString(empty, "");
        assertNull(SessionFileEnumerator.readSessionStartDate(empty));
    }

    @Test
    void peekHandlesTranscriptBeyond4kWindow() throws IOException {
        // Fill >4KB with non-transcript entries; first transcript message is past
        // the window → null (caller falls through to a full read).
        StringBuilder sb = new StringBuilder();
        String filler = "{\"type\":\"mode\",\"mode\":\"" + "x".repeat(200) + "\"}\n";
        while (sb.length() < 5000) sb.append(filler);
        sb.append("{\"type\":\"user\",\"timestamp\":\"2026-07-08T03:13:13.984Z\",\"isSidechain\":false}\n");
        Path f = tmp.resolve("s.jsonl");
        Files.writeString(f, sb.toString());
        assertNull(SessionFileEnumerator.readSessionStartDate(f));
    }
}
