package com.claudecode.permissions;

import com.claudecode.core.state.CwdState;
import com.claudecode.core.config.ClaudePaths;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Path roots and trusted network locations used by filesystem permission checks.
 */
public record PermissionPathContext(
    Path originalWorkingDirectory,
    Map<RuleSource, Path> ruleRoots,
    Set<Path> trustedNetworkDirectories,
    boolean followSessionSettingsRoot
) {

    /** Source-compatible constructor for callers with fixed roots. */
    public PermissionPathContext(Path originalWorkingDirectory,
                                 Map<RuleSource, Path> ruleRoots,
                                 Set<Path> trustedNetworkDirectories) {
        this(originalWorkingDirectory, ruleRoots, trustedNetworkDirectories, false);
    }

    public PermissionPathContext {
        originalWorkingDirectory = normalize(originalWorkingDirectory);
        EnumMap<RuleSource, Path> roots = new EnumMap<>(RuleSource.class);
        if (ruleRoots != null) {
            ruleRoots.forEach((source, path) -> {
                if (source != null && path != null) roots.put(source, normalize(path));
            });
        }
        ruleRoots = Map.copyOf(roots);
        trustedNetworkDirectories = trustedNetworkDirectories == null
            ? Set.of()
            : trustedNetworkDirectories.stream().filter(Objects::nonNull)
                .map(PermissionPathContext::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public static PermissionPathContext defaults(Path cwd) {
        return new PermissionPathContext(cwd, Map.of(), Set.of());
    }

    /**
     * Builds the CLI context whose project/local/policy roots follow the session's
     * {@link CwdState#getOriginalCwd} across worktree transitions. Relative patterns
     * still resolve against {@code originalWorkingDirectory}, which is the live tool cwd.
     */
    public static PermissionPathContext forSession(Path liveCwd,
                                                    Map<RuleSource, Path> ruleRoots,
                                                    Set<Path> trustedNetworkDirectories) {
        return new PermissionPathContext(liveCwd, ruleRoots, trustedNetworkDirectories, true);
    }


    public Path rootFor(RuleSource source) {
        if (followSessionSettingsRoot
                && (source == RuleSource.PROJECT_SETTINGS
                    || source == RuleSource.LOCAL_SETTINGS
                    || source == RuleSource.POLICY_SETTINGS)) {
            Path original = CwdState.getOriginalCwd();
            if (original != null) return normalize(original);
        }
        if (followSessionSettingsRoot
                && (source == RuleSource.CLI_ARG
                    || source == RuleSource.COMMAND
                    || source == RuleSource.SESSION)) {

            // runtime/CLI sources when a pattern starts with '/'. Relative
            // patterns remain rooted at the live cwd in FilePermissionRuleMatcher;
            // this branch only supplies the root for absolute spellings.
            Path original = CwdState.getOriginalCwd();
            if (original != null) return normalize(original);
        }
        Path explicit = ruleRoots.get(source);
        if (explicit != null) return explicit;
        if (source == RuleSource.USER_SETTINGS) {

// getClaudeConfigHomeDir, including CLAUDE_CONFIG_DIR overrides.
            return ClaudePaths.currentClaudeHome().toAbsolutePath().normalize();
        }
        return originalWorkingDirectory;
    }

    public boolean isTrustedNetworkDirectory(Path candidate) {
        if (candidate == null) return false;
        Path normalized = normalize(candidate);
        return trustedNetworkDirectories.stream().anyMatch(root ->
            normalized.equals(root) || normalized.startsWith(root));
    }

    private static Path normalize(Path path) {
        return (path == null ? Path.of(".") : path).toAbsolutePath().normalize();
    }
}
