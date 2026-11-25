package com.claudecode.commands.impl.integration;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.runtime.mcp.McpManagementPort;

import java.util.List;
import java.util.Locale;

/**
 * /mcp — manage MCP servers through the application MCP management port.
 *
 * <ul>
 *   <li>metadata and argument hint.</li>
 *   <li>settings surface and routing for
 *       reconnect/enable/disable; omitted toggle target means all.</li>
 *   <li>reconnect messages.</li>
 * </ul>
 */
@SlashCommand(name = "mcp", description = "Manage MCP servers")
public class McpCommand implements AnnotatedCommand {

    private final McpManagementPort mcp;
    private Runnable dialogLauncher;

    public McpCommand(McpManagementPort mcp) {
        if (mcp == null) throw new IllegalArgumentException("mcp must not be null");
        this.mcp = mcp;
    }

    @Override public boolean isImmediate() { return true; }

    /** {@code reconnect} / {@code enable} / {@code disable} connect to MCP servers synchronously. */
    @Override public boolean isLongRunning() { return true; }

    @Override public String argumentHint() { return "[enable|disable [server-name]]"; }

    public void setDialogLauncher(Runnable launcher) { dialogLauncher = launcher; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String input = args != null ? args.trim() : "";
        if (input.isEmpty()) return openSettingsOrList();
        String[] parts = input.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String target = parts.length > 1 ? parts[1].trim() : "";
        if (Strings.CS.equals(action, "no-redirect")) return openSettingsOrList();
        if (Strings.CS.equals(action, "reconnect") && !target.isEmpty()) {
            return reconnectServer(target);
        }
        if (Strings.CS.equals(action, "enable") || Strings.CS.equals(action, "disable")) {
            boolean enabling = Strings.CS.equals(action, "enable");
            SessionCapabilityPort capabilities = context != null && context.application() != null
                ? context.application().capabilities() : null;
            if (capabilities != null) {
                return toggleViaCapabilities(context, capabilities, target, enabling);
            }
            return toggleServers(target.isEmpty() ? "all" : target, enabling);
        }
        return openSettingsOrList();
    }

    private CommandResult openSettingsOrList() {
        if (dialogLauncher != null) {
            dialogLauncher.run();
            return CommandResult.skip();
        }
        return listServers(mcp.servers());
    }

    private static CommandResult listServers(List<McpManagementPort.Server> servers) {
        StringBuilder sb = new StringBuilder("MCP Servers\n==========\n\n");
        if (servers.isEmpty()) {
            sb.append("No MCP servers configured. Please run /doctor if this is unexpected. ")
                .append("Otherwise, run `claude mcp --help` or visit ")
                .append("https://code.claude.com/docs/en/mcp to learn more.\n");
            return CommandResult.of(sb.toString());
        }
        for (McpManagementPort.Server server : servers) {
            String disabled = server.disabled() ? " [DISABLED]" : "";
            String connected = server.connected() ? " [CONNECTED]" : "";
            sb.append(String.format("  %s%s%s%n", server.name(), disabled, connected));
            sb.append(String.format("    command: %s%n", server.command()));
            if (!server.args().isEmpty()) {
                sb.append(String.format("    args: %s%n", String.join(" ", server.args())));
            }
            if (!server.environment().isEmpty()) {
                sb.append(String.format("    env: %s keys%n", server.environment().size()));
            }
            sb.append(String.format("    transport: %s%n%n", server.transport()));
        }
        return CommandResult.of(sb.toString());
    }

    CommandResult reconnectServer(String serverName) {
        McpManagementPort.Server server = find(serverName);
        if (server == null) return CommandResult.of("MCP server \"" + serverName + "\" not found");
        if (server.disabled()) return CommandResult.of("Failed to reconnect to " + serverName);
        try {
            mcp.execute(McpManagementPort.Action.RECONNECT, serverName);
            return CommandResult.of("Successfully reconnected to " + serverName);
        } catch (Exception e) {
            return CommandResult.of("Error: " + e.getMessage());
        }
    }

