package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProcessUtilsTest {
    @Test void inspectsCurrentProcessWithoutPlatformCommands() {
        long pid = ProcessHandle.current().pid();
        assertTrue(ProcessUtils.isProcessRunning(pid));
        assertFalse(ProcessUtils.isProcessRunning(1));
        assertNotNull(ProcessUtils.processCommand(pid));
        assertNotNull(ProcessUtils.ancestorPids(pid, 10));
        assertNotNull(ProcessUtils.childPids(pid));
    }
}
