package com.claudecode.core.prompt;

import com.claudecode.core.process.SubprocessEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * Stubs the platform identity the frozen TS goldens were captured with
 * ({@code Platform: darwin}, {@code Shell: zsh}, {@code OS Version: Darwin 23.5.0}).
 *
 * <p>The golden fixtures are verbatim TypeScript references — rewriting them per
 * platform would destroy their value — so the tests render under the captured
 * platform instead: {@code os.name}/{@code os.version} system properties plus a
 * {@code SHELL} runtime override (the same seam the process environment uses).
 * Restore between tests so production behavior on the real host is unaffected.
 *
 * <p>{@code OS Version} is not read from {@code os.version} on Unix: the section
 * runs {@code uname -r} and only falls back to the property when that fails. On a
 * macOS host the real {@code uname} happens to answer {@code 23.5.0}, which is why
 * this suite passed locally and only failed on the Linux runners, where it answers
 * a kernel string.
 *
 * <p>The stub therefore supplies its own {@code uname} and puts it first on the
 * {@code PATH} of the process-environment seam. That seam is what matters:
 * {@code EnvInfoSection} resolves the command through {@code ExecutableFinder},
 * which reads the overlaid snapshot, so an override is honoured. Prefixing
 * {@code PATH} for a plain {@link ProcessBuilder} would <em>not</em> work — the
 * JDK locates a bare command name with the JVM's own environment, ignoring the
 * map handed to the child.
 */
final class DarwinGoldenEnvironment {

    /** What {@code uname -r} reported when the TS fixtures were captured. */
    private static final String CAPTURED_KERNEL_RELEASE = "23.5.0";

    private static String osName;
    private static String osVersion;
    private static Path stubBinDir;

    private DarwinGoldenEnvironment() {}

    static void stub() {
        osName = System.setProperty("os.name", "Mac OS X");
        osVersion = System.setProperty("os.version", CAPTURED_KERNEL_RELEASE);
        stubBinDir = createUnameStub();
        SubprocessEnvironment.updateRuntime(Map.of(
            "SHELL", "/bin/zsh",
            "PATH", stubBinDir + ":" + SubprocessEnvironment.get("PATH")));
    }

    /**
     * A directory holding only a {@code uname} that reports the captured release.
     * Prefixing it to {@code PATH} leaves every other tool reachable.
     */
    private static Path createUnameStub() {
        try {
            Path dir = Files.createTempDirectory("darwin-golden-bin");
            Path uname = dir.resolve("uname");
            Files.writeString(uname, "#!/bin/sh\necho " + CAPTURED_KERNEL_RELEASE + "\n",
                StandardCharsets.UTF_8);
            // A non-executable stub would silently fall through to the host's
            // uname and fail the goldens with a confusing kernel-string diff, so
            // surface the cause here instead.
            if (!uname.toFile().setExecutable(true, false)) {
                throw new IOException("cannot mark the uname stub executable: " + uname);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the uname stub", e);
        }
    }

    static void restore() {
        if (osName == null) System.clearProperty("os.name");
        else System.setProperty("os.name", osName);
        if (osVersion == null) System.clearProperty("os.version");
        else System.setProperty("os.version", osVersion);
        SubprocessEnvironment.clearRuntimeOverrides();
        deleteStubBinDir();
        osName = null;
        osVersion = null;
    }

    private static void deleteStubBinDir() {
        if (stubBinDir == null) return;
        try (var entries = Files.walk(stubBinDir)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // A leaked temp dir must not fail the suite.
                }
            });
        } catch (IOException _) {
            // Same: best-effort cleanup only.
        }
        stubBinDir = null;
    }
}