package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExternalEditorDefaultsTest {

    @Test
    void releasedWindowsDefaultUsesTheNativeTextEditor() {
        assertEquals("notepad.exe", ExternalEditorDefaults.commandFor(true));
        assertEquals("vi", ExternalEditorDefaults.commandFor(false));
    }
}
