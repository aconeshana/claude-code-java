package com.claudecode.cli;

import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.PermissionSettings;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.config.SettingsFileStore;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.services.git.GitignoreHelper;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Composition-root adapter joining command settings use cases to the existing layered settings
 * stores.
 */
final class CliSettingsManagementAdapter implements SettingsManagementPort {

    private final Path globalConfigPath;
    private final Path userSettingsPath;
    private final Configuration configuration = new ConfigurationAdapter();
    private final Preferences preferences = new PreferencesAdapter();
    private final Sandbox sandbox = new SandboxAdapter();

    CliSettingsManagementAdapter() {
        this(ClaudePaths.GLOBAL_JSON, SettingsPaths.userSettingsPath());
    }

    CliSettingsManagementAdapter(Path globalConfigPath, Path userSettingsPath) {
        this.globalConfigPath = globalConfigPath;
        this.userSettingsPath = userSettingsPath;
    }

    @Override public Configuration configuration() { return configuration; }
    @Override public Preferences preferences() { return preferences; }
    @Override public Sandbox sandbox() { return sandbox; }

    private final class ConfigurationAdapter implements Configuration {
        @Override
        public Map<String, String> values(String workingDirectory) {
            return ConfigCommand.currentValues(
                () -> GlobalConfigStore.snapshot(globalConfigPath),
                () -> SettingsSnapshots.effective(effectiveCwd(workingDirectory)),
                () -> SettingsSources.settingsForSource(
                    RuleSource.USER_SETTINGS, effectiveCwd(workingDirectory)));
        }

        @Override
        public void save(String workingDirectory, String key, String value) {
            switch (key) {
                case "thinkingEnabled" -> saveUserScalar(
                    "alwaysThinkingEnabled", Boolean.parseBoolean(value));
                case "spinnerTipsEnabled", "switchModelsOnFlag", "prefersReducedMotion",
                     "awaySummaryEnabled", "enableWorkflows", "workflowKeywordTriggerEnabled",
                     "useAutoModeDuringPlan", "respectGitignore" ->
                    saveUserScalar(key, Boolean.parseBoolean(value));
                case "claudeHudEnabled" -> {
                    saveUserScalar(key, Boolean.parseBoolean(value));
                    GlobalConfigStore.set(globalConfigPath, key, null);
                }
                case "subagentMaxDepth" -> saveUserScalar(key, Integer.parseInt(value));
                case "defaultPermissionMode" -> mutate(userSettingsPath, root ->
                    object(root, "permissions").put("defaultMode", value));
                case "worktreeBaseRef" -> mutate(userSettingsPath, root ->
                    object(root, "worktree").put("baseRef", value));
                case "outputStyle", "language", "autoUpdatesChannel" ->
                    saveUserScalar(key, value);
                default -> GlobalConfigStore.set(globalConfigPath, key, scalar(value));
            }
        }

        private void saveUserScalar(String key, Object value) {
            mutate(userSettingsPath, root -> putScalar(root, key, value));
        }

        @Override public boolean syntaxHighlightingDisabled() {
            return RuntimeSettings.loadSyntaxHighlightingDisabled();
        }

        @Override public void saveSyntaxHighlightingDisabled(boolean disabled) {
            RuntimeSettings.saveSyntaxHighlightingDisabled(disabled);
        }
    }

    private final class PreferencesAdapter implements Preferences {
        @Override public String theme() {
            return GlobalConfigStore.getString(globalConfigPath, "theme", "dark");
        }

        @Override public void saveTheme(String theme) {
            GlobalConfigStore.set(globalConfigPath, "theme", theme);
        }

        @Override public String effortLevel() {
            if (userSettingsPath.toAbsolutePath().normalize().equals(
                    SettingsPaths.userSettingsPath().toAbsolutePath().normalize())) {
                return RuntimeSettings.loadEffortLevel();
            }
            JsonNode root = read(userSettingsPath);
            JsonNode value = root == null ? null : root.get("effortLevel");
            return value != null && value.isTextual() ? value.asText() : null;
        }

        @Override public void saveEffortLevel(String effort) {
            mutate(userSettingsPath, root -> {
                if (effort == null) root.remove("effortLevel");
                else root.put("effortLevel", effort);
            });
        }

        @Override public Optional<String> advisorModel() {
            if (userSettingsPath.toAbsolutePath().normalize().equals(
                    SettingsPaths.userSettingsPath().toAbsolutePath().normalize())) {
                JsonNode value = SettingsSnapshots.withSources(
                    System.getProperty("user.dir")).path("effective").get("advisorModel");
                return value != null && value.isTextual()
                    ? Optional.of(value.asText()) : Optional.empty();
            }
            JsonNode root = read(userSettingsPath);
            JsonNode value = root == null ? null : root.get("advisorModel");
            return value != null && value.isTextual()
                ? Optional.of(value.asText()) : Optional.empty();
        }

        @Override public void saveAdvisorModel(String model) {
            mutate(userSettingsPath, root -> {
                if (model == null) root.remove("advisorModel");
                else root.put("advisorModel", model);
            });
        }

        @Override public boolean copyFullResponse() {
            return GlobalConfigStore.getBoolean(globalConfigPath, "copyFullResponse", false);
        }

        @Override public void saveCopyFullResponse(boolean enabled) {
            GlobalConfigStore.set(globalConfigPath, "copyFullResponse", enabled);
        }

