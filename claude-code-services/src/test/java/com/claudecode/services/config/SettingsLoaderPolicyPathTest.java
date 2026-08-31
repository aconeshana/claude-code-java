package com.claudecode.services.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link SettingsPaths#policySettingsPath} resolves the exact paths
 * a
 * regression guard for a real bug found during the second {@code /config}
 * audit: the previous implementation (formerly hand-rolled in
 * {@code UiSettings}) used an underscored file name and the wrong Windows/Linux
 * directories, so file-based policy lockdown never actually took effect off
 * macOS.
 */
class SettingsPolicyPathContractTest {

    private String originalOsName;

    @BeforeEach
    void saveOsName() {
        originalOsName = System.getProperty("os.name");
    }

    @AfterEach
    void restoreOsName() {
        System.setProperty("os.name", originalOsName);
    }

    @Test
    void macOs_resolvesToApplicationSupportDirectory() {
        System.setProperty("os.name", "Mac OS X");
        assertEquals(Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }

    @Test
    void windows_resolvesToHardcodedProgramFilesPath() {
        System.setProperty("os.name", "Windows 11");
        assertEquals(Path.of("C:\\Program Files\\ClaudeCode", "managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }

    @Test
    void linux_resolvesToEtcClaudeCode() {
        System.setProperty("os.name", "Linux");
        assertEquals(Path.of("/etc/claude-code/managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }
}
