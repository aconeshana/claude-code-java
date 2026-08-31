package com.claudecode.core.config;

import com.claudecode.core.process.SubprocessEnvironment;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Leaf-safe resolver for settings paths used across module boundaries.
 *
 * <ul>
 *   <li>user
 *       settings versus cowork settings selection.</li>
 *   <li>
 *
 *       administrator-managed settings location.</li>
 * </ul>
 */
public final class SettingsPathResolver {

    private SettingsPathResolver() {}

    /** Resolves the current process's user settings file. */
    public static Path userSettingsPath() {
        boolean coworkPlugins = EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get("CLAUDE_CODE_USE_COWORK_PLUGINS"));
        return userSettingsPath(ClaudePaths.currentClaudeHome(), coworkPlugins);
    }

    /** Resolves the administrator-managed settings file for the current process. */
    public static Path policySettingsPath() {
        return policySettingsPath(
            System.getProperty("os.name", ""),
            SubprocessEnvironment.get("USER_TYPE"),
            SubprocessEnvironment.get("CLAUDE_CODE_MANAGED_SETTINGS_PATH"));
    }

    /** Resolves the directory containing managed settings and its drop-ins. */
    public static Path policySettingsDirectory() {
        Path settings = policySettingsPath();
        Path parent = settings.getParent();
        return parent == null ? settings : parent;
    }

    static Path userSettingsPath(Path claudeHome, boolean coworkPlugins) {
        return claudeHome.resolve(coworkPlugins ? "cowork_settings.json" : "settings.json");
    }

    static Path policySettingsPath(String osName, String userType, String override) {
        if (Strings.CS.equals("ant", userType) && override != null && !override.isEmpty()) {
            return Path.of(override, "managed-settings.json");
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac")) {
            return Path.of("/Library/Application Support/ClaudeCode/managed-settings.json");
        }
        if (Strings.CS.contains(os, "win")) {
            return Path.of("C:\\Program Files\\ClaudeCode", "managed-settings.json");
        }
        return Path.of("/etc/claude-code/managed-settings.json");
    }
}
