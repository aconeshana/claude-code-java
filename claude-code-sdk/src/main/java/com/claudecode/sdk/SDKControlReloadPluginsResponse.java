package com.claudecode.sdk;

import java.util.List;
import java.util.Objects;

/** Refreshed SDK-visible components after reloading plugins. */
public record SDKControlReloadPluginsResponse(List<SlashCommand> commands, List<AgentInfo> agents,
                                              List<PluginInfo> plugins,
                                              List<McpServerStatus> mcpServers, int errorCount) {
    public SDKControlReloadPluginsResponse {
        commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
        agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    }

    public record PluginInfo(String name, String path, String source) {}
}
