package com.claudecode.core.memdir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link AutoMemoryPrompt#isAutoMemPath}. Doesn't assert on the literal
 * resolved directory (that depends on {@code ~/.claude} / git root, which
 * static-final-path tests must never hardcode — see project lesson on
 * test pollution) — only on membership relative to whatever
 * {@code resolveAutoMemPath} actually returns for the given cwd.
 */
class AutoMemoryPromptPathTest {

    @Test
    void fileInsideMemoryDirIsAutoMemPath(@TempDir Path cwd) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(cwd);
        Path memoryFile = memDir.resolve("feedback_testing.md");
        assertTrue(AutoMemoryPrompt.isAutoMemPath(memoryFile, cwd));
    }

    @Test
    void fileOutsideMemoryDirIsNotAutoMemPath(@TempDir Path cwd) {
        Path unrelated = cwd.resolve("src/main/Foo.java");
        assertFalse(AutoMemoryPrompt.isAutoMemPath(unrelated, cwd));
    }

    @Test
    void siblingDirWithMemoryPrefixIsNotAutoMemPath(@TempDir Path cwd) {
        Path memDir = AutoMemoryPrompt.resolveAutoMemPath(cwd);
        // path-segment prefix, not raw string prefix — "memory-archive" must
        // not match just because it starts with the string "memory"
        Path sibling = memDir.getParent().resolve(memDir.getFileName() + "-archive/note.md");
        assertFalse(AutoMemoryPrompt.isAutoMemPath(sibling, cwd));
    }
}
