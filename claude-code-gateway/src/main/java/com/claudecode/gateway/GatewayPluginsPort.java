package com.claudecode.gateway;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
