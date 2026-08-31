package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfigWriter;
import com.claudecode.mcp.McpPromptInfo;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.mcp.MCPTool;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Composition-root adapter joining MCP infrastructure to presentation-neutral management actions.
 */
final class CliMcpManagementAdapter implements McpManagementPort {

    private final Path cwd;
    private final Supplier<McpClientRuntime> runtime;
    private final ToolRegistry tools;
    private final CommandRegistry commands;
    private final PluginMarketplacePort plugins;
    private final McpOAuthProvider oauth;

    CliMcpManagementAdapter(Path cwd, Supplier<McpClientRuntime> runtime,
                            ToolRegistry tools, CommandRegistry commands,
                            PluginMarketplacePort plugins) {
        this(cwd, runtime, tools, commands, plugins, new McpOAuthProvider());
    }

    CliMcpManagementAdapter(Path cwd, Supplier<McpClientRuntime> runtime,
                            ToolRegistry tools, CommandRegistry commands,
                            PluginMarketplacePort plugins, McpOAuthProvider oauth) {
        this.cwd = cwd;
        this.runtime = runtime;
        this.tools = tools;
        this.commands = commands;
        this.plugins = plugins;
        this.oauth = oauth;
    }

    @Override
    public Snapshot snapshot() {
        McpConfig config = McpConfigLoader.loadConfig(cwd);
        Map<String, Server> result = new LinkedHashMap<>();
        McpClientRuntime live = runtime.get();
        Set<String> connected = live == null ? Set.of() : live.getConnectedServerIds();
        config.servers().forEach((name, server) -> {
            McpServerScope scope = config.scopeOf(name);
            result.put(name, view(server, scope.name().toLowerCase(Locale.ROOT),
                scopeOrder(scope), false, name, connected, configLocation(scope), true));
        });
        if (plugins != null) {
            for (PluginMarketplacePort.PluginMcpServer plugin : plugins.pluginMcpServers()) {
                McpServerConfig server = new McpServerConfig(plugin.name(), plugin.command(),
                    plugin.args(), plugin.env(), plugin.disabled(), plugin.transportType(),
                    plugin.url(), plugin.headers());
                String[] parts = plugin.name().split(":", 3);
                String display = parts.length >= 3 ? parts[2] : plugin.name();
                result.put(plugin.name(), view(server, plugin.scope().wire(),
                    pluginScopeOrder(plugin.scope()), true, display, connected,
                    "Plugin-provided", true));
            }
        }
        List<Server> servers = result.values().stream()
            .sorted(Comparator.comparingInt(Server::scopeOrder).thenComparing(Server::displayName))
            .toList();
        return new Snapshot(servers, config.warnings());
    }

    @Override
    public List<Server> servers() {
        return snapshot().servers();
    }

    @Override
    public List<Tool> tools(String serverName) {
        McpClientRuntime live = runtime.get();
        if (live == null) return List.of();
        return live.listToolsForServer(serverName).stream()
            .map(tool -> new Tool(tool.name(), tool.description(), tool.inputSchema())).toList();
    }

    @Override
    public String execute(Action action, String serverName) {
        return switch (action) {
            case ENABLE -> setEnabled(serverName, true);
            case DISABLE -> setEnabled(serverName, false);
            case RECONNECT -> reconnect(serverName);
            case AUTHENTICATE -> authenticate(serverName);
            case CLEAR_AUTHENTICATION -> clearAuthentication(serverName);
        };
    }

