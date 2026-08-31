package com.claudecode.core.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsPathResolverTest {

    @Test
    void resolvesReleased197UserSettingsFilename() {
        Path home = Path.of("config-home");

        assertEquals(home.resolve("settings.json"),
            SettingsPathResolver.userSettingsPath(home, false));
        assertEquals(home.resolve("cowork_settings.json"),
            SettingsPathResolver.userSettingsPath(home, true));
    }

    @Test
    void resolvesManagedSettingsPathForEachPlatform() {
        assertEquals(Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
            SettingsPathResolver.policySettingsPath("Mac OS X", null, null));
        assertEquals(Path.of("C:\\Program Files\\ClaudeCode", "managed-settings.json"),
            SettingsPathResolver.policySettingsPath("Windows 11", null, null));
        assertEquals(Path.of("/etc/claude-code/managed-settings.json"),
            SettingsPathResolver.policySettingsPath("Linux", null, null));
        assertEquals(Path.of("/managed", "managed-settings.json"),
            SettingsPathResolver.policySettingsPath(
                "Linux", "ant", "/managed"));
    }

    @Test
    void derivesTheManagedSettingsDirectoryFromTheCanonicalFilePath() {
        assertEquals(SettingsPathResolver.policySettingsPath().getParent(),
            SettingsPathResolver.policySettingsDirectory());
    }
}
