package com.claudecode.commands.diff;

import com.claudecode.core.diff.StructuredPatchHunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitDiffCollectorTest {

    // ── parseShortstat ───────────────────────────────────────────────────────

    @Test
    void parseShortstat_standardLine() {
        DiffData.Stats stats = GitDiffCollector.parseShortstat(
            " 3 files changed, 52 insertions(+), 8 deletions(-)");
        assertEquals(new DiffData.Stats(3, 52, 8), stats);
    }

    @Test
    void parseShortstat_singularForms() {
        DiffData.Stats stats = GitDiffCollector.parseShortstat(
            " 1 file changed, 1 insertion(+), 1 deletion(-)");
        assertEquals(new DiffData.Stats(1, 1, 1), stats);
    }

    @Test
    void parseShortstat_noInsertions() {
        DiffData.Stats stats = GitDiffCollector.parseShortstat(
            " 2 files changed, 5 deletions(-)");
        assertEquals(new DiffData.Stats(2, 0, 5), stats);
    }

    @Test
    void parseShortstat_noDeletions() {
        DiffData.Stats stats = GitDiffCollector.parseShortstat(
            " 4 files changed, 10 insertions(+)");
        assertEquals(new DiffData.Stats(4, 10, 0), stats);
    }

    @Test
    void parseShortstat_noMatchReturnsNull() {
        assertNull(GitDiffCollector.parseShortstat(""));
        assertNull(GitDiffCollector.parseShortstat("not a shortstat line"));
    }

    // ── parseGitNumstat ──────────────────────────────────────────────────────

    @Test
    void parseGitNumstat_plainFiles() {
        GitDiffCollector.NumstatResult result = GitDiffCollector.parseGitNumstat(
            "10\t2\tsrc/Main.java\n0\t5\tREADME.md\n");
        assertEquals(new DiffData.Stats(2, 10, 7), result.stats());
        assertEquals(2, result.perFileStats().size());
        assertEquals(new GitDiffCollector.PerFileStat(10, 2, false, false),
            result.perFileStats().get("src/Main.java"));
        assertEquals(new GitDiffCollector.PerFileStat(0, 5, false, false),
            result.perFileStats().get("README.md"));
    }

    @Test
    void parseGitNumstat_binaryDashCounts() {
        GitDiffCollector.NumstatResult result = GitDiffCollector.parseGitNumstat(
            "-\t-\timg/logo.png\n3\t1\ta.txt\n");
        assertEquals(new DiffData.Stats(2, 3, 1), result.stats());
        assertEquals(new GitDiffCollector.PerFileStat(0, 0, true, false),
            result.perFileStats().get("img/logo.png"));
    }

    @Test
    void parseGitNumstat_fileNameContainingTabs() {
        GitDiffCollector.NumstatResult result = GitDiffCollector.parseGitNumstat(
            "1\t2\tweird\tname\twith\ttabs.txt\n");
        assertEquals(new DiffData.Stats(1, 1, 2), result.stats());
        assertEquals(new GitDiffCollector.PerFileStat(1, 2, false, false),
            result.perFileStats().get("weird\tname\twith\ttabs.txt"));
    }

    @Test
    void parseGitNumstat_capsPerFileEntriesAt50ButCountsAll() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            out.append("1\t1\tfile").append(i).append(".txt\n");
        }
        GitDiffCollector.NumstatResult result = GitDiffCollector.parseGitNumstat(out.toString());
        assertEquals(new DiffData.Stats(60, 60, 60), result.stats());
        assertEquals(50, result.perFileStats().size());
        assertTrue(result.perFileStats().containsKey("file0.txt"));
        assertTrue(result.perFileStats().containsKey("file49.txt"));
        assertFalse(result.perFileStats().containsKey("file50.txt"));
    }

    @Test
    void parseGitNumstat_skipsMalformedLines() {
        GitDiffCollector.NumstatResult result = GitDiffCollector.parseGitNumstat(
            "garbage\n1\t1\tok.txt\n");
        assertEquals(new DiffData.Stats(1, 1, 1), result.stats());
        assertEquals(1, result.perFileStats().size());
    }

    // ── parseGitDiff ─────────────────────────────────────────────────────────

    @Test
    void parseGitDiff_singleFileSingleHunk() {
        String diff = """
            diff --git a/src/A.java b/src/A.java
            index 1234567..89abcde 100644
            --- a/src/A.java
            +++ b/src/A.java
            @@ -1,3 +1,4 @@ class A {
             context
            -removed line
            +added one
            +added two
            """;
        Map<String, List<StructuredPatchHunk>> hunks = GitDiffCollector.parseGitDiff(diff);
        assertEquals(1, hunks.size());
        List<StructuredPatchHunk> fileHunks = hunks.get("src/A.java");
        assertNotNull(fileHunks);
        assertEquals(1, fileHunks.size());
        StructuredPatchHunk hunk = fileHunks.getFirst();
        assertEquals(1, hunk.oldStart());
        assertEquals(3, hunk.oldLines());
        assertEquals(1, hunk.newStart());
        assertEquals(4, hunk.newLines());

        assertEquals(List.of(" context", "-removed line", "+added one", "+added two", ""),
            hunk.lines());
    }

    @Test
    void parseGitDiff_multipleHunksInOneFile() {
        String diff = """
            diff --git a/b.txt b/b.txt
            index 111..222 100644
            --- a/b.txt
            +++ b/b.txt
            @@ -1,2 +1,2 @@
            -x
            +y
            @@ -10,2 +10,3 @@
             keep
            +new
            """;
        Map<String, List<StructuredPatchHunk>> hunks = GitDiffCollector.parseGitDiff(diff);
        List<StructuredPatchHunk> fileHunks = hunks.get("b.txt");
        assertEquals(2, fileHunks.size());
        assertEquals(1, fileHunks.getFirst().oldStart());
        assertEquals(10, fileHunks.get(1).oldStart());
        assertEquals(10, fileHunks.get(1).newStart());
        assertEquals(3, fileHunks.get(1).newLines());
    }

    @Test
    void parseGitDiff_multipleFiles() {
        String diff = """
            diff --git a/one.txt b/one.txt
            @@ -1 +1 @@
            -a
            +b
            diff --git a/two.txt b/two.txt
            @@ -5,2 +5,2 @@
            -c
            +d
            """;
        Map<String, List<StructuredPatchHunk>> hunks = GitDiffCollector.parseGitDiff(diff);
        assertEquals(2, hunks.size());
        assertTrue(hunks.containsKey("one.txt"));
        assertTrue(hunks.containsKey("two.txt"));
        assertEquals(5, hunks.get("two.txt").getFirst().oldStart());
    }

    @Test
    void parseGitDiff_hunkHeaderWithoutLineCountsDefaultsTo1() {
        String diff = """
            diff --git a/x.txt b/x.txt
            @@ -7 +9 @@
            -old
            +new
            """;
        StructuredPatchHunk hunk = GitDiffCollector.parseGitDiff(diff).get("x.txt").getFirst();
        assertEquals(7, hunk.oldStart());
        assertEquals(1, hunk.oldLines());
        assertEquals(9, hunk.newStart());
        assertEquals(1, hunk.newLines());
    }

    @Test
    void parseGitDiff_binaryFileProducesNoHunks() {
        String diff = """
            diff --git a/logo.png b/logo.png
            index 111..222 100644
            Binary files a/logo.png and b/logo.png differ
            diff --git a/t.txt b/t.txt
            @@ -1 +1 @@
            -a
            +b
            """;
        Map<String, List<StructuredPatchHunk>> hunks = GitDiffCollector.parseGitDiff(diff);
        assertFalse(hunks.containsKey("logo.png"), "binary file has no hunks so is absent");
        assertTrue(hunks.containsKey("t.txt"));
    }

    @Test
    void parseGitDiff_truncatesAt400LinesPerFile() {
        StringBuilder diff = new StringBuilder("diff --git a/big.txt b/big.txt\n@@ -1,450 +1,450 @@\n");
        for (int i = 0; i < 450; i++) {
            diff.append("+line").append(i).append('\n');
        }
        List<StructuredPatchHunk> fileHunks = GitDiffCollector.parseGitDiff(diff.toString()).get("big.txt");
        assertEquals(1, fileHunks.size());
        assertEquals(400, fileHunks.getFirst().lines().size());
        assertEquals("+line0", fileHunks.getFirst().lines().getFirst());
        assertEquals("+line399", fileHunks.getFirst().lines().get(399));
    }

    @Test
    void parseGitDiff_emptyInput() {
        assertTrue(GitDiffCollector.parseGitDiff("").isEmpty());
        assertTrue(GitDiffCollector.parseGitDiff("   \n").isEmpty());
    }

    // ── collect() end-to-end against a real temp repo ────────────────────────

    @Test
    void collect_nonGitDirectoryReturnsNull(@TempDir Path dir) {
        assumeTrue(gitAvailable(), "git executable not available");
        assertNull(new GitDiffCollector(dir.toString()).collect());
    }

    @Test
    void collect_realRepoWithModifiedAndUntrackedFiles(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");

        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("tracked.txt"), "one\ntwo\nthree\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");

        Files.writeString(dir.resolve("tracked.txt"), "one\nTWO\nthree\nfour\n");
        Files.writeString(dir.resolve("brand-new.txt"), "hello\n");

        DiffData data = new GitDiffCollector(dir.toString()).collect();
        assertNotNull(data);

        // numstat: tracked.txt +2/-1; untracked adds one more file to the count.
        assertEquals(new DiffData.Stats(2, 2, 1), data.stats());
        assertEquals(2, data.files().size());

        DiffData.DiffFile untracked = data.files().getFirst();
        assertEquals("brand-new.txt", untracked.path());
        assertTrue(untracked.isUntracked());
        assertEquals(0, untracked.linesAdded());
        assertFalse(untracked.isLargeFile());

        DiffData.DiffFile tracked = data.files().get(1);
        assertEquals("tracked.txt", tracked.path());
        assertEquals(2, tracked.linesAdded());
        assertEquals(1, tracked.linesRemoved());
        assertFalse(tracked.isBinary());
        assertFalse(tracked.isLargeFile());
        assertFalse(tracked.isTruncated());
        assertFalse(tracked.isUntracked());

        List<StructuredPatchHunk> hunks = data.hunks().get("tracked.txt");
        assertNotNull(hunks);
        assertEquals(1, hunks.size());
        assertTrue(hunks.getFirst().lines().contains("+TWO"));
        assertTrue(hunks.getFirst().lines().contains("-two"));
        assertTrue(hunks.getFirst().lines().contains("+four"));
    }

    @Test
    void collect_cleanRepoHasEmptyDiff(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");

        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");

        DiffData data = new GitDiffCollector(dir.toString()).collect();
        assertNotNull(data, "clean repo is still a repo — empty diff, not null");
        assertEquals(new DiffData.Stats(0, 0, 0), data.stats());
        assertTrue(data.files().isEmpty());
        assertTrue(data.hunks().isEmpty());
    }

    @Test
    void collect_transientMergeStateReturnsNull(@TempDir Path dir) throws Exception {
        assumeTrue(gitAvailable(), "git executable not available");

        run(dir, "git", "init", "-q");
        run(dir, "git", "config", "user.email", "test@example.com");
        run(dir, "git", "config", "user.name", "Test");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        run(dir, "git", "add", ".");
        run(dir, "git", "commit", "-q", "-m", "init");
        // Simulate an in-flight merge by dropping the probe file directly.
        Files.writeString(dir.resolve(".git").resolve("MERGE_HEAD"), "deadbeef\n");

        assertNull(new GitDiffCollector(dir.toString()).collect());
    }

    private static boolean gitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start();
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed (" + code + "): " + out);
        }
    }
}
