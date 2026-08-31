package com.claudecode.runtime.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsManagementPortTest {

    @Test
    void noneProvidesSafeReadsAndRejectsWrites() {
        SettingsManagementPort port = SettingsManagementPort.none();

        assertTrue(port.configuration().values("/workspace").isEmpty());
        assertFalse(port.configuration().syntaxHighlightingDisabled());
        assertEquals("dark", port.preferences().theme());
        assertFalse(port.preferences().copyFullResponse());
        assertTrue(port.preferences().settingSourceLabels("/workspace").isEmpty());
        assertFalse(port.preferences().hasStoredApiKey());
        assertFalse(port.sandbox().lockedByPolicy());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> port.configuration().save("/workspace", "theme", "light"));
        assertEquals("Settings management is not wired", error.getMessage());
    }
}
