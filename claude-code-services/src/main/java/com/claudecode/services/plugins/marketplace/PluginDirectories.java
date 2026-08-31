package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.SubprocessEnvironment;

import java.nio.file.Path;

/**
 * Path layout of the plugins root directory.
 */
public final class PluginDirectories {

    private final Path root;

    public PluginDirectories(Path root) {
        this.root = root;
    }

    /** Default root: {@code $CLAUDE_CODE_PLUGIN_CACHE_DIR} or {@code ~/.claude/plugins}. */
    public static PluginDirectories standard() {
        String override = SubprocessEnvironment.get(
            "CLAUDE_CODE_PLUGIN_CACHE_DIR");
        if (StringUtils.isNotBlank(override)) {
            String expanded = Strings.CS.startsWith(override, "~")
                ? System.getProperty("user.home") + override.substring(1)
                : override;
            return new PluginDirectories(Path.of(expanded));
        }
        return new PluginDirectories(ClaudePaths.CLAUDE_HOME.resolve("plugins"));
    }

    public Path root() {
        return root;
    }

    public Path knownMarketplacesFile() {
        return root.resolve("known_marketplaces.json");
    }

    public Path installedPluginsFile() {
        return root.resolve("installed_plugins.json");
    }

    public Path flaggedPluginsFile() {
        return root.resolve("flagged-plugins.json");
    }


    public Path installCountsCacheFile() {
        return root.resolve("install-counts-cache.json");
    }

    public Path marketplacesCacheDir() {
        return root.resolve("marketplaces");
    }

    public Path pluginCacheDir() {
        return root.resolve("cache");
    }

    /**
     * Versioned, immutable install snapshot:
     * {@code cache/<marketplace>/<plugin>/<version>/}.
     */
    public Path versionedCachePath(String pluginId, String version) {
        PluginId id = PluginId.parse(pluginId);
        return pluginCacheDir()
            .resolve(sanitizeComponent(id.marketplace() == null ? "unknown" : id.marketplace()))
            .resolve(sanitizeComponent(id.name()))
            .resolve(sanitizeVersion(version));
    }

    /**
     * Mutable per-plugin data directory ({@code ${CLAUDE_PLUGIN_DATA}}), which survives updates: {@code
     * data/<sanitized-plugin-id>/}.
     */
    public Path pluginDataDir(String pluginId) {
        return root.resolve("data").resolve(sanitizeComponent(pluginId));
    }


    static String sanitizeComponent(String value) {
        return value.replaceAll("[^a-zA-Z0-9\\-_]", "-");
    }

    /** Versions additionally keep dots (semver). */
    static String sanitizeVersion(String version) {
        return version.replaceAll("[^a-zA-Z0-9\\-_.]", "-");
    }

    /** Parsed {@code plugin@marketplace} identifier (marketplace may be absent). */
    public record PluginId(String name, String marketplace) {


        public static PluginId parse(String pluginId) {
            int at = pluginId.indexOf('@');
            if (at < 0) {
                return new PluginId(pluginId, null);
            }
            String name = pluginId.substring(0, at);
            String rest = pluginId.substring(at + 1);
            int nextAt = rest.indexOf('@');
            return new PluginId(name, nextAt < 0 ? rest : rest.substring(0, nextAt));
        }

        public String id() {
            return marketplace == null ? name : name + "@" + marketplace;
        }
    }
}
