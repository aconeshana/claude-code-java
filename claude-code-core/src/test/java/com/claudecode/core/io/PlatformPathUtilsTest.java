package com.claudecode.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlatformPathUtilsTest {
    @Test
    void convertsWindowsPosixAndUncForms() {
        assertEquals("/c/Users/me", PathUtils.windowsPathToPosixPath("C:\\Users\\me"));
        assertEquals("//server/share", PathUtils.windowsPathToPosixPath("\\\\server\\share"));
        assertEquals("C:\\Users\\me", PathUtils.posixPathToWindowsPath("/c/Users/me"));
        assertEquals("C:\\Users\\me", PathUtils.posixPathToWindowsPath("/cygdrive/c/Users/me"));
    }
}
