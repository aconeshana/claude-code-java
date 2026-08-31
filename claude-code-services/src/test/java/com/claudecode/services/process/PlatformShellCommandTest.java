package com.claudecode.services.process;

import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.PowerShellEncodedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformShellCommandTest {

    @Test
    void windowsDefaultPrefersResolvedGitBash() {
        assertEquals(List.of("C:\\Program Files\\Git\\bin\\bash.exe", "-c", "echo ok"),
            PlatformShellCommand.resolve(null, "echo ok", Platform.WIN32,
                () -> "C:\\Program Files\\Git\\bin\\bash.exe", () -> "powershell.exe"));
    }

    @Test
    void windowsDefaultFallsBackToPowerShellWhenGitBashIsUnavailable() {
        assertEquals(List.of(
            "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
            "Write-Output ok"),
            PlatformShellCommand.resolve(null, "Write-Output ok", Platform.WIN32,
                () -> { throw new IllegalStateException("missing Git Bash"); },
                () -> "powershell.exe"));
    }

    @Test
    void windowsEncodedPowerShellCommandBypassesTheOuterShell() {
        String command = PowerShellEncodedCommand.encode(
            "$raw=[Console]::In.ReadToEnd(); Write-Output $raw.Length");

        assertEquals(PowerShellEncodedCommand.argv(command).orElseThrow(),
            PlatformShellCommand.resolve(null, command, Platform.WIN32,
                () -> "C:\\Program Files\\Git\\bin\\bash.exe", () -> "powershell.exe"));
    }

    @Test
    void explicitPowerShellUsesPowerShellOnEveryPlatform() {
        assertEquals(List.of(
            "pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "echo ok"),
            PlatformShellCommand.resolve("powershell", "echo ok", Platform.LINUX,
                () -> "/bin/bash", () -> "pwsh"));
    }

    @Test
    void explicitBashDoesNotSilentlyFallBack() {
        assertThrows(IllegalStateException.class,
            () -> PlatformShellCommand.resolve("bash", "echo ok", Platform.WIN32,
                () -> { throw new IllegalStateException("missing Git Bash"); },
                () -> "powershell.exe"));
    }
}