    private String setEnabled(String serverName, boolean enabled) {
        Server server = requireServer(serverName);
        if (!server.manageable()) {
            throw new IllegalStateException("Dynamic MCP server '" + serverName
                + "' is managed by its host and cannot be enabled or disabled here");
        }
        McpServerScope scope = scope(server.scope());
        try {
            boolean changed = McpConfigWriter.setDisabled(scope, cwd, serverName, !enabled);
            if (!changed) return "MCP server '" + serverName + "' already "
                + (enabled ? "enabled" : "disabled") + ".";
            if (!enabled) {
                McpClientRuntime live = runtime.get();
                if (live != null) live.disconnect(serverName);
                unregister(serverName);
            }
            return (enabled ? "✓ Enabled" : "⛔ Disabled") + " MCP server '"
                + serverName + "' (" + server.scope() + ").";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to " + (enabled ? "enable" : "disable")
                + " '" + serverName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String reconnect(String serverName) {
        Server server = requireServer(serverName);
        if (server.needsAuthentication()) {
            throw new IllegalStateException(serverName
                + " requires authentication. Use the 'Authenticate' option.");
        }
        McpClientRuntime live = requireRuntime();
        live.connect(config(serverName));
        int toolCount = syncTools(serverName, live);
        int promptCount = syncPrompts(serverName, live);
        return "Successfully reconnected to " + serverName
            + (toolCount > 0 ? " (" + toolCount + " tool" + plural(toolCount)
                + " available to model)" : "")
            + (promptCount > 0 ? " · " + promptCount + " prompt" + plural(promptCount)
                + " available as slash commands" : "");
    }

    @Override
    public String authenticate(String serverName) {
        McpServerConfig config = config(serverName);
        if (!isRemote(config)) {
            throw new IllegalStateException("Cannot authenticate stdio server " + serverName);
        }
        McpOAuthProvider.AuthResult result = oauth.authenticate(config);
        String suffix;
        try {
            suffix = ". " + reconnect(serverName);
        } catch (Exception e) {
            suffix = ". Reconnect pending: " + e.getMessage();
        }
        return "✓ Authenticated to " + serverName
            + (result.expiresAt() > 0 ? " (expires in "
                + Math.max(1, (result.expiresAt() - System.currentTimeMillis()) / 60_000)
                + " min)" : "") + suffix;
    }

    @Override
    public String clearAuthentication(String serverName) {
        McpServerConfig config = config(serverName);
        if (!isRemote(config)) {
            throw new IllegalStateException("Cannot clear authentication for stdio server "
                + serverName);
        }
        oauth.clearAuth(config);
        McpClientRuntime live = runtime.get();
        if (live != null) live.disconnect(serverName);
        unregister(serverName);
        return "✓ Cleared stored authentication for " + serverName;
    }

    private Server requireServer(String name) {
        return servers().stream().filter(server -> server.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("MCP server '" + name + "' not found"));
    }

    private McpServerConfig config(String name) {
        McpConfig config = McpConfigLoader.loadConfig(cwd);
        McpServerConfig server = config.servers().get(name);
        if (server != null) return server;
        if (plugins != null) {
            for (PluginMarketplacePort.PluginMcpServer plugin : plugins.pluginMcpServers()) {
                if (plugin.name().equals(name)) {
                    return new McpServerConfig(plugin.name(), plugin.command(), plugin.args(),
                        plugin.env(), plugin.disabled(), plugin.transportType(), plugin.url(),
                        plugin.headers());
                }
            }
        }
        throw new IllegalArgumentException("MCP server '" + name + "' not found");
    }

    private McpClientRuntime requireRuntime() {
        McpClientRuntime live = runtime.get();
        if (live == null) throw new IllegalStateException("MCP client manager not wired");
        return live;
    }

    private int syncTools(String serverName, McpClientRuntime live) {
        if (tools == null) return 0;
        String prefix = "mcp__" + serverName + "__";
        tools.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
        List<McpToolInfo> discovered = live.listToolsForServer(serverName);
        discovered.forEach(info -> tools.register(new MCPTool(info, live)));
        return discovered.size();
    }

    private int syncPrompts(String serverName, McpClientRuntime live) {
        if (commands == null) return 0;
        String prefix = "mcp__" + serverName + "__";
        commands.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
        List<McpPromptInfo> prompts = live.listPromptsForServer(serverName);
        CliMcpPromptAdapter promptAdapter = new CliMcpPromptAdapter(live);
        prompts.forEach(info -> commands.register(new McpPromptCommand(
            CliMcpPromptAdapter.definition(info), promptAdapter)));
        return prompts.size();
    }

    private void unregister(String serverName) {
        String prefix = "mcp__" + serverName + "__";
        if (tools != null) tools.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
        if (commands != null) commands.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
    }

    private Server view(McpServerConfig config, String scope, int order, boolean pluginChild,
                        String display, Set<String> connected, String location,
                        boolean manageable) {
        AuthStatus auth = authStatus(config);
        Status status = config.disabled() ? Status.DISABLED
            : connected.contains(config.name()) ? Status.CONNECTED
            : isRemote(config) && auth == AuthStatus.NOT_AUTHENTICATED
                ? Status.NEEDS_AUTH : Status.DISCONNECTED;
        return new Server(config.name(), display, scope, order, status, auth,
            authDescription(auth), pluginChild, manageable,
            config.transportType(), config.command(), config.args(), config.env(), config.url(),
            config.headers().size(), location);
    }

    private AuthStatus authStatus(McpServerConfig config) {
        if (!isRemote(config)) return AuthStatus.NOT_APPLICABLE;
        if (hasAuthorizationHeader(config)) return AuthStatus.STATIC_HEADER;
        return oauth.hasStoredToken(config) ? AuthStatus.AUTHENTICATED
            : AuthStatus.NOT_AUTHENTICATED;
    }

    private static String authDescription(AuthStatus auth) {
        return switch (auth) {
            case NOT_APPLICABLE -> "";
            case STATIC_HEADER -> "auth: configured via Authorization header";
            case AUTHENTICATED -> "auth: ✓ authenticated";
            case NOT_AUTHENTICATED -> "auth: ✗ not authenticated";
        };
    }

    private static boolean isRemote(McpServerConfig config) {
        return Strings.CI.equals("http", config.transportType())
            || Strings.CI.equals("sse", config.transportType());
    }

    private static boolean hasAuthorizationHeader(McpServerConfig config) {
        return config.headers().keySet().stream()
            .anyMatch(key -> Strings.CI.equals("Authorization", key));
    }

    private String configLocation(McpServerScope scope) {
        return switch (scope) {
            case USER -> ClaudePaths.GLOBAL_JSON.toString();
            case PROJECT -> cwd.resolve(".mcp.json").toString();
            case LOCAL -> ClaudePaths.GLOBAL_JSON + " [project: " + cwd + "]";
            case ENTERPRISE -> ClaudePaths.managedRoot().resolve("managed-mcp.json").toString();
            case DYNAMIC -> "Dynamically configured";
        };
    }

    private static int scopeOrder(McpServerScope scope) {
        return switch (scope) {
            case PROJECT -> 0;
            case LOCAL -> 1;
            case USER -> 2;
            case ENTERPRISE -> 3;
            case DYNAMIC -> 5;
        };
    }

    private static int pluginScopeOrder(PluginMarketplacePort.Scope scope) {
        return switch (scope) {
            case PROJECT -> 0;
            case LOCAL -> 1;
            case USER -> 2;
            case MANAGED -> 4;
        };
    }

    private static McpServerScope scope(String scope) {
        return switch (scope) {
            case "project" -> McpServerScope.PROJECT;
            case "local" -> McpServerScope.LOCAL;
            case "user" -> McpServerScope.USER;
            case "enterprise", "managed" -> McpServerScope.ENTERPRISE;
            default -> McpServerScope.DYNAMIC;
        };
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }
}
