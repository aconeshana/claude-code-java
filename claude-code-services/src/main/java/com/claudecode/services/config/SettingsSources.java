package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-local settings-source selection and dynamic source state.
 */
public final class SettingsSources {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsSources.class);

    private SettingsSources() {}

    public static void configureAllowedSettingSources(
            boolean user, boolean project, boolean local, String cwd) {
        List<RuleSource> ordered = new ArrayList<>();
        if (user) ordered.add(RuleSource.USER_SETTINGS);
        if (project) ordered.add(RuleSource.PROJECT_SETTINGS);
        if (local) ordered.add(RuleSource.LOCAL_SETTINGS);
        configureAllowedSettingSources(ordered, cwd, true);
    }

    public static void configureAllowedSettingSources(List<RuleSource> orderedSources, String cwd) {
        configureAllowedSettingSources(orderedSources, cwd, false);
    }

    public static void configureAllowedSettingSources(
            List<RuleSource> orderedSources, String cwd, boolean flagBeforePolicy) {
        if (orderedSources == null) {
            throw new IllegalArgumentException("Setting sources must not be null");
        }
        List<RuleSource> normalized = new ArrayList<>();
        for (RuleSource source : orderedSources) {
            if (source != RuleSource.USER_SETTINGS
                    && source != RuleSource.PROJECT_SETTINGS
                    && source != RuleSource.LOCAL_SETTINGS) {
                throw new IllegalArgumentException("Only editable settings sources may be configured");
            }
            if (!normalized.contains(source)) normalized.add(source);
        }
        List<RuleSource> effective = new ArrayList<>(normalized);
        if (flagBeforePolicy) {
            effective.add(RuleSource.FLAG_SETTINGS);
            effective.add(RuleSource.POLICY_SETTINGS);
        } else {
            effective.add(RuleSource.POLICY_SETTINGS);
            effective.add(RuleSource.FLAG_SETTINGS);
        }
        SettingsProcessState.configuredSettingsRoot = SettingsPaths.sessionProjectRoot(cwd);
        SettingsProcessState.enabledOrder = List.copyOf(effective);
        McpConfigLoader.configureEnabledFileScopes(mcpFileScopes(normalized));
        invalidateForReload();
    }

    private static Set<McpServerScope> mcpFileScopes(List<RuleSource> sources) {
        Set<McpServerScope> scopes = new HashSet<>();
        for (RuleSource source : sources) {
            switch (source) {
                case USER_SETTINGS -> scopes.add(McpServerScope.USER);
                case PROJECT_SETTINGS -> scopes.add(McpServerScope.PROJECT);
                case LOCAL_SETTINGS -> scopes.add(McpServerScope.LOCAL);
                default -> { }
            }
        }
        return Set.copyOf(scopes);
    }

    public static void setFlagSettingsSource(Path path, JsonNode settings) {
        if (path == null || settings == null || !settings.isObject()) {
            throw new IllegalArgumentException("Flag settings source must be an object and a path");
        }
        synchronized (SettingsProcessState.LOCK) {
            SettingsProcessState.flagSettingsPath = path.toAbsolutePath().normalize();
            SettingsProcessState.flagSettingsFile = ((ObjectNode) settings).deepCopy();
            SettingsProcessState.flagSettingsFileLoaded = true;
            SettingsProcessState.flagSettingsFileStamp = stamp(SettingsProcessState.flagSettingsPath);
            SettingsProcessState.flagSettingsInline = JsonUtils.getMapper().createObjectNode();
        }
        invalidateForReload();
        notifyFlagSettingsChanged();
    }

    public static void setFlagSettingsPath(Path path) {
        if (path == null) throw new IllegalArgumentException("Flag settings path must not be null");
        synchronized (SettingsProcessState.LOCK) {
            SettingsProcessState.flagSettingsPath = path.toAbsolutePath().normalize();
            SettingsProcessState.flagSettingsFile = JsonUtils.getMapper().createObjectNode();
            SettingsProcessState.flagSettingsFileLoaded = false;
            SettingsProcessState.flagSettingsFileStamp = SettingsProcessState.FileStamp.MISSING;
        }
        invalidateForReload();
        notifyFlagSettingsChanged();
    }

    public static Path flagSettingsPath() {
        return SettingsProcessState.flagSettingsPath;
    }

    public static Path flagSettingsRootPath(String cwd) {
        Path path = flagSettingsPath();
        if (path != null && path.getParent() != null) return path.getParent();
        return Path.of(cwd).toAbsolutePath().normalize();
    }

    public static void applyFlagSettings(JsonNode settings) {
        if (settings == null || !settings.isObject()) {
            throw new IllegalArgumentException("Flag settings must be an object");
        }
        synchronized (SettingsProcessState.LOCK) {
            ObjectNode merged = SettingsProcessState.flagSettingsInline.deepCopy();
            settings.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull()) merged.remove(entry.getKey());
                else merged.set(entry.getKey(), entry.getValue().deepCopy());
            });
            SettingsProcessState.flagSettingsInline = merged;
        }
        invalidateForReload();
        notifyFlagSettingsChanged();
    }

    public static void clearFlagSettings() {
        synchronized (SettingsProcessState.LOCK) {
            SettingsProcessState.flagSettingsFile = JsonUtils.getMapper().createObjectNode();
            SettingsProcessState.flagSettingsFileLoaded = true;
            SettingsProcessState.flagSettingsFileStamp = SettingsProcessState.FileStamp.MISSING;
            SettingsProcessState.flagSettingsInline = JsonUtils.getMapper().createObjectNode();
            SettingsProcessState.flagSettingsPath = null;
        }
        invalidateForReload();
        notifyFlagSettingsChanged();
    }

    public static AutoCloseable subscribeFlagSettingsChanged(Runnable listener) {
        if (listener == null) throw new IllegalArgumentException("listener must not be null");
        SettingsProcessState.FLAG_SETTINGS_LISTENERS.add(listener);
        return () -> SettingsProcessState.FLAG_SETTINGS_LISTENERS.remove(listener);
    }

    public static ObjectNode flagSettingsSnapshot() {
        ObjectNode file = SettingsTreeReader.accepted(loadFlagSettingsFile());
        ObjectNode inline = SettingsTreeReader.acceptedInline(inlineSnapshot());
        if (file == null) file = JsonUtils.getMapper().createObjectNode();
        if (inline == null) return file;
        return (ObjectNode) SettingsMerger.merge(file, inline);
    }

    public static void setPluginSettingsBase(JsonNode settings) {
        ObjectNode filtered = JsonUtils.getMapper().createObjectNode();
        if (settings != null && settings.isObject()) {
            JsonNode agent = settings.get("agent");
            if (agent != null && agent.isTextual()) filtered.set("agent", agent.deepCopy());
        }
        synchronized (SettingsProcessState.LOCK) {
            SettingsProcessState.pluginSettingsBase = filtered;
        }
        invalidateForReload();
    }

    public static void clearPluginSettingsBase() {
        synchronized (SettingsProcessState.LOCK) {
            SettingsProcessState.pluginSettingsBase = JsonUtils.getMapper().createObjectNode();
        }
        invalidateForReload();
    }

    public static ObjectNode pluginSettingsBaseSnapshot() {
        return SettingsProcessState.pluginSettingsBase.deepCopy();
    }

    public static void setSessionAdditionalDirectories(List<String> directories) {
        if (directories == null || directories.isEmpty()) {
            SettingsProcessState.sessionAdditionalDirectories = List.of();
            return;
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String directory : directories) {
            if (StringUtils.isBlank(directory)) continue;
            String value = Path.of(directory).toAbsolutePath().normalize().toString();
            if (seen.add(value)) normalized.add(value);
        }
        SettingsProcessState.sessionAdditionalDirectories = List.copyOf(normalized);
    }

    public static void clearSessionAdditionalDirectories() {
        SettingsProcessState.sessionAdditionalDirectories = List.of();
    }

    public static boolean isEnabled(RuleSource source) {
        return SettingsProcessState.enabledOrder.contains(source);
    }

    public static ObjectNode settingsForSource(RuleSource source, String cwd) {
        if (source == null) return JsonUtils.getMapper().createObjectNode();
        return switch (source) {
            case FLAG_SETTINGS -> flagSettingsSnapshot();
            case POLICY_SETTINGS -> SettingsSnapshots.policySnapshot();
            default -> {
                ObjectNode settings = SettingsTreeReader.readAccepted(editablePath(source, cwd), false);
                yield settings == null ? JsonUtils.getMapper().createObjectNode() : settings;
            }
        };
    }

    static boolean isReadPathDisabled(Path path) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();

        // three editable targets here too: worktree enter/exit can move that root, and a new
