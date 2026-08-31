package com.claudecode.services.plugins.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One assistant-mode channel declared by a plugin manifest.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginChannel(String server, String displayName,
                            Map<String, UserConfigOption> userConfig) {
    public PluginChannel {
        userConfig = userConfig == null ? Map.of() : Map.copyOf(userConfig);
    }
}
