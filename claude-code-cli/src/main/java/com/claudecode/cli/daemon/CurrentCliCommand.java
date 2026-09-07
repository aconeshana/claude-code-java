package com.claudecode.cli.daemon;

import com.claudecode.cli.ClaudeCodeCli;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.Strings;

/** Reconstructs a command that launches this CLI in a fresh JVM. */
public final class CurrentCliCommand {

    private CurrentCliCommand() {}

    public static List<String> resolve() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String sunCommand = System.getProperty("sun.java.command", "").trim();
        if (!sunCommand.isEmpty()) {
            String first = sunCommand.split("\\s+", 2)[0];
            if (Strings.CS.endsWith(first, ".jar")) return List.of(java, "-jar", first);
        }
        return List.of(java, "-cp", System.getProperty("java.class.path"),
            ClaudeCodeCli.class.getName());
    }
}
