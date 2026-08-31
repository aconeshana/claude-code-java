package com.claudecode.core.process;

import com.claudecode.core.platform.Platform;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cross-platform executable and Git-Bash resolution.
 *
 * <ul>
 *   <li>synchronous PATH lookup.</li>
 *   <li>resolved command with pass-through args.</li>
 *   <li>secure Windows executable lookup and
 *       {@code findGitBashPath}.</li>
 *   <li>callers can cache {@link #find} results.</li>
 * </ul>
 */
public final class ExecutableFinder {
    private ExecutableFinder() {}

    public record Command(String cmd, List<String> args) {}

    public static Optional<Path> find(String command) {
        return find(command, SubprocessEnvironment.snapshot(),
            Path.of(System.getProperty("user.dir")), Platform.CURRENT);
    }

    static Optional<Path> find(String command, Map<String, String> env, Path cwd, Platform platform) {
        if (StringUtils.isBlank(command)) return Optional.empty();
        String trimmed = command.trim();
        if (Strings.CS.contains(trimmed, "/") || Strings.CS.contains(trimmed, "\\")) {
            Path explicit = Path.of(trimmed).toAbsolutePath().normalize();
            return executable(explicit, platform) ? Optional.of(explicit) : Optional.empty();
        }
        if (platform == Platform.WIN32 && Strings.CI.equals("git", trimmed)) {
            for (String location : List.of("C:\\Program Files\\Git\\cmd\\git.exe",
                    "C:\\Program Files (x86)\\Git\\cmd\\git.exe")) {
                Path candidate = Path.of(location);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
        }
        String pathValue = env.get("PATH");
        if (StringUtils.isBlank(pathValue)) return Optional.empty();
        List<String> extensions = platform == Platform.WIN32
            ? windowsExtensions(trimmed, env.get("PATHEXT")) : List.of("");
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (StringUtils.isBlank(directory)) continue;
            for (String extension : extensions) {
                Path candidate = Path.of(directory, trimmed + extension).toAbsolutePath().normalize();
                if (platform == Platform.WIN32 && candidate.startsWith(normalizedCwd)) continue;
                if (executable(candidate, platform)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Command resolve(String executable, List<String> args) {
        return new Command(find(executable).map(Path::toString).orElse(executable), List.copyOf(args));
    }

    public static Path findGitBash() {
        String override = SubprocessEnvironment.get("CLAUDE_CODE_GIT_BASH_PATH");
        if (StringUtils.isNotBlank(override)) {
            Path path = Path.of(override);
            if (Files.isRegularFile(path)) return path;
            throw new IllegalStateException("Claude Code was unable to find CLAUDE_CODE_GIT_BASH_PATH path \""
                + override + "\"");
        }
        Optional<Path> git = find("git");
        if (git.isPresent()) {
            Path parent = git.get().getParent();
            if (parent != null && parent.getParent() != null) {
                Path bash = parent.getParent().resolve("bin").resolve("bash.exe").normalize();
                if (Files.isRegularFile(bash)) return bash;
            }
        }
        throw new IllegalStateException("Claude Code on Windows requires git-bash. Install Git for Windows or set CLAUDE_CODE_GIT_BASH_PATH to bash.exe");
    }

    public static String bashExecutable() {
        return Platform.IS_WINDOWS ? findGitBash().toString()
            : find("bash").map(Path::toString).orElse("bash");
    }

    private static boolean executable(Path path, Platform platform) {
        return Files.isRegularFile(path) && (platform == Platform.WIN32 || Files.isExecutable(path));
    }

    private static List<String> windowsExtensions(String command, String pathExt) {
        if (Strings.CS.contains(command, ".")) return List.of("");
        String value = StringUtils.isBlank(pathExt) ? ".COM;.EXE;.BAT;.CMD" : pathExt;
        List<String> result = new ArrayList<>();
        for (String ext : value.split(";")) {
            if (!StringUtils.isBlank(ext)) result.add(Strings.CS.startsWith(ext, ".") ? ext.toLowerCase(Locale.ROOT)
                : "." + ext.toLowerCase(Locale.ROOT));
        }
        return result;
    }
}