        @Override public Optional<PokemonProfile> pokemon() {
            return Optional.ofNullable(PokemonProfile.fromJson(
                GlobalConfigStore.getNode(globalConfigPath, "welcomePokemon")));
        }

        @Override public void savePokemon(PokemonProfile profile) {
            GlobalConfigStore.set(globalConfigPath, "welcomePokemon", profile.toJson());
        }

        @Override public void incrementBtwUseCount() {
            int current = GlobalConfigStore.getInt(globalConfigPath, "btwUseCount", 0);
            GlobalConfigStore.set(globalConfigPath, "btwUseCount", current + 1);
        }

        @Override public boolean hasStoredApiKey() {
            String primary = GlobalConfigStore.getString(
                globalConfigPath, "primaryApiKey", null);
            String legacy = GlobalConfigStore.getString(globalConfigPath, "apiKey", null);
            return StringUtils.isNotBlank(primary) || StringUtils.isNotBlank(legacy);
        }

        @Override public List<String> settingSourceLabels(String workingDirectory) {
            List<String> sources = new ArrayList<>();
            JsonNode sourceArray = SettingsSnapshots.withSources(
                effectiveCwd(workingDirectory)).path("sources");
            if (!sourceArray.isArray()) return List.of();
            for (JsonNode source : sourceArray) {
                String label = switch (source.path("source").asText()) {
                    case "userSettings" -> "User settings";
                    case "projectSettings" -> "Project settings";
                    case "localSettings" -> "Local settings";
                    case "flagSettings" -> "CLI flag settings";
                    case "policySettings" -> "Policy settings";
                    default -> null;
                };
                if (label != null) sources.add(label);
            }
            return List.copyOf(sources);
        }
    }

    private final class SandboxAdapter implements Sandbox {
        @Override public SandboxConfig config() { return SandboxSettings.loadSandboxConfig(); }
        @Override public boolean lockedByPolicy() {
            return SandboxSettings.areSandboxSettingsLockedByPolicy();
        }

        @Override
        public void saveSettings(String workingDirectory, Boolean enabled,
                                 Boolean autoAllowBashIfSandboxed,
                                 Boolean allowUnsandboxedCommands) {
            mutateLocal(workingDirectory, root -> {
                ObjectNode value = object(root, "sandbox");
                if (enabled != null) value.put("enabled", enabled);
                if (autoAllowBashIfSandboxed != null) {
                    value.put("autoAllowBashIfSandboxed", autoAllowBashIfSandboxed);
                }
                if (allowUnsandboxedCommands != null) {
                    value.put("allowUnsandboxedCommands", allowUnsandboxedCommands);
                }
            });
        }

        @Override
        public String addExcludedCommand(String workingDirectory, String pattern) {
            String cwd = effectiveCwd(workingDirectory);
            mutateLocal(cwd, root -> {
                ObjectNode value = object(root, "sandbox");
                ArrayNode excluded = value.hasNonNull("excludedCommands")
                    && value.get("excludedCommands").isArray()
                        ? (ArrayNode) value.get("excludedCommands")
                        : value.putArray("excludedCommands");
                boolean exists = false;
                for (JsonNode item : excluded) {
                    if (pattern.equals(item.asText())) { exists = true; break; }
                }
                if (!exists) excluded.add(pattern);
            });
            Path local = SettingsPaths.sessionLocalSettingsPath(cwd);
            try {
                return Path.of(cwd).toAbsolutePath().normalize()
                    .relativize(local.toAbsolutePath().normalize()).toString();
            } catch (RuntimeException _) {
                return ".claude/settings.local.json";
            }
        }

        @Override public void saveAdditionalDirectory(
                String workingDirectory, String absolutePath) {
            PermissionSettings.saveAdditionalDirectoryToLocalSettings(
                workingDirectory, absolutePath);
        }
    }

    private void mutateLocal(String workingDirectory,
                             Consumer<ObjectNode> edit) {
        String cwd = effectiveCwd(workingDirectory);
        Path path = SettingsPaths.sessionLocalSettingsPath(cwd);
        mutate(path, edit);
        GitignoreHelper.addFileGlobRuleToGitignore(
            ".claude/settings.local.json", SettingsPaths.sessionProjectRoot(cwd).toString());
    }

    private static void mutate(Path path, Consumer<ObjectNode> edit) {
        try {
            SettingsFileStore.mutate(path, edit);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write settings: " + path, e);
        }
    }

    private static JsonNode read(Path path) {
        try {
            if (!Files.isReadable(path)) return null;
            return JsonUtils.getMapper().readTree(path.toFile());
        } catch (IOException _) {
            return null;
        }
    }

    private static Object scalar(String value) {
        if (Strings.CS.equals("true", value) || Strings.CS.equals("false", value)) {
            return Boolean.valueOf(value);
        }
        return value;
    }

    private static void putScalar(ObjectNode root, String key, Object value) {
        switch (value) {
            case Boolean bool -> root.put(key, bool);
            case Integer integer -> root.put(key, integer);
            case Long longValue -> root.put(key, longValue);
            case Double doubleValue -> root.put(key, doubleValue);
            case null -> root.remove(key);
            default -> root.put(key, String.valueOf(value));
        }
    }

    private static ObjectNode object(ObjectNode root, String key) {
        return root.hasNonNull(key) && root.get(key).isObject()
            ? (ObjectNode) root.get(key) : root.putObject(key);
    }

    private static String effectiveCwd(String workingDirectory) {
        return StringUtils.isBlank(workingDirectory)
            ? System.getProperty("user.dir") : workingDirectory;
    }
}
