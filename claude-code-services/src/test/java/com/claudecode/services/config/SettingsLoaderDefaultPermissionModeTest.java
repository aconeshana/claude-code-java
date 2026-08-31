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

import static org.junit.jupiter.api.Assertions.*;


class SettingsDefaultPermissionModeTest {

    @TempDir Path tempDir;

    private String originalHome;
    private Path settingsFile;

    @BeforeEach
    void redirectHome() throws IOException {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        settingsFile = claudeDir.resolve("settings.json");
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void saveThenLoad_roundTrips() {
        PermissionSettings.saveDefaultPermissionMode("plan");
        assertEquals("plan", PermissionSettings.loadDefaultPermissionMode());
    }

    @Test
    void saveNull_removesKey() {
        PermissionSettings.saveDefaultPermissionMode("plan");
        PermissionSettings.saveDefaultPermissionMode(null);
        assertNull(PermissionSettings.loadDefaultPermissionMode());
    }

    @Test
    void save_preservesUnrelatedTopLevelKeys() throws IOException {
        Files.writeString(settingsFile, "{\"verbose\": true}");
        PermissionSettings.saveDefaultPermissionMode("acceptEdits");

        String content = Files.readString(settingsFile);
        assertTrue(Strings.CS.contains(content, "\"verbose\""));
        assertTrue(Strings.CS.contains(content, "\"acceptEdits\""));
    }

    @Test
    void save_preservesSiblingPermissionsFields() throws IOException {
        Files.writeString(settingsFile,
            "{\"permissions\": {\"allow\": [\"Bash(git *)\"]}}");
        PermissionSettings.saveDefaultPermissionMode("plan");

        String content = Files.readString(settingsFile);
        assertTrue(Strings.CS.contains(content, "Bash(git *)"), content);
        assertTrue(Strings.CS.contains(content, "\"plan\""), content);
    }

    // ── layered read (user → project → local → policy, later wins) ─────────

    private Path tier(String name) {
        return tempDir.resolve(name);
    }

    @Test
    void allTiersAbsent_returnsNull() {
        List<Path> tiers = List.of(tier("user.json"), tier("project.json"), tier("local.json"), tier("policy.json"));
        assertNull(PermissionSettings.loadDefaultPermissionMode(tiers));
    }

    @Test
    void localTierOverridesUserTier() throws IOException {
        Path user = tier("user.json");
        Path local = tier("local.json");
        Files.writeString(user, "{\"permissions\": {\"defaultMode\": \"plan\"}}");
        Files.writeString(local, "{\"permissions\": {\"defaultMode\": \"acceptEdits\"}}");
        List<Path> tiers = List.of(user, tier("project.json"), local, tier("policy.json"));
        assertEquals("acceptEdits", PermissionSettings.loadDefaultPermissionMode(tiers));
    }

    @Test
    void policyTierOverridesAllOtherTiers() throws IOException {
        Path user = tier("user.json");
        Path local = tier("local.json");
        Path policy = tier("policy.json");
        Files.writeString(user, "{\"permissions\": {\"defaultMode\": \"plan\"}}");
        Files.writeString(local, "{\"permissions\": {\"defaultMode\": \"acceptEdits\"}}");
        Files.writeString(policy, "{\"permissions\": {\"defaultMode\": \"dontAsk\"}}");
        List<Path> tiers = List.of(user, tier("project.json"), local, policy);
        assertEquals("dontAsk", PermissionSettings.loadDefaultPermissionMode(tiers));
    }

    @Test
    void malformedJson_degradesToNullNotThrow() throws IOException {
        Path user = tier("user.json");
        Files.writeString(user, "{ not valid json");
        List<Path> tiers = List.of(user, tier("project.json"), tier("local.json"), tier("policy.json"));
        assertNull(PermissionSettings.loadDefaultPermissionMode(tiers));
    }

    @Test
    void nonObjectPermissionsField_returnsNull() throws IOException {
        Path user = tier("user.json");
        Files.writeString(user, "{\"permissions\": \"not-an-object\"}");
        List<Path> tiers = List.of(user, tier("project.json"), tier("local.json"), tier("policy.json"));
        assertNull(PermissionSettings.loadDefaultPermissionMode(tiers));
    }

    @Test
    void bypassPermissionsDisableSetting_isReadFromEffectiveLayers() throws IOException {
        Files.writeString(settingsFile,
            "{\"permissions\":{\"disableBypassPermissionsMode\":\"disable\"}}");
        assertTrue(PermissionSettings.isBypassPermissionsModeDisabled());
    }
}
