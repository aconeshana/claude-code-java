package com.claudecode.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitUtilsTest {

    @Test
    void currentBranchReadsSymbolicNameBeforeFirstCommit(@TempDir Path repo) throws Exception {
        Process process = new ProcessBuilder("git", "init", "-q", "-b", "main")
            .directory(repo.toFile())
            .start();
        assertEquals(0, process.waitFor());

        assertEquals("main", GitUtils.currentBranch(repo));
    }
}
