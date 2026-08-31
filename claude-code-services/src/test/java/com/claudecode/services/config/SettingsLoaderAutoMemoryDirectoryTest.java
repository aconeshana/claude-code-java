package com.claudecode.services.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link WorkspaceSettings#loadAutoMemoryDirectory} — the trusted-tier (policy/local/user,
 * project excluded) reader that backs the {@code autoMemoryDirectory} settings override for the
 * auto/team-memory path resolver.
 */
class SettingsAutoMemoryDirectoryTest {

    @TempDir Path workDir;
    @TempDir Path homeDir;

    private String origHome;
    private String origDir;

    @BeforeEach
    void setUp() {
        origHome = System.getProperty("user.home");
        origDir = System.getProperty("user.dir");
        System.setProperty("user.home", homeDir.toString());
        System.setProperty("user.dir", workDir.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", origHome);
        System.setProperty("user.dir", origDir);
    }

    private void write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"autoMemoryDirectory\": \"" + value + "\"}");
    }

    @Test
    void projectTier_excluded_returnsNull() throws IOException {
        write(SettingsPaths.projectSettingsPath(workDir.toString()), "/abs/from/project");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory(),
            "project settings must NOT supply autoMemoryDirectory");
    }

    @Test
    void localTier_withTildeExpansion() throws IOException {
        write(SettingsPaths.localSettingsPath(workDir.toString()), "~/memdir");
        String result = WorkspaceSettings.loadAutoMemoryDirectory();
        assertEquals(Path.of(homeDir.toString(), "memdir").normalize()
            + File.separator, result);
    }

    @Test
    void userTier_absolutePath_accepted() throws IOException {
        write(SettingsPaths.userSettingsPath(), "/tmp/accepted");
        assertEquals("/tmp/accepted" + File.separator,
            WorkspaceSettings.loadAutoMemoryDirectory());
    }

    @Test
    void relativePath_rejected_returnsNull() throws IOException {
        write(SettingsPaths.userSettingsPath(), "relative/dir");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());
    }

    @Test
    void bareTilde_rejected_returnsNull() throws IOException {
        write(SettingsPaths.userSettingsPath(), "~");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());
    }

    @Test
    void nulByte_rejected_returnsNull() throws IOException {
        write(SettingsPaths.userSettingsPath(), "\\u0000/tmp/memory");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());
    }

    @Test
    void localPrecedenceOverUser() throws IOException {
        write(SettingsPaths.userSettingsPath(), "/tmp/user");
        write(SettingsPaths.localSettingsPath(workDir.toString()), "/tmp/local");
        assertEquals("/tmp/local" + File.separator,
            WorkspaceSettings.loadAutoMemoryDirectory());
    }

    @Test
    void emptyLocalTierBlocksLowerUserTier() throws IOException {
        write(SettingsPaths.userSettingsPath(), "/tmp/user");
        write(SettingsPaths.localSettingsPath(workDir.toString()), "");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory(),
            "an explicitly empty higher-priority value must not fall through to user settings");
    }

    @Test
    void unset_returnsNull() {
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());
    }
}
