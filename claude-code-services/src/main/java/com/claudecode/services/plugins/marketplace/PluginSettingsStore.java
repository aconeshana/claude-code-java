package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.claudecode.services.config.SettingsFileStore;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.git.GitignoreHelper;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;






public final class PluginSettingsStore {

    /** Provenance used to make Errors-tab remediation safe. */
    public record MarketplaceSourceInfo(boolean user, boolean project, boolean local, boolean policy) {
        public boolean hasEditableSource() { return user || project || local; }
    }

    private static final Logger LOG = LoggerFactory.getLogger(PluginSettingsStore.class);

    private final Path userSettingsPath;
    private final Path projectSettingsPath;
    private final Path localSettingsPath;
    private final Path policySettingsPath;
    /** Non-null only for production wiring, where the canonical source filter applies. */
    private final String canonicalCwd;

    public PluginSettingsStore(Path userSettingsPath, Path projectSettingsPath,
                               Path localSettingsPath, Path policySettingsPath) {
        this(userSettingsPath, projectSettingsPath, localSettingsPath, policySettingsPath, null);
    }

    private PluginSettingsStore(Path userSettingsPath, Path projectSettingsPath,
                                Path localSettingsPath, Path policySettingsPath,
                                String canonicalCwd) {
        this.userSettingsPath = userSettingsPath;
        this.projectSettingsPath = projectSettingsPath;
        this.localSettingsPath = localSettingsPath;
        this.policySettingsPath = policySettingsPath;
        this.canonicalCwd = canonicalCwd;
    }

    /** Production wiring against the canonical {@link SettingsPaths} paths. */
    public static PluginSettingsStore standard(String cwd) {
        return new PluginSettingsStore(
            SettingsPaths.userSettingsPath(),
            SettingsPaths.sessionProjectSettingsPath(cwd),
            SettingsPaths.sessionLocalSettingsPath(cwd),
            SettingsPaths.policySettingsPath(), cwd);
    }

    // ── enabledPlugins ────────────────────────────────────────────────────────


    public Map<String, Boolean> enabledPlugins() {
        Map<String, Boolean> merged = new LinkedHashMap<>();
        if (canonicalCwd != null) {
            // The runtime loader is a production settings consumer: use the
            // same effective snapshot as the rest of the process so
            // --setting-sources and --settings/SDK overlays are respected.
            mergeEnabledPlugins(merged,
                SettingsSnapshots.withSources(canonicalCwd)
                    .path("effective").path("enabledPlugins"));
            return merged;
        }
        for (Path tier : editableTiers()) {
            mergeEnabledPlugins(merged, readKey(tier, "enabledPlugins"));
        }
        // The CLI/SDK flag source sits above local settings and below policy.
        mergeEnabledPlugins(merged, SettingsSources.flagSettingsSnapshot().get("enabledPlugins"));
        // Policy remains the highest-priority source.
        mergeEnabledPlugins(merged, readKey(policySettingsPath, "enabledPlugins"));
        return merged;
    }

