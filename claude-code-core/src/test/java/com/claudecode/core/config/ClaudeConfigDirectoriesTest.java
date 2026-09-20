package com.claudecode.core.config;

import com.claudecode.core.state.CwdState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudeConfigDirectoriesTest {

    @BeforeEach
    @AfterEach
    void isolateSessionCwd() {
        CwdState.clearForTesting();
    }

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

    /**
     * The home boundary is compared case-sensitively off Windows, matching {@code $k} in the
     * 2.1.236 bundle. Folding case unconditionally would mistake {@code Repo} for the home
     * {@code repo}, stop the walk one level early, and drop the repository's own directory.
     */
    @Test
    void doesNotTreatACaseOnlyDifferenceFromHomeAsTheHomeBoundary(@TempDir Path temp) throws Exception {
        Path repo = temp.resolve("Repo");
        Path nested = repo.resolve("packages/app");
        Path nestedSkills = nested.resolve(".claude/skills");
        Path rootSkills = repo.resolve(".claude/skills");
        Files.createDirectories(repo.resolve(".git"));
        Files.createDirectories(nestedSkills);
        Files.createDirectories(rootSkills);

        Path homeDifferingOnlyByCase = temp.resolve("repo");

        assertEquals(List.of(nestedSkills, rootSkills),
            ClaudeConfigDirectories.projectDirs(nested, "skills", homeDifferingOnlyByCase));
    }
}
