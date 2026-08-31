package com.claudecode.ui.lanterna.plugin;

import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.mcp.McpManagementPort;

import java.util.concurrent.Executor;

/**
 * Bundle of everything {@link PluginSettingsPanel} needs from the application layer.
 */
public record PluginPanelServices(
    PluginMarketplacePort plugins,
    Executor background,
    McpManagementPort mcp) {

    public PluginPanelServices(PluginMarketplacePort plugins, Executor background) {
        this(plugins, background, McpManagementPort.none());
    }
}