    private static void mergeEnabledPlugins(Map<String, Boolean> target, JsonNode node) {
        if (node == null || !node.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isBoolean()) target.put(field.getKey(), field.getValue().asBoolean());
        }
    }

    /** Sets {@code enabledPlugins[pluginId]} in the settings tier for {@code scope}. */
    public void setEnabledPlugin(String pluginId, boolean enabled, PluginScope scope) {
        mutate(pathForScope(scope), root -> {
            ObjectNode plugins = objectChild(root, "enabledPlugins");
            plugins.put(pluginId, enabled);
            root.set("enabledPlugins", plugins);
        });
    }

    /** Removes {@code enabledPlugins[pluginId]} from every editable tier. */
    public void removeEnabledPlugin(String pluginId) {
        for (Path tier : editableTiers()) {
            mutateIfExists(tier, root -> {
                JsonNode plugins = root.get("enabledPlugins");
                if (plugins instanceof ObjectNode obj && obj.has(pluginId)) {
                    obj.remove(pluginId);
                    return true;
                }
                return false;
            });
        }
    }

    /** Removes one scope's {@code enabledPlugins[pluginId]} entry. */
    public void removeEnabledPlugin(String pluginId, PluginScope scope) {
        mutateIfExists(pathForScope(scope), root -> {
            JsonNode plugins = root.get("enabledPlugins");
            if (plugins instanceof ObjectNode obj && obj.has(pluginId)) {
                obj.remove(pluginId);
                return true;
            }
            return false;
        });
    }

    /** Exact-tier lookup; unlike {@link #enabledPlugins()}, this does not merge scopes. */
    public Boolean enabledPluginAtScope(String pluginId, PluginScope scope) {
        JsonNode plugins = readKey(pathForScope(scope), "enabledPlugins");
        JsonNode value = plugins == null ? null : plugins.get(pluginId);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    /**
     * Drops the marketplace's {@code extraKnownMarketplaces} entry and every
     * {@code enabledPlugins} key ending {@code @marketplaceName} from each
     * editable tier.
     */
    public void removeMarketplaceReferences(String marketplaceName) {
        String suffix = "@" + marketplaceName;
        for (Path tier : editableTiers()) {
            mutateIfExists(tier, root -> {
                boolean changed = false;
                JsonNode extra = root.get("extraKnownMarketplaces");
                if (extra instanceof ObjectNode extraObj && extraObj.has(marketplaceName)) {
                    extraObj.remove(marketplaceName);
                    changed = true;
                }
                JsonNode plugins = root.get("enabledPlugins");
                if (plugins instanceof ObjectNode pluginsObj) {
                    List<String> toRemove = new ArrayList<>();
                    Iterator<String> names = pluginsObj.fieldNames();
                    while (names.hasNext()) {
                        String key = names.next();
                        if (Strings.CS.endsWith(key, suffix)) {
                            toRemove.add(key);
                        }
                    }
                    toRemove.forEach(pluginsObj::remove);
                    changed = changed || !toRemove.isEmpty();
                }
                return changed;
            });
        }
    }

    public MarketplaceSourceInfo marketplaceSourceInfo(String marketplaceName) {
        return new MarketplaceSourceInfo(hasExtra(userPath(), marketplaceName),
            hasExtra(projectPath(), marketplaceName), hasExtra(localPath(), marketplaceName),
            hasExtraPolicy(marketplaceName));
    }

    private boolean hasExtra(Path tier, String marketplaceName) {
        JsonNode extra = readKey(tier, "extraKnownMarketplaces");
        return extra != null && extra.isObject() && extra.has(marketplaceName);
    }

    // ── pluginConfigs ─────────────────────────────────────────────────────────

    /** Reads {@code pluginConfigs[pluginId]} from the user tier ({@code null} when absent). */
    public JsonNode pluginConfig(String pluginId) {
        JsonNode configs = readKey(userPath(), "pluginConfigs");
        return configs != null && configs.isObject() ? configs.get(pluginId) : null;
    }

    /** Writes {@code pluginConfigs[pluginId]} in the user tier. */
    public void setPluginConfig(String pluginId, JsonNode config) {
        mutate(userPath(), root -> {
            ObjectNode configs = objectChild(root, "pluginConfigs");
            configs.set(pluginId, config);
            root.set("pluginConfigs", configs);
        });
    }

    /** Removes {@code pluginConfigs[pluginId]} after the plugin is fully uninstalled. */
    public void removePluginConfig(String pluginId) {
        mutate(userPath(), root -> {
            JsonNode configs = root.get("pluginConfigs");
            if (configs instanceof ObjectNode object) {
                object.remove(pluginId);
                if (object.isEmpty()) root.remove("pluginConfigs");
            }
        });
    }

    // ── policy tier ───────────────────────────────────────────────────────────

    /** {@code blockedMarketplaces} from managed settings, or {@code null} when unset. */
    public List<MarketplaceSource> blockedMarketplaces() {
        return readSourceList("blockedMarketplaces");
    }

    /** {@code strictKnownMarketplaces} allowlist from managed settings, or {@code null} when unset. */
    public List<MarketplaceSource> strictKnownMarketplaces() {
        return readSourceList("strictKnownMarketplaces");
    }

    private List<MarketplaceSource> readSourceList(String key) {
        JsonNode node = policyKey(key);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<MarketplaceSource> sources = new ArrayList<>();
        for (JsonNode item : node) {
            try {
                sources.add(JsonUtils.getMapper().treeToValue(item, MarketplaceSource.class));
            } catch (Exception e) {
                LOG.warn("Skipping unparseable {} entry: {}", key, e.getMessage());
            }
        }
        return List.copyOf(sources);
    }

    /**
     * Reads policy keys through the selected policy source in production.
     */
    private JsonNode policyKey(String key) {
        if (canonicalCwd != null) {
            ObjectNode policy = SettingsSources.settingsForSource(
                RuleSource.POLICY_SETTINGS, currentSettingsCwd());
            return policy.get(key);
        }
        return readKey(policySettingsPath, key);
    }

    private boolean hasExtraPolicy(String marketplaceName) {
        JsonNode extra = canonicalCwd == null
            ? readKey(policySettingsPath, "extraKnownMarketplaces")
            : SettingsSources.settingsForSource(
                RuleSource.POLICY_SETTINGS, currentSettingsCwd())
                    .get("extraKnownMarketplaces");
        return extra != null && extra.isObject() && extra.has(marketplaceName);
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private List<Path> editableTiers() {
        return List.of(userPath(), projectPath(), localPath());
    }

    private Path pathForScope(PluginScope scope) {
        return switch (scope) {
            case USER -> userPath();
            case PROJECT -> projectPath();
            case LOCAL -> localPath();

            case MANAGED -> throw new PluginOperationException("Cannot install plugins to managed scope");
        };
    }

    private JsonNode readKey(Path settingsPath, String key) {
        if (!Files.isReadable(settingsPath)) {
            return null;
        }
        try {
            JsonNode root = JsonUtils.readJson(settingsPath);
            return root == null ? null : root.get(key);
        } catch (Exception e) {
            LOG.warn("Failed to read {} from {}: {}", key, settingsPath, e.getMessage());
            return null;
        }
    }

    private void mutate(Path settingsPath, Consumer<ObjectNode> edit) {
        try {
            SettingsFileStore.mutate(settingsPath, edit);
            scheduleLocalSettingsGitignore(settingsPath);
        } catch (Exception e) {
            throw new PluginOperationException(
                "Failed to update settings: " + e.getMessage(), e);
        }
    }

    /** Read-modify-write that skips missing files and only writes when the editor reports a change. */
    private void mutateIfExists(Path settingsPath, Predicate<ObjectNode> edit) {
        if (!Files.isReadable(settingsPath)) {
            return;
        }
        try {
            if (SettingsFileStore.mutateIfExists(settingsPath, edit)) {
                scheduleLocalSettingsGitignore(settingsPath);
            }
        } catch (Exception e) {

            LOG.warn("Failed to clean up settings at {}: {}", settingsPath, e.getMessage());
        }
    }


    private void scheduleLocalSettingsGitignore(Path settingsPath) {
        Path localPath = localPath();
        if (canonicalCwd != null && settingsPath.toAbsolutePath().normalize()
                .equals(localPath.toAbsolutePath().normalize())) {
            GitignoreHelper.addFileGlobRuleToGitignore(
                ".claude/settings.local.json", currentSettingsCwd());
        }
    }


    private Path userPath() {
        return canonicalCwd == null ? userSettingsPath : SettingsPaths.userSettingsPath();
    }

    private Path projectPath() {
        return canonicalCwd == null
            ? projectSettingsPath : SettingsPaths.sessionProjectSettingsPath(canonicalCwd);
    }

    private Path localPath() {
        return canonicalCwd == null
            ? localSettingsPath : SettingsPaths.sessionLocalSettingsPath(canonicalCwd);
    }

    private String currentSettingsCwd() {
        return SettingsPaths.sessionProjectRoot(canonicalCwd).toString();
    }

    private static ObjectNode objectChild(ObjectNode root, String key) {
        JsonNode child = root.get(key);
        return child != null && child.isObject()
            ? (ObjectNode) child
            : JsonUtils.getMapper().createObjectNode();
    }
}
