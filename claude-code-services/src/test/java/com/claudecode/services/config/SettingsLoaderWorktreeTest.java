package com.claudecode.services.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link WorkspaceSettings#loadWorktreeSymlinkDirectories(String)} — reads
 * {@code worktree.symlinkDirectories} across User/Project/Local settings tiers,
 * order-preserving + deduped. matches the additionalDirectories tier-merge test.
 */
class SettingsWorktreeTest {

    @TempDir Path tempDir;

    private String originalHome;
    private String originalDir;
    private Path userSettings;
    private Path localSettings;

    @BeforeEach
    void redirect() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        userSettings = claudeDir.resolve("settings.json");
        localSettings = claudeDir.resolve("settings.local.json");
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void missingFiles_returnsEmptyList() {
        assertTrue(WorkspaceSettings.loadWorktreeSymlinkDirectories(tempDir.toString()).isEmpty());
        assertEquals("fresh", WorkspaceSettings.loadWorktreeBaseRef(tempDir.toString()));
    }

    @Test
    void readsSymlinkDirectoriesFromUserSettings() throws IOException {
        Files.writeString(userSettings,
            "{\"worktree\": {\"symlinkDirectories\": [\"node_modules\", \".venv\"]}}");
        assertEquals(List.of("node_modules", ".venv"),
            WorkspaceSettings.loadWorktreeSymlinkDirectories(tempDir.toString()));
    }

    @Test
    void unionsAcrossTiersAndDedupes() throws IOException {
        Files.writeString(userSettings,
            "{\"worktree\": {\"symlinkDirectories\": [\"node_modules\", \"shared\"]}}");
        Files.writeString(localSettings,
            "{\"worktree\": {\"symlinkDirectories\": [\"shared\", \"target\"]}}");

        List<String> result = WorkspaceSettings.loadWorktreeSymlinkDirectories(tempDir.toString());
        assertEquals(List.of("node_modules", "shared", "target"), result);
    }

    @Test
    void noWorktreeSection_returnsEmpty() throws IOException {
        Files.writeString(userSettings, "{\"permissions\": {\"additionalDirectories\": [\"/x\"]}}");
        assertTrue(WorkspaceSettings.loadWorktreeSymlinkDirectories(tempDir.toString()).isEmpty());
    }

    @Test
    void malformedSettings_doesNotThrow() throws IOException {
        Files.writeString(userSettings, "{ not valid json");
        assertTrue(WorkspaceSettings.loadWorktreeSymlinkDirectories(tempDir.toString()).isEmpty());
        assertEquals("fresh", WorkspaceSettings.loadWorktreeBaseRef(tempDir.toString()));
    }

    @Test
    void baseRefUsesLaterSettingsTierAndAcceptsHead() throws IOException {
        Files.writeString(userSettings, "{\"worktree\": {\"baseRef\": \"fresh\"}}");
        Files.writeString(localSettings, "{\"worktree\": {\"baseRef\": \"head\"}}");

        assertEquals("head", WorkspaceSettings.loadWorktreeBaseRef(tempDir.toString()));
    }
}
