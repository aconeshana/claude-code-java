package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects marketplace-delisted plugins, removes editable installations, and records a user-visible
 * flagged notification.
 */
final class PluginDelistingService {

    private static final Logger LOG = LoggerFactory.getLogger(PluginDelistingService.class);

    private final MarketplaceManager marketplaces;
    private final PluginInstaller installer;
    private final InstalledPluginsStore installedStore;
    private final FlaggedPluginStore flaggedStore;

    PluginDelistingService(MarketplaceManager marketplaces, PluginInstaller installer,
                           FlaggedPluginStore flaggedStore) {
        this.marketplaces = marketplaces;
        this.installer = installer;
        this.installedStore = marketplaces.installedStore();
        this.flaggedStore = flaggedStore;
    }

    List<String> reconcile() {
        InstalledPlugins installed = installedStore.load();
        Set<String> alreadyFlagged = new HashSet<>(flaggedStore.load().keySet());
        List<String> newlyFlagged = new ArrayList<>();
        for (String marketplaceName : marketplaces.list().keySet()) {
            try {
                MarketplaceManifest manifest = marketplaces.get(marketplaceName);
                if (!Boolean.TRUE.equals(manifest.forceRemoveDeletedPlugins())) continue;
                Set<String> available = new HashSet<>();
                if (manifest.plugins() != null) {
                    manifest.plugins().forEach(entry -> available.add(entry.name()));
                }
                String suffix = "@" + marketplaceName;
                for (Map.Entry<String, List<InstalledPlugins.InstallationEntry>> plugin
                        : installed.plugins().entrySet()) {
                    String pluginId = plugin.getKey();
                    if (!Strings.CS.endsWith(pluginId, suffix) || alreadyFlagged.contains(pluginId)) continue;
                    String pluginName = pluginId.substring(0, pluginId.length() - suffix.length());
                    if (available.contains(pluginName)) continue;
                    boolean editable = plugin.getValue().stream()
                        .anyMatch(entry -> entry.scope() != PluginScope.MANAGED);
                    if (!editable) continue;
                    for (InstalledPlugins.InstallationEntry installation : plugin.getValue()) {
                        if (installation.scope() == PluginScope.MANAGED) continue;
                        try {
                            installer.uninstall(pluginId, installation.scope(), true);
                        } catch (Exception e) {
                            LOG.warn("Failed to auto-uninstall delisted plugin {} from {}: {}",
                                pluginId, installation.scope(), e.getMessage());
                        }
                    }
                    flaggedStore.add(pluginId);
                    alreadyFlagged.add(pluginId);
                    newlyFlagged.add(pluginId);
                }
            } catch (Exception e) {
                LOG.warn("Failed to check delisted plugins in {}: {}",
                    marketplaceName, e.getMessage());
            }
        }
        return List.copyOf(newlyFlagged);
    }
}
