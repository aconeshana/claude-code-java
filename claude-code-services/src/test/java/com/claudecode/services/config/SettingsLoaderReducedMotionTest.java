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

/**
 * Verifies {@link RuntimeSettings#loadPrefersReducedMotion} reads {@code prefersReducedMotion}
 * across the layered settings tiers (user → project → local, local wins.
 */
class SettingsReducedMotionTest {

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
    void missingFile_returnsFalse() {
        assertFalse(RuntimeSettings.loadPrefersReducedMotion(),
            "absent settings must default to false");
    }

    @Test
    void explicitTrueInUserTier_returnsTrue() throws IOException {
        Files.writeString(userSettings, "{\"prefersReducedMotion\": true}");
        assertTrue(RuntimeSettings.loadPrefersReducedMotion());
    }

    @Test
    void explicitFalseInUserTier_returnsFalse() throws IOException {
        Files.writeString(userSettings, "{\"prefersReducedMotion\": false}");
        assertFalse(RuntimeSettings.loadPrefersReducedMotion());
    }

    @Test
    void localTierOverridesUserTier() throws IOException {
        Files.writeString(userSettings, "{\"prefersReducedMotion\": false}");
        Files.writeString(localSettings, "{\"prefersReducedMotion\": true}");
        assertTrue(RuntimeSettings.loadPrefersReducedMotion(),
            "local tier must win over user tier (TS merge order)");
    }

    @Test
    void policyTierOverridesAllLowerTiers() throws IOException {
        Files.writeString(userSettings, "{\"prefersReducedMotion\": false}");
        Files.writeString(localSettings, "{\"prefersReducedMotion\": false}");
        Path fakePolicy = tempDir.resolve("fake-managed-settings.json");
        Files.writeString(fakePolicy, "{\"prefersReducedMotion\": true}");
        boolean result = RuntimeSettings.loadLayeredBoolean("prefersReducedMotion", false,
            List.of(userSettings, localSettings, fakePolicy));
        assertTrue(result, "policy tier must win over user/project/local (TS settings.ts:801)");
    }

    @Test
    void saveWritesToLocalTierAndRoundTrips() {
        RuntimeSettings.savePrefersReducedMotion(true);
        assertTrue(Files.isReadable(localSettings), "save must target settings.local.json");
        assertFalse(Files.isReadable(userSettings), "save must not touch the user tier");
        assertTrue(RuntimeSettings.loadPrefersReducedMotion());
    }

    @Test
    void keyAbsentFromJson_returnsFalse() throws IOException {
        Files.writeString(userSettings, "{\"someOtherKey\": 42}");
        assertFalse(RuntimeSettings.loadPrefersReducedMotion(),
            "missing key must fall back to default false");
    }

    @Test
    void malformedJson_returnsFalse() throws IOException {
        Files.writeString(userSettings, "{ not valid json");
        assertFalse(RuntimeSettings.loadPrefersReducedMotion(),
            "malformed JSON must degrade to false rather than throwing");
    }
}
