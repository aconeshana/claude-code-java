package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitSettingsTest {

    @TempDir Path tempDir;

    private String originalHome;

    @BeforeEach
    void redirectHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void truthyDisableEnvironmentWinsOverSettings() {
        assertFalse(GitSettings.resolveIncludeGitInstructions("true", true));
        assertFalse(GitSettings.resolveIncludeGitInstructions("on", null));
    }

    @Test
    void explicitlyFalsyDisableEnvironmentForcesInstructionsOn() {
        assertTrue(GitSettings.resolveIncludeGitInstructions("false", false));
        assertTrue(GitSettings.resolveIncludeGitInstructions("0", false));
    }

    @Test
    void absentOrUnrecognizedEnvironmentUsesSettingThenDefaultsTrue() {
        assertFalse(GitSettings.resolveIncludeGitInstructions(null, false));
        assertTrue(GitSettings.resolveIncludeGitInstructions(null, true));
        assertTrue(GitSettings.resolveIncludeGitInstructions(null, null));
        assertFalse(GitSettings.resolveIncludeGitInstructions("unexpected", false));
    }

    @Test
    void onlyLocalSettingsWritesScheduleTheGitignoreSafetyRule() throws Exception {
        Path repository = initGitRepository();
        Path globalGitignore = tempDir.resolve(".config/git/ignore");

        GitSettings.ensureLocalSettingsIgnored(repository.toString(), RuleSource.PROJECT_SETTINGS);

        assertFalse(Files.exists(globalGitignore));

        GitSettings.ensureLocalSettingsIgnored(repository.toString(), RuleSource.LOCAL_SETTINGS);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            while (!Files.isReadable(globalGitignore)
                || !Strings.CS.contains(Files.readString(globalGitignore), "**/.claude/settings.local.json")) {
                Thread.sleep(10);
            }
        });
    }

    private Path initGitRepository() throws IOException, InterruptedException {
        Path repository = tempDir.resolve("repository");
        Files.createDirectories(repository);
        Process init = new ProcessBuilder("git", "init", "-q")
            .directory(repository.toFile())
            .start();
        assertTrue(init.waitFor(5, TimeUnit.SECONDS));
        return repository;
    }
}
