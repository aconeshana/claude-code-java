package com.claudecode.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class WindowsRuntimeDependencyTest {

    @Test
    void windowsTerminalNativeBackendIsOnRuntimeClasspath() {
        assertNotNull(
            getClass().getClassLoader().getResource("com/sun/jna/platform/win32/Wincon.class"),
            "Lanterna's Windows terminal requires jna-platform at runtime"
        );
    }
}
