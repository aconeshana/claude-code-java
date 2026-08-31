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
import static org.junit.jupiter.api.Assertions.assertFalse;


class SettingsThinkingTest {

    @TempDir Path tempDir;

    private String originalHome;
    private String originalDir;
    private Path settingsFile;
    private Path localSettingsFile;

    @BeforeEach
    void redirectHome() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        settingsFile = claudeDir.resolve("settings.json");
        localSettingsFile = claudeDir.resolve("settings.local.json");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDir);
    }

    @Test
    void missingFile_returnsTrue() {

        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "absent settings.json must default to true");
    }

    @Test
    void explicitTrue_returnsTrue() throws IOException {
        Files.writeString(settingsFile, "{\"alwaysThinkingEnabled\": true}");
        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled());
    }

    @Test
    void explicitFalse_returnsFalse() throws IOException {
        Files.writeString(settingsFile, "{\"alwaysThinkingEnabled\": false}");
        assertFalse(RuntimeSettings.loadAlwaysThinkingEnabled());
    }

    @Test
    void keyAbsentFromJson_returnsTrue() throws IOException {
        // Settings file present but doesn't mention alwaysThinkingEnabled.
        Files.writeString(settingsFile, "{\"someOtherKey\": 42}");
        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "missing key must fall back to default true");
    }

    @Test
    void nullValue_returnsTrue() throws IOException {
        Files.writeString(settingsFile, "{\"alwaysThinkingEnabled\": null}");
        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "explicit null must fall back to true");
    }

    @Test
    void malformedJson_returnsTrue() throws IOException {
        // Malformed JSON → LOG.warn and return true (unlike permission rules which throw).
        Files.writeString(settingsFile, "{ not valid json");
        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "malformed JSON must degrade to true rather than throwing");
    }

    @Test
    void localTierOverridesUserTier() throws IOException {
        Files.writeString(settingsFile, "{\"alwaysThinkingEnabled\": true}");
        Files.writeString(localSettingsFile, "{\"alwaysThinkingEnabled\": false}");
        assertFalse(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "local tier must win over user tier (TS merge order) — a prior "
                + "implementation only read the user tier and ignored this");
    }

    @Test
    void policyTierOverridesAllLowerTiers() throws IOException {
        Files.writeString(settingsFile, "{\"alwaysThinkingEnabled\": true}");
        Files.writeString(localSettingsFile, "{\"alwaysThinkingEnabled\": true}");
        Path fakePolicy = tempDir.resolve("fake-managed-settings.json");
        Files.writeString(fakePolicy, "{\"alwaysThinkingEnabled\": false}");
        boolean result = RuntimeSettings.loadLayeredBoolean("alwaysThinkingEnabled", true,
            List.of(settingsFile, localSettingsFile, fakePolicy));
        assertFalse(result, "policy tier must win over user/project/local (TS settings.ts:801)");
    }
}
