package com.claudecode.services.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trusted-source and persistence coverage for the bypass-permissions acknowledgement. */
class SettingsBypassPromptTest {

    @TempDir Path tempDir;

    private String originalHome;
    private String originalDir;
    private Path home;
    private Path project;

    @BeforeEach
    void redirectPaths() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project.resolve(".claude"));
    }

    @AfterEach
    void restorePaths() {
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void projectSettingCannotSuppressSafetyPrompt() throws IOException {
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"skipDangerousModePermissionPrompt\":true}");

        assertFalse(PermissionSettings.hasSkipDangerousModePermissionPrompt());
    }

    @Test
    void acceptWritesUserSettingAndRoundTrips() {
        PermissionSettings.saveSkipDangerousModePermissionPrompt();

        assertTrue(PermissionSettings.hasSkipDangerousModePermissionPrompt());
        assertTrue(Files.isRegularFile(SettingsPaths.userSettingsPath()));
    }

    @Test
    void autoModeEntryWarningTrustsLocalButNotProjectSettings() throws IOException {
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"skipAutoPermissionPrompt\":true}");
        assertFalse(PermissionSettings.hasSkipAutoPermissionPrompt(),
            "checked-in project settings cannot hide the safety notice");

        Files.writeString(project.resolve(".claude/settings.local.json"),
            "{\"skipAutoPermissionPrompt\":true}");

        assertTrue(PermissionSettings.hasSkipAutoPermissionPrompt(),
            "local settings are a trusted source for the auto-mode acknowledgement");
    }
}
