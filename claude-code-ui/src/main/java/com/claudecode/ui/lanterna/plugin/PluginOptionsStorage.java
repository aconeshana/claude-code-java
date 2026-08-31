package com.claudecode.ui.lanterna.plugin;

import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigOption;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure option-schema filtering used by the plugin option flow.
 */
final class PluginOptionsStorage {

    private PluginOptionsStorage() {}

    static LinkedHashMap<String, ConfigOption> unconfigured(
            Map<String, ConfigOption> schema, Map<String, Object> saved) {
        LinkedHashMap<String, ConfigOption> remaining = new LinkedHashMap<>();
        schema.forEach((key, option) -> {
            if (!saved.containsKey(key)) remaining.put(key, option);
        });
        return remaining;
    }
}
