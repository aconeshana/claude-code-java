package com.claudecode.core.process;

import com.claudecode.core.annotation.Explanation;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stable, quote-free PowerShell command encoding for Windows process boundaries. */
@Explanation("Avoids nested-shell quoting and preserves redirected stdin for Windows status lines")
public final class PowerShellEncodedCommand {

    private static final String EXECUTABLE = "powershell.exe";
    private static final List<String> OPTIONS = List.of(
        "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand");
    private static final String PREFIX = EXECUTABLE + " "
        + String.join(" ", OPTIONS) + " ";

    private PowerShellEncodedCommand() { }

    /** Encodes a PowerShell script using the UTF-16LE format required by {@code -EncodedCommand}. */
    public static String encode(String script) {
        Objects.requireNonNull(script, "script");
        String payload = Base64.getEncoder().encodeToString(
            script.getBytes(StandardCharsets.UTF_16LE));
        return PREFIX + payload;
    }

    /** Returns the direct process argv only for the exact encoding produced by {@link #encode}. */
    public static Optional<List<String>> argv(String command) {
        if (command == null || !command.startsWith(PREFIX)) return Optional.empty();
        String payload = command.substring(PREFIX.length());
        if (payload.isEmpty() || payload.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length == 0 || decoded.length % 2 != 0) return Optional.empty();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        return Optional.of(List.of(EXECUTABLE, OPTIONS.get(0), OPTIONS.get(1),
            OPTIONS.get(2), OPTIONS.get(3), payload));
    }
}
