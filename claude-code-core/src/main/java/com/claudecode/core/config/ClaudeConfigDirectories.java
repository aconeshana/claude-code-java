package com.claudecode.core.config;

import com.claudecode.core.git.GitUtils;
import com.claudecode.core.state.CwdState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers project-level {@code .claude/<subdir>} directories.
 */
public final class ClaudeConfigDirectories {

    private ClaudeConfigDirectories() {}

    public static List<Path> projectDirs(Path cwd, String subdir) {
        return projectDirs(cwd, subdir, Path.of(System.getProperty("user.home")));
    }

    static List<Path> projectDirs(Path cwd, String subdir, Path home) {
        Path current = cwd.toAbsolutePath().normalize();
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path boundary = stopBoundary(current);
        List<Path> dirs = new ArrayList<>();

        while (true) {
            if (samePath(current, normalizedHome)) break;
            Path candidate = current.resolve(".claude").resolve(subdir);
            if (Files.isDirectory(candidate)) dirs.add(candidate);
            if (boundary != null && samePath(current, boundary)) break;
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) break;
            current = parent;
        }

        Path nearestRoot = nearestGitRoot(cwd);
        if (nearestRoot != null && Files.isRegularFile(nearestRoot.resolve(".git"))) {
            Path worktreeDir = nearestRoot.resolve(".claude").resolve(subdir);
            boolean worktreeHasDir = dirs.stream().anyMatch(worktreeDir::equals);
            if (!worktreeHasDir) {
                Path canonical = GitUtils.findCanonicalGitRoot(cwd);
                if (canonical != null && !samePath(canonical, nearestRoot)) {
                    Path fallback = canonical.resolve(".claude").resolve(subdir);
                    if (Files.isDirectory(fallback) && !dirs.contains(fallback)) dirs.add(fallback);
                }
            }
        }
        return List.copyOf(dirs);
    }

    private static Path stopBoundary(Path cwd) {
        Path cwdRoot = nearestGitRoot(cwd);
        Path sessionCwd = CwdState.getOriginalCwd();
        Path sessionRoot = sessionCwd == null ? null : nearestGitRoot(sessionCwd);
        if (cwdRoot == null || sessionRoot == null || samePath(cwdRoot, sessionRoot)) {
            return cwdRoot;
        }
        Path canonical = GitUtils.findCanonicalGitRoot(cwd);
        if (canonical != null && samePath(canonical, sessionRoot)) {
            return cwdRoot;
        }
        if (cwdRoot.startsWith(sessionRoot)) {
            return sessionRoot;
        }
        return cwdRoot;
    }

    private static Path nearestGitRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) return current;
            current = current.getParent();
        }
        return null;
    }

    private static boolean samePath(Path left, Path right) {
        return left.toAbsolutePath().normalize().toString()
            .equalsIgnoreCase(right.toAbsolutePath().normalize().toString());
    }
}