    CommandResult toggleServers(String target, boolean enabling) {
        List<McpManagementPort.Server> servers = mcp.servers();
        List<McpManagementPort.Server> candidates = Strings.CS.equals("all", target)
            ? servers.stream().filter(server -> server.disabled() == enabling).toList()
            : servers.stream().filter(server -> server.name().equals(target))
                .filter(server -> server.disabled() == enabling).toList();
        if (candidates.isEmpty()) {
            if (Strings.CS.equals("all", target)) {
                return CommandResult.of("All MCP servers are already "
                    + (enabling ? "enabled" : "disabled"));
            }
            if (servers.stream().noneMatch(server -> server.name().equals(target))) {
                return CommandResult.of("MCP server \"" + target + "\" not found");
            }
            return CommandResult.of("MCP server \"" + target + "\" is already "
                + (enabling ? "enabled" : "disabled"));
        }
        int changed = 0;
        for (McpManagementPort.Server server : candidates) {
            try {
                mcp.execute(enabling ? McpManagementPort.Action.ENABLE
                    : McpManagementPort.Action.DISABLE, server.name());
                changed++;
            } catch (Exception e) {
                return CommandResult.of("Failed to " + (enabling ? "enable" : "disable")
                    + " MCP server \"" + server.name() + "\": " + e.getMessage());
            }
        }
        if (Strings.CS.equals("all", target)) {
            return CommandResult.of((enabling ? "Enabled " : "Disabled ")
                + changed + " MCP server(s)");
        }
        return CommandResult.of("MCP server \"" + target + "\" "
            + (enabling ? "enabled" : "disabled"));
    }

    private CommandResult toggleViaCapabilities(
            CommandContext context, SessionCapabilityPort capabilities,
            String rawTarget, boolean enabling) {
        SessionCapabilityPort.Scope scope = SessionCapabilityPort.Scope.SESSION;
        String cleaned = rawTarget == null ? "" : rawTarget.trim();
        String[] tokens = cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
        String target = "";
        for (String token : tokens) {
            if (Strings.CS.equals("--default", token) || Strings.CS.equals("--persist", token)) {
                scope = SessionCapabilityPort.Scope.DEFAULT;
            } else if (Strings.CS.equals("--session", token)) {
                scope = SessionCapabilityPort.Scope.SESSION;
            } else if (target.isEmpty()) {
                target = token;
            }
        }
        if (target.isEmpty()) target = "all";
        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : "";

        // Support per-MCP-tool toggle via canonical mcp__<server>__<tool> name.
        if (Strings.CS.startsWith(target, "mcp__")) {
            var outcome = capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
                sessionId, SessionCapabilityPort.Kind.MCP_TOOL, target, null, enabling, scope));
            return CommandResult.of(outcome.message());
        }

        final SessionCapabilityPort.Scope targetScope = scope;
        var snap = capabilities.snapshot(sessionId, context.session().model());
        List<SessionCapabilityPort.McpServerItem> servers = snap.mcpServers();
        if (Strings.CS.equals("all", target)) {
            List<SessionCapabilityPort.McpServerItem> candidates = servers.stream()
                .filter(s -> (targetScope == SessionCapabilityPort.Scope.DEFAULT
                    ? s.defaultEnabled() : s.sessionEnabled()) != enabling)
                .toList();
            if (candidates.isEmpty()) {
                return CommandResult.of("All MCP servers are already "
                    + (enabling ? "enabled" : "disabled") + " (" + scope.wire() + ")");
            }
            for (var s : candidates) {
                capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
                    sessionId, SessionCapabilityPort.Kind.MCP_SERVER, s.name(), null, enabling, scope));
            }
            return CommandResult.of((enabling ? "Enabled " : "Disabled ")
                + candidates.size() + " MCP server(s) (" + scope.wire() + ")");
        }

        final String serverName = target;
        var match = servers.stream().filter(s -> s.name().equals(serverName)).findFirst().orElse(null);
        if (match == null) {
            return CommandResult.of("MCP server \"" + serverName + "\" not found");
        }
        var outcome = capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
            sessionId, SessionCapabilityPort.Kind.MCP_SERVER, serverName, null, enabling, scope));
        return CommandResult.of(outcome.message());
    }

    private McpManagementPort.Server find(String serverName) {
        return mcp.servers().stream().filter(server -> server.name().equals(serverName))
            .findFirst().orElse(null);
    }
}
