package com.claudecode.commands;

import com.claudecode.commands.dream.DreamPort;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.commands.permissions.PermissionCommandPort;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.settings.SettingsManagementPort;
import java.util.function.Supplier;

/**
 * Application-runtime capabilities consumed by slash commands.
 *
 * <p>This is the inward-facing boundary between the command catalog and the
 * host application. Commands own small use-case ports; the CLI composition
 * root supplies adapters backed by services, persistence, MCP and UI runtime
 * objects. The aggregate prevents every host feature from becoming another
 * top-level {@link CommandContext} component without weakening the individual
 * feature boundaries.
 *
 * <ul>
 *   <li>
 *        plugin runtime
 *       inspection and refresh capability.</li>
 *   <li>
 *       — diagnostic collection capability.</li>
 *   <li>report generation capability.</li>
 *   <li>manual memory-consolidation capability.</li>
 * </ul>
 */
public record CommandApplicationPorts(
    DoctorPort doctor,
    DreamPort dream,
    PluginRuntimePort plugins,
    Supplier<InsightsPort> insights,
    SettingsManagementPort settings,
    McpManagementPort mcp,
    PermissionCommandPort permissions,
    SessionCommandPort sessions,
    ToolingCommandPorts tooling
) {
    public static CommandApplicationPorts empty() {
        return new CommandApplicationPorts(
            null, null, null, null, SettingsManagementPort.none(),
            McpManagementPort.none(), PermissionCommandPort.none(), SessionCommandPort.none(),
            ToolingCommandPorts.none());
    }
}
