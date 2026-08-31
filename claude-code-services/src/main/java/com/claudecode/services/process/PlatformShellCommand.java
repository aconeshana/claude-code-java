package com.claudecode.services.process;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.PowerShellEncodedCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Resolves a configured shell command to an executable argv on the current platform. */
@Explanation("Adapts hook and status-line shell execution to native Windows process discovery")
public final class PlatformShellCommand {

    private PlatformShellCommand() {}

    public static List<String> resolve(String requestedShell, String command) {
        return resolve(requestedShell, command, Platform.CURRENT,
            ExecutableFinder::bashExecutable, PlatformShellCommand::powerShellExecutable);
    }

    static List<String> resolve(String requestedShell, String command, Platform platform,
                                Supplier<String> bashExecutable,
                                Supplier<String> powerShellExecutable) {
        if (platform == Platform.WIN32 && StringUtils.isBlank(requestedShell)) {
            Optional<List<String>> encodedPowerShell = PowerShellEncodedCommand.argv(command);
            if (encodedPowerShell.isPresent()) return encodedPowerShell.get();
        }
        if (Strings.CI.equals("powershell", requestedShell)) {
            return powerShell(powerShellExecutable.get(), command);
        }
        if (!StringUtils.isBlank(requestedShell)
                && !Strings.CI.equals("bash", requestedShell)) {
            return List.of(requestedShell, "-c", command);
        }
        if (platform == Platform.WIN32 && StringUtils.isBlank(requestedShell)) {
            try {
                return bash(bashExecutable.get(), command);
            } catch (IllegalStateException _) {
                return powerShell(powerShellExecutable.get(), command);
            }
        }
        return bash(bashExecutable.get(), command);
    }

    private static List<String> bash(String executable, String command) {
        return List.of(executable, "-c", command);
    }

    private static List<String> powerShell(String executable, String command) {
        return List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
            command);
    }

    private static String powerShellExecutable() {
        return ExecutableFinder.find("pwsh")
            .or(() -> ExecutableFinder.find("powershell"))
            .map(Path::toString)
            .orElse(Platform.IS_WINDOWS ? "powershell.exe" : "pwsh");
    }
}
