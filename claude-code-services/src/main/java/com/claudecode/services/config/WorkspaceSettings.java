package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.config.ClaudePaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Validated settings readers for workspace-scoped paths and worktree behavior.
 */
public final class WorkspaceSettings {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceSettings.class);

    private WorkspaceSettings() {}

    /**
     * Returns a safe configured auto-memory base directory, or {@code null} when no trusted
     * source supplies one. Project settings are intentionally never consulted because a
     * repository-controlled file must not redirect automatic memory writes.
     */
    public static String loadAutoMemoryDirectory() {
        String cwd = SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
        String configured = selectTrustedAutoMemoryDirectory(
            SettingsSources.settingsForSource(RuleSource.POLICY_SETTINGS, cwd),
            SettingsSources.settingsForSource(RuleSource.FLAG_SETTINGS, cwd),
            SettingsSources.settingsForSource(RuleSource.LOCAL_SETTINGS, cwd),
            SettingsSources.settingsForSource(RuleSource.USER_SETTINGS, cwd));
        return validateAutoMemoryDirectory(configured);
    }

    /**
     * Resolves the configured plan directory beneath {@code cwd}, falling back to the user's
     * Claude plans directory if the setting is absent or escapes the project root.
     */
    public static Path loadPlansDirectory(String cwd) {
        JsonNode effective = effectiveSettings(cwd);
        String configured = SettingsTreeReader.stringValue(effective, "plansDirectory", false);
        return resolvePlansDirectory(Path.of(cwd), configured,
            ClaudePaths.currentClaudeHome().resolve("plans"));
    }

    /** Test seam for deterministic tier precedence without changing process-global source state. */
    static Path loadPlansDirectory(String cwd, List<Path> tiers, Path fallback) {
        return resolvePlansDirectory(Path.of(cwd),
            RuntimeSettings.loadLayeredString("plansDirectory", tiers), fallback);
    }

    /** Returns the ordered union of configured worktree directories to symlink. */
    public static List<String> loadWorktreeSymlinkDirectories(String cwd) {
        JsonNode worktree = SettingsTreeReader.objectValue(effectiveSettings(cwd), "worktree");
        return distinctTextualValues(worktree == null ? null : worktree.get("symlinkDirectories"),
            false);
    }

   /** Returns the effective worktree base reference, defaulting to {@code fresh}. */
    public static String loadWorktreeBaseRef(String cwd) {
        String value = SettingsTreeReader.nestedStringValue(
            effectiveSettings(cwd), "worktree", "baseRef");
        return Strings.CS.equals( "head", value) ? "head" : "fresh";
    }

    /** Returns the ordered union of safe configured worktree sparse-checkout paths. */
    public static List<String> loadWorktreeSparsePaths(String cwd) {
        JsonNode worktree = SettingsTreeReader.objectValue(effectiveSettings(cwd), "worktree");
        return distinctTextualValues(worktree == null ? null : worktree.get("sparsePaths"), false)
            .stream()
            .filter(value -> !Strings.CS.startsWith(value, "/") && !Strings.CS.contains(value, "..") && !Strings.CS.contains(value, "\\"))
            .toList();
    }

    /** Returns the ordered union of nonblank CLAUDE.md exclusion patterns. */
    public static List<String> loadClaudeMdExcludes(String cwd) {
        return distinctTextualValues(effectiveSettings(cwd).get("claudeMdExcludes"), true);
    }

    /**
     * Selects the first explicitly supplied auto-memory setting in the special trusted-source
     * order. An empty string is deliberately an explicit value: validation rejects it without
     * falling through to a lower-priority source.
     */
    static String selectTrustedAutoMemoryDirectory(
            JsonNode policy, JsonNode flag, JsonNode local, JsonNode user) {
        for (JsonNode settings : new JsonNode[] {policy, flag, local, user}) {
            String value = SettingsTreeReader.stringValue(settings, "autoMemoryDirectory", false);
            if (value != null) return value;
        }
        return null;
    }

    /**
     * Expands a settings-file tilde prefix and returns a normalized absolute directory with one
     * trailing platform separator, or {@code null} for unsafe paths.
     */
    static String validateAutoMemoryDirectory(String raw) {
        if (StringUtils.isEmpty(raw) || raw.indexOf('\0') >= 0) return null;
        try {
            String candidate = raw;
            if (Strings.CS.startsWith(candidate, "~/") ||Strings.CS.startsWith( candidate, "~\\")) {
                String rest = candidate.substring(2);
                String normalizedRest = Path.of(rest.isEmpty() ? "." : rest).normalize().toString();
                if (normalizedRest.isEmpty() ||Strings.CS.equals( ".", normalizedRest)
                        ||Strings.CS.equals( "..", normalizedRest)) {
                    return null;
                }
                candidate = Path.of(System.getProperty("user.home", ""), rest).toString();
            }

            Path path = Path.of(candidate);
            String normalized = stripTrailingSeparators(path.normalize().toString());
            if (!path.isAbsolute()
                    || normalized.length() < 3
                    || normalized.matches("^[A-Za-z]:$")
                    ||Strings.CS.startsWith( normalized, "\\\\")
                    ||Strings.CS.startsWith( normalized, "//")
                    || normalized.indexOf('\0') >= 0) {
                return null;
            }
            return Normalizer.normalize(normalized + path.getFileSystem().getSeparator(),
                Normalizer.Form.NFC);
        } catch (InvalidPathException | SecurityException _) {
            return null;
        }
    }

    private static JsonNode effectiveSettings(String cwd) {
        return SettingsSnapshots.effective(cwd);
    }

    /** Package-private path-validation core for plans-directory characterization tests. */
    static Path resolvePlansDirectory(Path projectRoot, String configured, Path fallback) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path result = fallback.toAbsolutePath().normalize();
        if (StringUtils.isNotEmpty(configured)) {
            try {
                Path candidate = root.resolve(configured).toAbsolutePath().normalize();
                if (candidate.equals(root) || candidate.startsWith(root)) {
                    result = candidate;
                } else {
                    LOG.error("plansDirectory must be within project root: {}", configured);
                }
            } catch (InvalidPathException e) {
                LOG.error("Invalid plansDirectory path: {}", configured, e);
            }
        }
        try {
            Files.createDirectories(result);
        } catch (IOException | SecurityException e) {
            LOG.error("Failed to create plans directory {}", result, e);
        }
        return result;
    }

    private static List<String> distinctTextualValues(JsonNode array, boolean omitBlank) {
        List<String> values = new ArrayList<>();
        if (array == null || !array.isArray()) return values;
        Set<String> seen = new HashSet<>();
        for (JsonNode node : array) {
            if (!node.isTextual()) continue;
            String value = node.asText();
            if ((!omitBlank || !StringUtils.isBlank(value)) && seen.add(value)) values.add(value);
        }
        return values;
    }

    private static String stripTrailingSeparators(String path) {
        int end = path.length();
        while (end > 0 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        return path.substring(0, end);
    }
}
