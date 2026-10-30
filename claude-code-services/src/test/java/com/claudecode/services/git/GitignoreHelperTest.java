package com.claudecode.services.git;

import org.apache.commons.lang3.Strings;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


class GitignoreHelperTest {

    /**
     * The home directory under test is handed to {@link GitignoreHelper}
     * explicitly instead of being installed as the process-wide
     * {@code user.home}. Redirecting the global property made every assertion
     * depend on the machine running the build: the real
     * {@code ~/.config/git/ignore} is consulted by the {@code git check-ignore}
     * subprocess, so a developer whose global ignore already covered
     * {@code .claude/settings.local.json} turned every "appends the pattern"
     * case into a silent no-op.
     */
    @TempDir Path tempDir;

    private Path home() {
        return tempDir.resolve("home");
    }

    private Path globalGitignore() {
        return home().resolve(".config/git/ignore");
    }

    @Test
    void outsideGitRepo_noOp() throws Exception {
        Path notARepo = tempDir.resolve("not-a-repo");
        Files.createDirectories(notARepo);
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", notARepo.toString(), home());
        assertFalse(Files.exists(globalGitignore()), "must not create a gitignore file outside a git repo");
    }

    @Test
    void insideGitRepo_appendsGlobPattern() throws Exception {
        Path repo = initGitRepo("repo1");
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home());
        assertTrue(Files.isReadable(globalGitignore()));
        String content = Files.readString(globalGitignore());
        assertTrue(Strings.CS.contains(content, "**/.claude/settings.local.json"), content);
    }

    @Test
    void calledTwice_doesNotDuplicateEntry() throws Exception {
        Path repo = initGitRepo("repo2");
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home());
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home());
        String content = Files.readString(globalGitignore());
        int occurrences = content.split("\\Q**/.claude/settings.local.json\\E", -1).length - 1;
        assertEquals(1, occurrences, content);
    }

    @Test
    void alreadyGitignoredByProjectFile_doesNotDuplicateInGlobalFile() throws Exception {
        Path repo = initGitRepo("repo3");
        Files.writeString(repo.resolve(".gitignore"), ".claude/settings.local.json\n");
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home());
        assertFalse(Files.exists(globalGitignore()),
            "already covered by the project's own .gitignore — no need to touch the global one");
    }

    @Test
    void anUnknownHome_doesNotTouchTheProcessHome() throws Exception {
        Path repo = initGitRepo("repo4");
        // A caller that resolved no home contributes nothing rather than
        // falling back to whatever user.home happens to hold.
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), null);
        assertFalse(Files.exists(globalGitignore()));
    }

    @Test
    void aRuleWrittenForAHomeIsSeenWhenTheSameHomeIsSuppliedAgain() throws Exception {
        Path repo = initGitRepo("repo5");
        Path home = home();
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home);
        String first = Files.readString(globalGitignore());

        // The second call must observe its own prior write through git's global
        // ignore resolution; if the HOME handed to git and the file written here
        // ever diverge, this is the case that catches it.
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", repo.toString(), home);
        assertEquals(first, Files.readString(globalGitignore()));
    }

    private Path initGitRepo(String name) throws Exception {
        Path repo = tempDir.resolve(name);
        Files.createDirectories(repo);
        Process init = new ProcessBuilder("git", "init", "-q").directory(repo.toFile()).start();
        assertTrue(init.waitFor(5, TimeUnit.SECONDS));
        return repo;
    }
}