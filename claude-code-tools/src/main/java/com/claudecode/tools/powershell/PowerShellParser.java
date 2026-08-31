package com.claudecode.tools.powershell;


import com.claudecode.core.process.SubprocessEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Isolated PowerShell syntax checker.
 */
final class PowerShellParser {

    private static final long TIMEOUT_MS = 5_000L;
    private static final String INPUT_ENV = "CLAUDE_CODE_PWSH_PARSE_INPUT";
    private static final String PARSE_SCRIPT = ""
        + "$c=[Environment]::GetEnvironmentVariable('CLAUDE_CODE_PWSH_PARSE_INPUT');"
        + "$t=$null;$e=$null;"
        + "[System.Management.Automation.Language.Parser]::ParseInput($c,[ref]$t,[ref]$e)|Out-Null;"
        + "if($null -eq $e -or $e.Count -eq 0){exit 0}else{exit 1}";

    private static volatile String executable;
    private static volatile boolean searched;

    private PowerShellParser() {}

    record ParseResult(boolean available, boolean valid, String detail) {
        static ParseResult unavailable(String detail) {
            return new ParseResult(false, false, detail);
        }
    }

    static ParseResult parse(String command) {
        String shell = findExecutable();
        if (shell == null) {
            return ParseResult.unavailable("PowerShell parser is unavailable");
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shell, "-NoLogo", "-NoProfile",
                "-NonInteractive", "-Command", PARSE_SCRIPT);
            SubprocessEnvironment.applyTo(builder.environment());
            builder.environment().put(INPUT_ENV, command == null ? "" : command);
            process = builder.start();
            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ParseResult(true, false, "PowerShell parser timed out");
            }
            if (process.exitValue() == 0) {
                return new ParseResult(true, true, null);
            }
            String error = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            return new ParseResult(true, false,
                StringUtils.isBlank(error) ? "PowerShell parser rejected the command" : error);
        } catch (IOException e) {
            return ParseResult.unavailable("PowerShell parser failed to start: " + e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
// builder.start either returns a live Process or throws, so a
            // non-null process here means start succeeded — destroy it.
            process.destroyForcibly();
            return new ParseResult(true, false, "PowerShell parser was interrupted");
        }
    }

    private static String findExecutable() {
        if (searched) return executable;
        synchronized (PowerShellParser.class) {
            if (searched) return executable;
            executable = probe("pwsh");
            if (executable == null) executable = probe("powershell");
            if (executable == null) executable = probeAbsolute("/opt/microsoft/powershell/7/pwsh");
            if (executable == null) executable = probeAbsolute("/usr/bin/pwsh");
            searched = true;
            return executable;
        }
    }

    private static String probe(String command) {
        String path = SubprocessEnvironment.get("PATH");
        if (StringUtils.isBlank(path)) return null;
        for (String directory : path.split(File.pathSeparator)) {
            if (StringUtils.isBlank(directory)) continue;
            Path candidate = Path.of(directory, command);
            if (Files.isRegularFile(candidate)
                    && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
            if (Strings.CS.contains(
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT), "win")) {
                Path exe = Path.of(directory, command + ".exe");
                if (Files.isRegularFile(exe)) return exe.toString();
            }
        }
        return null;
    }

    private static String probeAbsolute(String value) {
        Path candidate = Path.of(value);
        return Files.isRegularFile(candidate)
                && Files.isExecutable(candidate) ? candidate.toString() : null;
    }
}
