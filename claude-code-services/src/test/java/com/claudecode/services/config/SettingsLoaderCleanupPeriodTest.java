package com.claudecode.services.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link RuntimeSettings#loadCleanupPeriodDays} — the layered-int
 * counterpart to {@link SettingsThinkingTest}'s boolean coverage.
 */
class SettingsCleanupPeriodTest {

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
    void missingFile_returns30() {
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays(),
            "absent settings.json must default to TS DEFAULT_CLEANUP_PERIOD_DAYS");
    }

    @Test
    void explicitValue_isRead() throws IOException {
        Files.writeString(settingsFile, "{\"cleanupPeriodDays\": 7}");
        assertEquals(7, RuntimeSettings.loadCleanupPeriodDays());
    }

    @Test
    void zero_isAValidExplicitValue() throws IOException {
        Files.writeString(settingsFile, "{\"cleanupPeriodDays\": 0}");
        assertEquals(0, RuntimeSettings.loadCleanupPeriodDays(),
            "0 is schema-valid (immediate cleanup, no grace period), must not fall back to default");
    }

    @Test
    void integralFloatingLiteral_isAcceptedLikeTypeScriptNumberInt() throws IOException {
        Files.writeString(settingsFile, "{\"cleanupPeriodDays\": 30.0}");
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays(),
            "Zod z.number().int() accepts 30.0; Jackson must not require an integer node type");
    }

    @Test
    void keyAbsentFromJson_returns30() throws IOException {
        Files.writeString(settingsFile, "{\"someOtherKey\": 42}");
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays());
    }

    @Test
    void malformedJson_returns30() throws IOException {
        Files.writeString(settingsFile, "{ not valid json");
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays(),
            "malformed JSON must degrade to the default rather than throwing");
    }

    @Test
    void localTierOverridesUserTier() throws IOException {
        Files.writeString(settingsFile, "{\"cleanupPeriodDays\": 30}");
        Files.writeString(localSettingsFile, "{\"cleanupPeriodDays\": 3}");
        assertEquals(3, RuntimeSettings.loadCleanupPeriodDays(),
            "local tier must win over user tier (TS merge order)");
    }

    @Test
    void policyTierOverridesAllLowerTiers() throws IOException {
        Files.writeString(settingsFile, "{\"cleanupPeriodDays\": 30}");
        Files.writeString(localSettingsFile, "{\"cleanupPeriodDays\": 30}");
        Path fakePolicy = tempDir.resolve("fake-managed-settings.json");
        Files.writeString(fakePolicy, "{\"cleanupPeriodDays\": 1}");
        int result = RuntimeSettings.loadLayeredInt("cleanupPeriodDays", 30,
            List.of(settingsFile, localSettingsFile, fakePolicy));
        assertEquals(1, result, "policy tier must win over user/project/local (TS settings.ts:801)");
    }
}
