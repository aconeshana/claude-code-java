package com.claudecode.core.platform;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JVM OS platform detection — centralizes all {@code System.getProperty("os.name")} checks.
 */
public enum Platform {
    DARWIN, WIN32, LINUX, OTHER;

    /** Current OS platform, detected once at class-load time. */
    public static final Platform CURRENT = detect();
    public static final boolean IS_DARWIN  = CURRENT == DARWIN;
    public static final boolean IS_WINDOWS = CURRENT == WIN32;
    public static final boolean IS_LINUX   = CURRENT == LINUX;

    /**
     * Whether the current host is running under Windows Subsystem for Linux.
     */
    public static final boolean IS_WSL = detectWsl() > 0;

    /**
     * WSL version: {@code 2} for WSL2 (full Linux kernel, sandbox supported),
     * {@code 1} for WSL1 (no sandbox support), {@code 0} when not WSL.
     */
    public static final int WSL_VERSION = detectWsl();
    public static final Set<Platform> SUPPORTED_SANDBOX_PLATFORMS = Set.of(DARWIN, LINUX);

    private static final List<VcsMarker> VCS_MARKERS = List.of(
        new VcsMarker(".git", "git"), new VcsMarker(".hg", "mercurial"),
        new VcsMarker(".svn", "svn"), new VcsMarker(".p4config", "perforce"),
        new VcsMarker("$tf", "tfs"), new VcsMarker(".tfvc", "tfs"),
        new VcsMarker(".jj", "jujutsu"), new VcsMarker(".sl", "sapling"));

    private static Platform detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin")) return DARWIN;
        if (Strings.CS.contains(os, "win"))                          return WIN32;
        if (Strings.CS.contains(os, "nux") || Strings.CS.contains(os, "nix") || Strings.CS.contains(os, "aix")) return LINUX;
        return OTHER;
    }

    /**
     * Detect WSL and its version by inspecting the kernel release string.
     * WSL1: {@code 4.4.0-Microsoft}; WSL2: {@code 5.x.x-microsoft-standard-WSL2}
     * (or newer {@code ...-WSL2}). Returns 0 when not WSL.
     */
    private static int detectWsl() {
        if (CURRENT != LINUX) return 0;
        Path release = Path.of("/proc/sys/kernel/osrelease");
        String content;
        try {
            content = Files.readString(release);
        } catch (IOException _) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        if (!Strings.CS.contains(lower, "microsoft")) return 0;
        // WSL2 kernels carry the "WSL2" / "microsoft-standard" marker; plain
        // "Microsoft" without it is WSL1.
        if (Strings.CS.contains(lower, "wsl2") || Strings.CS.contains(lower, "microsoft-standard")) return 2;
        return 1;
    }

    public record LinuxDistroInfo(String linuxDistroId, String linuxDistroVersion,
                                  String linuxKernel) {}

    public static LinuxDistroInfo linuxDistroInfo() {
        if (CURRENT != LINUX) return null;
        String kernel = System.getProperty("os.version");
        String id = null;
        String version = null;
        try {
            for (String line : Files.readAllLines(Path.of("/etc/os-release"))) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1).replaceAll("^\"|\"$", "");
                if (Strings.CS.equals("ID", key)) id = value;
                else if (Strings.CS.equals("VERSION_ID", key)) version = value;
            }
        } catch (IOException _) {
            // /etc/os-release is optional.
        }
        return new LinuxDistroInfo(id, version, kernel);
    }


    public static boolean isDocker() {
        return CURRENT == LINUX && Files.isRegularFile(Path.of("/.dockerenv"));
    }

    /** Whether the current process was launched inside the bubblewrap sandbox. */
    public static boolean isBubblewrapSandbox() {
        return CURRENT == LINUX
            && EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_BUBBLEWRAP"));
    }

    /** Runtime musl fallback used by unbundled Linux builds in the original. */
    public static boolean isMuslEnvironment() {
        if (CURRENT != LINUX) return false;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String muslArch = Strings.CS.contains(arch, "aarch64") || Strings.CS.contains(arch, "arm64") ? "aarch64" : "x86_64";
        return Files.exists(Path.of("/lib/libc.musl-" + muslArch + ".so.1"));
    }

    public static List<String> detectVcs(Path directory) {
        LinkedHashSet<String> detected = new LinkedHashSet<>();
        if (System.getenv("P4PORT") != null) detected.add("perforce");
        try (var entries = Files.list(directory)) {
            Set<String> names = entries.map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());
            for (VcsMarker marker : VCS_MARKERS) if (names.contains(marker.file())) detected.add(marker.vcs());
        } catch (IOException _) {
            // Unreadable directories simply have no detectable markers.
        }
        return List.copyOf(detected);
    }

    private record VcsMarker(String file, String vcs) {}
}
