package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;
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
 * Verifies {@link PermissionSettings#loadAdditionalDirectories(String)} and {@link
 * PermissionSettings#saveAdditionalDirectoryToLocalSettings(String, String)} — the persistence side
 * of {@code /add-dir}'s "remember this directory" option.
 */
class SettingsAdditionalDirectoriesTest {

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
        assertTrue(PermissionSettings.loadAdditionalDirectories(tempDir.toString()).isEmpty());
    }

    @Test
    void saveWritesToLocalTierUnderPermissionsKey() throws IOException {
        PermissionSettings.saveAdditionalDirectoryToLocalSettings(tempDir.toString(), "/tmp/project-a");
        assertTrue(Files.isReadable(localSettings), "save must target settings.local.json");
        String content = Files.readString(localSettings);
        assertTrue(Strings.CS.contains(content, "/tmp/project-a"));
        assertTrue(Strings.CS.contains(content, "additionalDirectories"));
    }

    @Test
    void saveThenLoad_roundTrips() {
        PermissionSettings.saveAdditionalDirectoryToLocalSettings(tempDir.toString(), "/tmp/project-a");
        assertEquals(List.of("/tmp/project-a"), PermissionSettings.loadAdditionalDirectories(tempDir.toString()));
    }

    @Test
    void saveTwice_dedupes() {
        PermissionSettings.saveAdditionalDirectoryToLocalSettings(tempDir.toString(), "/tmp/project-a");
        PermissionSettings.saveAdditionalDirectoryToLocalSettings(tempDir.toString(), "/tmp/project-a");
        assertEquals(List.of("/tmp/project-a"), PermissionSettings.loadAdditionalDirectories(tempDir.toString()));
    }

    @Test
    void savePreservesOtherKeysInLocalSettings() throws IOException {
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"prefersReducedMotion\": true}");
        PermissionSettings.saveAdditionalDirectoryToLocalSettings(tempDir.toString(), "/tmp/project-a");
        assertTrue(RuntimeSettings.loadPrefersReducedMotion(),
            "unrelated top-level key must survive the read-modify-write");
        assertEquals(List.of("/tmp/project-a"), PermissionSettings.loadAdditionalDirectories(tempDir.toString()));
    }

    @Test
    void loadUnionsAcrossTiersAndDedupes() throws IOException {
        Files.writeString(userSettings, "{\"permissions\": {\"additionalDirectories\": [\"/tmp/from-user\", \"/tmp/shared\"]}}");
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"permissions\": {\"additionalDirectories\": [\"/tmp/shared\", \"/tmp/from-local\"]}}");

        List<String> result = PermissionSettings.loadAdditionalDirectories(tempDir.toString());
        assertEquals(3, result.size());
        assertTrue(result.containsAll(List.of("/tmp/from-user", "/tmp/shared", "/tmp/from-local")));
    }

    @Test
    void malformedLocalSettings_doesNotThrow() throws IOException {
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{ not valid json");
        assertTrue(PermissionSettings.loadAdditionalDirectories(tempDir.toString()).isEmpty());
    }
}
