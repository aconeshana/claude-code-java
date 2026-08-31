package com.claudecode.services.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies {@link RuntimeSettings#loadAutoMemoryEnabled} reads {@code autoMemoryEnabled} across the
 * layered settings tiers, defaulting to {@code true}.
 */
class SettingsAutoMemoryTest {

    @TempDir Path tempDir;

    private String originalHome;
    private String originalDir;
    private Path userSettings;

    @BeforeEach
    void redirect() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        userSettings = claudeDir.resolve("settings.json");
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void missingFile_defaultsToTrue() {
        assertTrue(RuntimeSettings.loadAutoMemoryEnabled(),
            "absent settings must default to true (TS settings.autoMemoryEnabled ?? true)");
    }

    @Test
    void explicitFalseInUserTier_returnsFalse() throws IOException {
        Files.writeString(userSettings, "{\"autoMemoryEnabled\": false}");
        assertFalse(RuntimeSettings.loadAutoMemoryEnabled());
    }

    @Test
    void explicitTrueInUserTier_returnsTrue() throws IOException {
        Files.writeString(userSettings, "{\"autoMemoryEnabled\": true}");
        assertTrue(RuntimeSettings.loadAutoMemoryEnabled());
    }
}
