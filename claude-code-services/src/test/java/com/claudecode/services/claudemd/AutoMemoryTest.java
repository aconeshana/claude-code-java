package com.claudecode.services.claudemd;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;


class AutoMemoryTest {

    @Test
    void isEnabled_defaultTrue_whenNoEnvNoSettingsOverride() {
        assertTrue(AutoMemory.isEnabled(null, null, null, null, true));
    }

    @Test
    void isEnabled_false_whenSettingsDisabled() {
        assertFalse(AutoMemory.isEnabled(null, null, null, null, false));
    }

    @Test
    void isEnabled_false_whenDisableAutoMemoryTruthy_evenIfSettingsEnabled() {
        assertFalse(AutoMemory.isEnabled("1", null, null, null, true));
        assertFalse(AutoMemory.isEnabled("true", null, null, null, true));
        assertFalse(AutoMemory.isEnabled("YES", null, null, null, true));
        assertFalse(AutoMemory.isEnabled("on", null, null, null, true));
    }

    @Test
    void isEnabled_true_whenDisableAutoMemoryDefinedFalsy_evenIfSettingsDisabled() {
        // isEnvDefinedFalsy short-circuits to true before the settings check.
        assertTrue(AutoMemory.isEnabled("0", null, null, null, false));
        assertTrue(AutoMemory.isEnabled("false", null, null, null, false));
        assertTrue(AutoMemory.isEnabled("off", null, null, null, false));
    }

    @Test
    void isEnabled_false_whenSimpleTruthy() {
        assertFalse(AutoMemory.isEnabled(null, "1", null, null, true));
    }

    @Test
    void isEnabled_false_whenRemoteTruthyWithoutMemoryDir() {
        assertFalse(AutoMemory.isEnabled(null, null, "1", null, true));
    }

    @Test
    void isEnabled_true_whenRemoteTruthyWithMemoryDir() {
        assertTrue(AutoMemory.isEnabled(null, null, "1", "/some/dir", true));
    }

    @Test
    void isEnabled_blankEnvValues_fallThroughToSettings() {
        assertTrue(AutoMemory.isEnabled("", "", "", "", true));
        assertFalse(AutoMemory.isEnabled("", "", "", "", false));
    }

    @Test
    void autoMemoryPath_delegatesToAutoMemoryPromptResolution(@TempDir Path workingDir) {
        String path = AutoMemory.autoMemoryPath(workingDir);
        assertTrue(Strings.CS.endsWith(path, "memory"),
            "auto-memory path must resolve to the memdir '.../memory' directory, got: " + path);
    }
}
