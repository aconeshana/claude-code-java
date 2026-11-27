package com.claudecode.gateway;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Consumer-owned boundary for managing plugin marketplaces, installed plugins,
 * user configuration options, and live runtime refreshes from the Web UI.
 */
public interface GatewayPluginsPort {

    /** Live plugin runtime counters projected into {@code GET /api/plugins}. */
    record RuntimeSummary(
            int commandCount,
            int agentCount,
            int skillCount,
            int mcpCount,
            int errorCount) {}

    /** Embedded UI panel metadata declared by an enabled plugin's {@code modelProviders} sidecar. */
    record UiPanel(
            String pluginId,
            String providerId,
            String title,
            String description,
            String url,
            String status,
            String error,
            boolean settingsTab) {
        public UiPanel(
                String pluginId, String providerId, String title, String description,
                String url, String status, String error) {
            this(pluginId, providerId, title, description, url, status, error, false);
        }
    }

    /** Internal reverse-proxy target for {@code /api/plugins/{pluginId}/ui/**}. */
    record UiProxyTarget(
            String pluginId,
            String baseUrl,
            String uiPath,
            String token,
            String status,
            String error) {}

    default PluginMarketplacePort marketplace() {
        return null;
    }

    default RuntimeSummary summary() {
        return new RuntimeSummary(0, 0, 0, 0, 0);
    }

    /** Re-reads enabled plugins from disk and injects updated skills/commands/agents/MCP servers. */
    default RuntimeSummary refreshRuntime() {
        return summary();
    }

    /** Lists embedded UI panels declared by enabled plugins. */
    default List<UiPanel> uiPanels() {
        return List.of();
    }

    /** Resolves the loopback reverse-proxy target for {@code pluginId}'s UI panel. */
    default Optional<UiProxyTarget> uiProxyTarget(String pluginId) {
        return Optional.empty();
    }

    /** Invalidates cached dynamic model lists after plugin-side account/plugin mutations. */
    default void invalidateProviderModels() {}

    /** Helper returning the current options map for every installed plugin keyed by {@code pluginId}. */
    default Map<String, Map<String, Object>> allPluginOptions() {
        PluginMarketplacePort port = marketplace();
        if (port == null) return Map.of();
        LinkedHashMap<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (PluginMarketplacePort.InstalledPlugin plugin : port.installedPlugins()) {
            out.put(plugin.pluginId(), port.loadOptions(plugin.pluginId()));
        }
        return Map.copyOf(out);
    }

    /** Helper returning discovered catalog plugins per marketplace name (best-effort). */
    default Map<String, List<PluginMarketplacePort.PluginEntry>> catalogByMarketplace() {
        PluginMarketplacePort port = marketplace();
        if (port == null) return Map.of();
        LinkedHashMap<String, List<PluginMarketplacePort.PluginEntry>> out =
            new LinkedHashMap<>();
        for (String name : port.marketplaces().keySet()) {
            try {
                out.put(name, port.marketplace(name).plugins());
            } catch (RuntimeException _) {
                out.put(name, List.of());
            }
        }
        return Map.copyOf(out);
    }
}
