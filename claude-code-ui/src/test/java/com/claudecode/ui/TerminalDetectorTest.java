package com.claudecode.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalDetectorTest {
    @Test
    void coversTerminalFallbacksFromOriginalEnvUtility() {
        assertEquals("windows-terminal", TerminalDetector.detect(Map.of("WT_SESSION", "1")));
        assertEquals("ssh-session", TerminalDetector.detect(Map.of("SSH_TTY", "/dev/pts/1")));
        assertEquals("wsl-Ubuntu", TerminalDetector.detect(Map.of("WSL_DISTRO_NAME", "Ubuntu")));
        assertEquals("alacritty", TerminalDetector.detect(Map.of("TERM", "alacritty-direct")));
    }
}
