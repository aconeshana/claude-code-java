package com.claudecode.services.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileHistorySettingsTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve(".claude.json");
    }

    @Test
    void isEnabled_defaultTrue_whenNoConfigNoEnv() {
        assertTrue(FileHistorySettings.isEnabled(file(), null));
    }

    @Test
    void isEnabled_false_whenConfigDisabled() {
        GlobalConfigStore.set(file(), "fileCheckpointingEnabled", false);
        assertFalse(FileHistorySettings.isEnabled(file(), null));
    }

    @Test
    void isEnabled_true_whenConfigExplicitlyEnabled() {
        GlobalConfigStore.set(file(), "fileCheckpointingEnabled", true);
        assertTrue(FileHistorySettings.isEnabled(file(), null));
    }

    @Test
    void isEnabled_false_whenEnvForceDisabled_evenIfConfigTrue() {
        GlobalConfigStore.set(file(), "fileCheckpointingEnabled", true);
        assertFalse(FileHistorySettings.isEnabled(file(), "1"));
        assertFalse(FileHistorySettings.isEnabled(file(), "true"));
        assertFalse(FileHistorySettings.isEnabled(file(), "YES"));
        assertFalse(FileHistorySettings.isEnabled(file(), "on"));
    }

    @Test
    void isEnabled_true_whenEnvBlankOrFalsy() {
        assertTrue(FileHistorySettings.isEnabled(file(), ""));
        assertTrue(FileHistorySettings.isEnabled(file(), "0"));
        assertTrue(FileHistorySettings.isEnabled(file(), "false"));
    }

    @Test
    void nonInteractiveDefaultsOffAndRequiresSdkEnableEnv() {
        assertFalse(FileHistorySettings.isEnabled(file(), null, true, null));
        assertTrue(FileHistorySettings.isEnabled(file(), null, true, "1"));
        assertFalse(FileHistorySettings.isEnabled(file(), "1", true, "1"));
    }
}
