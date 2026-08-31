package com.claudecode.lsp;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Built-in zero-config preset for Eclipse jdt.ls, the Java language server.
 * Resolves a launch command without requiring the user to configure
 * plugin {@code lspServers.java} entry — a bare
 * {@code {"java": {}}} entry (or no entry at all) is enough to activate it,
 * as long as jdt.ls can be found on the system.
 */
public final class JdtLsPreset {

    private static final Logger LOG = LoggerFactory.getLogger(JdtLsPreset.class);

    public static final String LANGUAGE_ID = "java";

    private JdtLsPreset() {}

    /** Resolve the jdt.ls launch command, trying the environment variable then well-known install paths. */
    public static Optional<List<String>> resolveCommand() {
        return resolveCommand(null);
    }

    /**
     * @param explicitOverride caller-supplied launcher path from the plugin config, tried first
     */
    public static Optional<List<String>> resolveCommand(Path explicitOverride) {
        if (explicitOverride != null && Files.exists(explicitOverride)) {
            return Optional.of(List.of(explicitOverride.toString()));
        }

        String envPath = System.getenv("JDT_LS_PATH");
        if (StringUtils.isNotEmpty(envPath)) {
            Path p = Path.of(envPath);
            if (Files.exists(p)) {
                LOG.info("Found jdt.ls from JDT_LS_PATH: {}", envPath);
                return Optional.of(List.of(p.toString()));
            }
        }

        // Common locations for jdt.ls
        List<Path> candidates = List.of(
            // VS Code extension (various versions)
            Path.of(System.getProperty("user.home"), ".vscode", "extensions", "redhat.java",
                "1.0.0", "server", "jdt.ls", "bin", "jdt.ls.sh"),
            Path.of(System.getProperty("user.home"), ".vscode", "extensions", "redhat.java",
                "1.30.0", "server", "jdt.ls", "bin", "jdt.ls.sh"),
            Path.of(System.getProperty("user.home"), ".vscode", "extensions", "redhat.java",
                "server", "jdt.ls", "bin", "jdt.ls.sh"),
            // Linux/macOS common installs
            Path.of(System.getProperty("user.home"), ".local", "share", "jdt.ls", "bin", "jdt.ls.sh"),
            Path.of("/usr/local/lib/jdt.ls/bin/jdt.ls.sh"),
            Path.of("/opt/jdt.ls/bin/jdt.ls.sh"),
            // Standalone jdt.ls
            Path.of(System.getProperty("user.home"), "jdt-language-server", "jdt.ls", "bin", "jdt.ls.sh")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                LOG.info("Found jdt.ls at: {}", candidate);
                return Optional.of(List.of(candidate.toString()));
            }
        }

        return Optional.empty();
    }

    /** Check if jdt.ls is available on this system without launching it. */
    public static boolean isAvailable() {
        return resolveCommand().isPresent();
    }

    /**
     * Build a "java" {@link ProcessLspServerInstance} if jdt.ls can be found.
     *
     * @param explicitOverride caller-supplied launcher path, or {@code null} to auto-discover
     * @param registry         diagnostic registry to wire into the instance, may be {@code null}
     */
    public static Optional<ProcessLspServerInstance> create(Path explicitOverride, LspDiagnosticRegistry registry) {
        return resolveCommand(explicitOverride)
            .map(command -> new ProcessLspServerInstance(LANGUAGE_ID, command, Map.of(), registry));
    }
}
