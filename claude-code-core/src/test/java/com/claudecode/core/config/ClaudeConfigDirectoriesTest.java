package com.claudecode.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeConfigDirectoriesTest {

    @Test
    void walksFromCwdToNearestGitRootAndDoesNotLeakParentConfig(@TempDir Path temp) throws Exception {
        Path repo = temp.resolve("repo");
        Path nested = repo.resolve("packages/app");
        Path nestedSkills = nested.resolve(".claude/skills");
        Path rootSkills = repo.resolve(".claude/skills");
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(nestedSkills);
        Files.createDirectories(rootSkills);
        Files.createDirectories(temp.resolve(".claude/skills"));

        assertEquals(List.of(nestedSkills, rootSkills),
            ClaudeConfigDirectories.projectDirs(nested, "skills", temp.getParent()));
    }
}
