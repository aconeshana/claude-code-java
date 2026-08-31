package com.claudecode.tools.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskOutputPathsTest {

    @AfterEach
    void resetMemoizedDirectory() {
        TaskOutputPaths.resetForTest();
    }

    @Test
    void outputPathUsesProjectAndSessionScopedTempDirectory(@TempDir Path tempDir) {
        Path originalCwd = Path.of("/Users/example/my.project");

        Path claudeTempDir = tempDir.resolve("claude-42");
        TaskOutputPaths.configureForTest(claudeTempDir, "session-123", originalCwd);

        assertEquals(
            claudeTempDir
                .resolve("-Users-example-my-project")
                .resolve("session-123")
                .resolve("tasks")
                .resolve("babc1234.output"),
            TaskOutputPaths.outputPath("babc1234"));
    }

    @Test
    void firstResolvedSessionDirectoryRemainsStableAcrossSessionChanges(@TempDir Path tempDir) {
        Path originalCwd = Path.of("/repo");
        TaskOutputPaths.configureForTest(tempDir.resolve("claude-42"), "first-session", originalCwd);
        Path first = TaskOutputPaths.outputPath("b1");

        TaskOutputPaths.configureForTest(tempDir.resolve("claude-42"), "after-clear", originalCwd);

        assertEquals(first.getParent().resolve("b2.output"), TaskOutputPaths.outputPath("b2"));
    }
}
