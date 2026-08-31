package com.claudecode.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemDirectoriesTest {
    @Test
    void honorsLinuxXdgAndWindowsProfile() {
        var linux = SystemDirectories.resolve(
            Map.of("XDG_DOWNLOAD_DIR", "/mnt/downloads"), "/home/me", Platform.LINUX, false);
        assertEquals("/mnt/downloads", linux.get("DOWNLOADS"));
        var windows = SystemDirectories.resolve(
            Map.of("USERPROFILE", "D:\\Users\\me"), "C:\\Users\\me", Platform.WIN32, false);
        assertEquals(Path.of("D:\\Users\\me", "Desktop").toString(),
            windows.get("DESKTOP"));
    }
}