// root may alias ~/on. A cached deny path would then incorrectly
        // suppress an enabled source at the aliased location.
        String fallbackCwd = System.getProperty("user.dir");
        Path settingsRoot = settingsSelectionRoot(fallbackCwd);
        Path userPath = SettingsPaths.userSettingsPath().toAbsolutePath().normalize();
        Path projectPath = SettingsPaths.projectSettingsPath(settingsRoot.toString())
            .toAbsolutePath().normalize();
        Path localPath = SettingsPaths.localSettingsPath(settingsRoot.toString())
            .toAbsolutePath().normalize();
        Set<Path> enabledPaths = new HashSet<>();
        if (isEnabled(RuleSource.USER_SETTINGS)) enabledPaths.add(userPath);
        if (isEnabled(RuleSource.PROJECT_SETTINGS)) enabledPaths.add(projectPath);
        if (isEnabled(RuleSource.LOCAL_SETTINGS)) enabledPaths.add(localPath);
        if (!isEnabled(RuleSource.USER_SETTINGS)
                && normalized.equals(userPath) && !enabledPaths.contains(normalized)) {
            return true;
        }
        if (!isEnabled(RuleSource.PROJECT_SETTINGS)
                && normalized.equals(projectPath) && !enabledPaths.contains(normalized)) {
            return true;
        }
        return !isEnabled(RuleSource.LOCAL_SETTINGS)
            && normalized.equals(localPath) && !enabledPaths.contains(normalized);
    }

    /**
     * Resolves the root used for source-selection checks. Worktree transitions update the
     * process original cwd and must take effect immediately; before startup wiring, retain the
     * root supplied to the last explicit source configuration instead of silently falling back
     * to the JVM launch directory.
     */
    private static Path settingsSelectionRoot(String fallbackCwd) {
        Path original = CwdState.getOriginalCwd();
        if (original != null) return original.toAbsolutePath().normalize();
        Path configured = SettingsProcessState.configuredSettingsRoot;
        if (configured != null) return configured.toAbsolutePath().normalize();
        return SettingsPaths.sessionProjectRoot(fallbackCwd);
    }

    static List<RuleSource> enabledOrder() {
        return SettingsProcessState.enabledOrder;
    }

    static Path editablePath(RuleSource source, String cwd) {
        return switch (source) {
            case USER_SETTINGS -> SettingsPaths.userSettingsPath();
            case PROJECT_SETTINGS -> SettingsPaths.sessionProjectSettingsPath(cwd);
            case LOCAL_SETTINGS -> SettingsPaths.sessionLocalSettingsPath(cwd);
            default -> throw new IllegalArgumentException("Not an editable settings source: " + source);
        };
    }

    static List<String> sessionAdditionalDirectories() {
        return SettingsProcessState.sessionAdditionalDirectories;
    }

    static ObjectNode inlineSnapshot() {
        return SettingsProcessState.flagSettingsInline.deepCopy();
    }

    static void refreshFlagSettingsFileOnNextRead() {
        if (SettingsProcessState.flagSettingsPath != null) {
            synchronized (SettingsProcessState.LOCK) {
                SettingsProcessState.flagSettingsFileLoaded = false;
                SettingsProcessState.flagSettingsFileStamp = SettingsProcessState.FileStamp.MISSING;
            }
        }
    }

    private static ObjectNode loadFlagSettingsFile() {
        synchronized (SettingsProcessState.LOCK) {
            Path path = SettingsProcessState.flagSettingsPath;
            if (path != null && (!SettingsProcessState.flagSettingsFileLoaded
                    || !stamp(path).equals(SettingsProcessState.flagSettingsFileStamp))) {
                try {
                    JsonNode parsed = SettingsTreeReader.readCached(path, false);
                    SettingsProcessState.flagSettingsFile = parsed != null && parsed.isObject()
                        ? ((ObjectNode) parsed).deepCopy()
                        : JsonUtils.getMapper().createObjectNode();
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Failed to read flag settings from {}: {}", path, e.getMessage());
                    SettingsProcessState.flagSettingsFile = JsonUtils.getMapper().createObjectNode();
                }
                SettingsProcessState.flagSettingsFileStamp = stamp(path);
                SettingsProcessState.flagSettingsFileLoaded = true;
            }
            return SettingsProcessState.flagSettingsFile.deepCopy();
        }
    }

    private static SettingsProcessState.FileStamp stamp(Path path) {
        try {
            return new SettingsProcessState.FileStamp(Files.getLastModifiedTime(path), Files.size(path));
        } catch (IOException | SecurityException _) {
            return SettingsProcessState.FileStamp.MISSING;
        }
    }

    private static void invalidateForReload() {
        SettingsSnapshots.invalidateForReload();
    }

    private static void notifyFlagSettingsChanged() {
        for (Runnable listener : SettingsProcessState.FLAG_SETTINGS_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.debug("Flag settings listener failed: {}", e.getMessage());
            }
        }
    }
}
