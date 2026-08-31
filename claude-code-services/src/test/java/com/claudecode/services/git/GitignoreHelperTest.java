package com.claudecode.services.git;

import org.apache.commons.lang3.Strings;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


class GitignoreHelperTest {

    @TempDir Path tempDir;

    private String originalHome;
    private Path globalGitignore;

    @BeforeEach
    void redirectHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        globalGitignore = tempDir.resolve(".config/git/ignore");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void outsideGitRepo_noOp() throws Exception {
        Path notARepo = tempDir.resolve("not-a-repo");
        Files.createDirectories(notARepo);
        GitignoreHelper.addFileGlobRuleToGitignore(".claude/settings.local.json", notARepo.toString());
        assertFalse(Files.exists(globalGitignore), "must not create a gitignore file outside a git repo");
    }

    @Test
    void insideGitRepo_appendsGlobPattern() throws Exception {
        Path repo = initGitRepo("repo1");
        GitignoreHelper.addFileGlobRuleToGitignore(".claude/settings.local.json", repo.toString());
        assertTrue(Files.isReadable(globalGitignore));
        String content = Files.readString(globalGitignore);
        assertTrue(Strings.CS.contains(content, "**/.claude/settings.local.json"), content);
    }

    @Test
    void calledTwice_doesNotDuplicateEntry() throws Exception {
        Path repo = initGitRepo("repo2");
        GitignoreHelper.addFileGlobRuleToGitignore(".claude/settings.local.json", repo.toString());
        GitignoreHelper.addFileGlobRuleToGitignore(".claude/settings.local.json", repo.toString());
        String content = Files.readString(globalGitignore);
        int occurrences = content.split("\\Q**/.claude/settings.local.json\\E", -1).length - 1;
        assertEquals(1, occurrences, content);
    }

    @Test
    void alreadyGitignoredByProjectFile_doesNotDuplicateInGlobalFile() throws Exception {
        Path repo = initGitRepo("repo3");
        Files.writeString(repo.resolve(".gitignore"), ".claude/settings.local.json\n");
        GitignoreHelper.addFileGlobRuleToGitignore(".claude/settings.local.json", repo.toString());
        assertFalse(Files.exists(globalGitignore),
            "already covered by the project's own .gitignore — no need to touch the global one");
    }

    private Path initGitRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo);
        Process init = new ProcessBuilder("git", "init", "-q").directory(repo.toFile()).start();
        assertTrue(init.waitFor(5, TimeUnit.SECONDS));
        return repo;
    }
}
