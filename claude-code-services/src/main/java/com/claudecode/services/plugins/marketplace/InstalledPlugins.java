package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory model of in V2 format: each plugin ID ({@code plugin@marketplace}) maps to an ARRAY of
 * installations, one per scope(+projectPath).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstalledPlugins(int version, Map<String, List<InstallationEntry>> plugins) {

    public static final int CURRENT_VERSION = 2;

    public InstalledPlugins(int version, Map<String, List<InstallationEntry>> plugins) {
        this.version = version;
        Map<String, List<InstallationEntry>> copy = new LinkedHashMap<>();
        if (plugins != null) {
            plugins.forEach((id, entries) -> copy.put(id, List.copyOf(entries)));
        }
        this.plugins = Collections.unmodifiableMap(copy);
    }

    /** One installation of a plugin at a specific scope. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstallationEntry(
        PluginScope scope,
        String projectPath,
        String installPath,
        String version,
        String installedAt,
        String lastUpdated,
        String gitCommitSha) {}

    public static InstalledPlugins empty() {
        return new InstalledPlugins(CURRENT_VERSION, Map.of());
    }

    public List<InstallationEntry> installationsOf(String pluginId) {
        return plugins.getOrDefault(pluginId, List.of());
    }

    /**
     * Returns a copy with the entry upserted at its scope(+projectPath) — an existing entry for the
     * same scope and projectPath is replaced, others are preserved.
     */
    public InstalledPlugins withInstallation(String pluginId, InstallationEntry entry) {
        List<InstallationEntry> updated = new ArrayList<>(installationsOf(pluginId));
        int existing = indexOfScope(updated, entry.scope(), entry.projectPath());
        if (existing >= 0) {
            updated.set(existing, entry);
        } else {
            updated.add(entry);
        }
        Map<String, List<InstallationEntry>> next = new LinkedHashMap<>(plugins);
        next.put(pluginId, List.copyOf(updated));
        return new InstalledPlugins(version, next);
    }

    /**
     * Returns a copy without the installation at the given scope(+projectPath); the plugin key
     * disappears entirely when its last installation is removed.
     */
    public InstalledPlugins withoutInstallation(String pluginId, PluginScope scope, String projectPath) {
        List<InstallationEntry> remaining = installationsOf(pluginId).stream()
            .filter(e -> !(e.scope() == scope && Objects.equals(e.projectPath(), projectPath)))
            .toList();
        Map<String, List<InstallationEntry>> next = new LinkedHashMap<>(plugins);
        if (remaining.isEmpty()) {
            next.remove(pluginId);
        } else {
            next.put(pluginId, remaining);
        }
        return new InstalledPlugins(version, next);
    }

    /** Returns a copy without any installation of {@code pluginId} (all scopes). */
    public InstalledPlugins withoutPlugin(String pluginId) {
        if (!plugins.containsKey(pluginId)) {
            return this;
        }
        Map<String, List<InstallationEntry>> next = new LinkedHashMap<>(plugins);
        next.remove(pluginId);
        return new InstalledPlugins(version, next);
    }

    /**
     * Returns a copy without every plugin belonging to {@code marketplaceName} (IDs ending {@code
     * @marketplaceName}).
     */
    public InstalledPlugins withoutMarketplace(String marketplaceName) {
        String suffix = "@" + marketplaceName;
        Map<String, List<InstallationEntry>> next = new LinkedHashMap<>();
        plugins.forEach((id, entries) -> {
            if (!Strings.CS.endsWith(id, suffix)) {
                next.put(id, entries);
            }
        });
        return new InstalledPlugins(version, next);
    }

    private static int indexOfScope(List<InstallationEntry> entries, PluginScope scope, String projectPath) {
        for (int i = 0; i < entries.size(); i++) {
            InstallationEntry e = entries.get(i);
            if (e.scope() == scope && Objects.equals(e.projectPath(), projectPath)) {
                return i;
            }
        }
        return -1;
    }
}
