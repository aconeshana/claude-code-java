package com.claudecode.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Verifies {@link GitignoreFilter} shells {@code git check-ignore} correctly and degrades
 * gracefully outside a git repository.
 */
class GitignoreFilterTest {

    @TempDir
    Path dir;

    private Path gitInit(Path root) throws IOException, InterruptedException {
        run(root, "git", "init");
        run(root, "git", "config", "user.email", "test@example.com");
        run(root, "git", "config", "user.name", "test");
        return root;
    }

    private void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).start();
        p.waitFor();
    }

    @Test
    void filtersIgnoredPaths() throws Exception {
        Path root = gitInit(dir);
        Path kept = dir.resolve("kept.txt");
        Path ignored = dir.resolve("ignored.txt");
        Files.writeString(kept, "keep");
        Files.writeString(ignored, "ignore");
        Files.writeString(dir.resolve(".gitignore"), "ignored.txt\n");

        Set<String> result = GitignoreFilter.ignoredPaths(
            root, List.of(kept.toAbsolutePath().toString(), ignored.toAbsolutePath().toString()));

        assertEquals(Set.of(ignored.toAbsolutePath().toString()), result);
    }

    @Test
    void returnsEmptyWhenNothingIgnored() throws Exception {
        Path root = gitInit(dir);
        Path a = dir.resolve("a.txt");
        Path b = dir.resolve("b.txt");
        Files.writeString(a, "a");
        Files.writeString(b, "b");

        Set<String> result = GitignoreFilter.ignoredPaths(
            root, List.of(a.toAbsolutePath().toString(), b.toAbsolutePath().toString()));

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(GitignoreFilter.ignoredPaths(dir, List.of()).isEmpty());
    }

    @Test
    void nullRepoRootReturnsEmpty() {
        assertEquals(Set.of(), GitignoreFilter.ignoredPaths(null, List.of("/x")));
    }
}
