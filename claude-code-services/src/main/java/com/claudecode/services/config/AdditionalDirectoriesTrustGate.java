package com.claudecode.services.config;

import com.claudecode.core.git.GitUtils;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import com.claudecode.permissions.RuleSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.Strings;

/**
 * Decides which project-scoped settings tiers may contribute
 * {@code permissions.additionalDirectories} before the workspace is trusted.
 *
 * <ul>
 *   <li>2.1.236 bundle {@code fFr} / {@code oLn}: an untrusted workspace drops
 *       {@code .claude/settings.json} entries (unless it is the user settings file) and
 *       {@code .claude/settings.local.json} entries when that file is, or may be, repository
 *       content.</li>
 *   <li>2.1.236 bundle {@code A_e} / {@code vTb} / {@code TTb}: the local-settings gate, including
 *       the {@code git ls-files --error-unmatch} probe that treats a tracked, symlinked or
 *       nested-repo {@code settings.local.json} as untrusted content.</li>
 * </ul>
 *
 * The user tier, flag settings and managed policy are never gated.
 */
final class AdditionalDirectoriesTrustGate {

    /** Which project-scoped tiers must be ignored for the current workspace. */
    record Gate(boolean project, boolean local) {
        static final Gate OPEN = new Gate(false, false);

        boolean blocks(RuleSource source) {
            return switch (source) {
                case PROJECT_SETTINGS -> project;
                case LOCAL_SETTINGS -> local;
                default -> false;
            };
        }
    }

    enum Tracking { TRACKED, UNTRACKED, INDETERMINATE }

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(2);
    private static final String LOCAL_SETTINGS_PATHSPEC = ":(icase).claude/settings.local.json";
    private static final Map<Path, Tracking> TRACKING_CACHE = new ConcurrentHashMap<>();

    private AdditionalDirectoriesTrustGate() {}

    static Gate evaluate(String cwd) {
        if (TrustConfigStore.isProjectTrustRecorded()) {
            return Gate.OPEN;
        }
        boolean project = !SettingsSources.editablePath(RuleSource.PROJECT_SETTINGS, cwd)
            .toAbsolutePath().normalize()
            .equals(SettingsSources.editablePath(RuleSource.USER_SETTINGS, cwd)
                .toAbsolutePath().normalize());
        return new Gate(project, gateLocal(Path.of(cwd).toAbsolutePath().normalize()));
    }

    /** Test isolation only. */
    static void clearTrackingCacheForTesting() {
        TRACKING_CACHE.clear();
    }

    private static boolean gateLocal(Path dir) {
        if (!TrustConfigStore.isTrustAccepted(dir)) {
            return !(isHomeOrUserConfigRoot(dir) && gitRootIsSelf(dir));
        }
        return TRACKING_CACHE.computeIfAbsent(dir, AdditionalDirectoriesTrustGate::probeTracking)
            == Tracking.TRACKED;
    }

    private static boolean isHomeOrUserConfigRoot(Path dir) {
        return sameFile(dir, userHome()) || isUserConfigDirOf(dir);
    }

    private static boolean isUserConfigDirOf(Path dir) {
        Path userConfigDir = SettingsPaths.userSettingsPath().toAbsolutePath().normalize().getParent();
        return userConfigDir != null && userConfigDir.equals(dir.resolve(".claude").normalize());
    }

    private static boolean gitRootIsSelf(Path dir) {
        Path root = GitUtils.findCanonicalGitRoot(dir);
        return root == null || sameFile(root, dir);
    }

    private static Tracking probeTracking(Path dir) {
        List<Path> candidates = new ArrayList<>(List.of(dir));
        Path root = GitUtils.findCanonicalGitRoot(dir);
        if (root != null && !sameFile(root, dir)) {
            candidates.add(root);
        }
        boolean indeterminate = false;
        for (Path candidate : candidates) {
            Tracking tracking = probeDirectory(candidate);
            if (tracking == Tracking.TRACKED) {
                return Tracking.TRACKED;
            }
            indeterminate |= tracking == Tracking.INDETERMINATE;
        }
        return indeterminate ? Tracking.INDETERMINATE : Tracking.UNTRACKED;
    }

    private static Tracking probeDirectory(Path dir) {
        if (isHomeOrUserConfigRoot(dir)) {
            return Tracking.UNTRACKED;
        }
        Path claudeDir = dir.resolve(".claude");
        if (existsAsLinkOrUnreadable(claudeDir, true)
                || existsAsLinkOrUnreadable(claudeDir.resolve("settings.local.json"), true)
                || existsAsLinkOrUnreadable(claudeDir.resolve(".git"), false)) {
            return Tracking.TRACKED;
        }
        return gitTracking(dir);
    }

    /** True when {@code path} is a symlink (if {@code linkOnly}), exists at all, or cannot be inspected. */
    private static boolean existsAsLinkOrUnreadable(Path path, boolean linkOnly) {
        try {
            BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return !linkOnly || attrs.isSymbolicLink();
        } catch (NoSuchFileException _) {
            return false;
        } catch (IOException _) {
            return true;
        }
    }

    private static Tracking gitTracking(Path dir) {
        var git = ExecutableFinder.find("git");
        if (git.isEmpty()) {
            return Tracking.UNTRACKED;
        }
        ProcessResult result = ProcessRunner.run(
            List.of(git.get().toString(), "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=",
                "-C", dir.toString(), "ls-files", "--error-unmatch", "--", LOCAL_SETTINGS_PATHSPEC),
            dir, GIT_TIMEOUT,
            Map.of("GIT_LITERAL_PATHSPECS", "", "LC_ALL", "C",
                "GIT_TRACE2", "", "GIT_TRACE2_PERF", "", "GIT_TRACE2_EVENT", ""));
        if (result.timedOut()) {
            return Tracking.TRACKED;
        }
        return switch (result.exitCode()) {
            case 0 -> Tracking.TRACKED;
            case 1 -> Tracking.UNTRACKED;
            case 128 -> Strings.CS.startsWith(result.stderr(), "fatal: not a git repository")
                ? Tracking.INDETERMINATE : Tracking.TRACKED;
            case -1 -> Tracking.UNTRACKED;
            default -> Tracking.TRACKED;
        };
    }

    private static Path userHome() {
        return Path.of(System.getProperty("user.home"));
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException _) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
