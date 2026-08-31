package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.config.SettingsPathResolver;
import com.claudecode.core.state.CwdState;
import java.nio.file.Path;

/**
 * Resolves the filesystem locations for the file-backed settings sources.
 */
public final class SettingsPaths {

    private SettingsPaths() {}

/**, or the cowork variant when enabled. */
    public static Path userSettingsPath() {
        return SettingsPathResolver.userSettingsPath();
    }

/** {@code on}. */
    public static Path projectSettingsPath(String cwd) {
        return Path.of(cwd).toAbsolutePath().normalize()
            .resolve(".claude").resolve("settings.json");
    }

/** {@code on}. */
    public static Path localSettingsPath(String cwd) {
        return Path.of(cwd).toAbsolutePath().normalize()
            .resolve(".claude").resolve("settings.local.json");
    }

    /**
     * Resolves the session's project root for project/local settings.
     */
    public static Path sessionProjectRoot(String fallbackCwd) {
        Path original = CwdState.getOriginalCwd();
        if (original != null) return original.toAbsolutePath().normalize();
        if (StringUtils.isBlank(fallbackCwd)) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        return Path.of(fallbackCwd).toAbsolutePath().normalize();
    }

    /** {@code getSettingsFilePathForSource('projectSettings')} for this session. */
    public static Path sessionProjectSettingsPath(String fallbackCwd) {
        return projectSettingsPath(sessionProjectRoot(fallbackCwd).toString());
    }

    /** {@code getSettingsFilePathForSource('localSettings')} for this session. */
    public static Path sessionLocalSettingsPath(String fallbackCwd) {
        return localSettingsPath(sessionProjectRoot(fallbackCwd).toString());
    }

    /**
     * OS-specific path to the administrator-managed policy settings file.
     */
    public static Path policySettingsPath() {
        return SettingsPathResolver.policySettingsPath();
    }

    /** Directory containing administrator-managed policy drop-in fragments. */
    public static Path policySettingsDropInDirectory() {
        Path base = policySettingsPath().toAbsolutePath().normalize();
        Path parent = base.getParent();
        return (parent == null ? base : parent).resolve("managed-settings.d");
    }
}
