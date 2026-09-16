package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.compact.CompactWarningProvider;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.session.ConversationResetPort;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.runtime.startup.StartupTrustPort;
import com.claudecode.runtime.statusline.StatusLinePort;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.runtime.turn.TurnAwakeGuard;

/**
 * Application use-case ports consumed by one interactive REPL session.
 *
 * <p>Optional ports are normalized to their inert implementation here, so every consumer can
 * treat each component as non-null. Ports without an inert implementation ({@code sessions},
 * {@code hooks}, {@code sessionLifecycle}, {@code doctor}, {@code plugins}) are passed through
 * unchanged.
 */
public record ReplApplicationPorts(
    ReplCommandUiBridge commandUi,
    InteractiveSessionPort sessions,
    HookConfigurationPort hooks,
    McpManagementPort mcp,
    CompactWarningProvider compactWarnings,
    SessionLifecycle sessionLifecycle,
    ConversationResetPort conversationReset,
    MemoryCatalog memory,
    OutputStyleCatalog outputStyles,
    DoctorPort doctor,
    PluginMarketplacePort plugins,
    StatusLinePort statusLine,
    StartupTrustPort startupTrust,
    ShutdownPort shutdown,
    TurnAwakeGuard awakeGuard,
    TaskBoardPort taskBoard,
    ProjectCatalogPort projects
) {
    public ReplApplicationPorts {
        if (commandUi == null) commandUi = new ReplCommandUiBridge();
        if (mcp == null) mcp = McpManagementPort.none();
        if (compactWarnings == null) compactWarnings = CompactWarningProvider.none();
        if (conversationReset == null) conversationReset = ConversationResetPort.noop();
        if (memory == null) memory = MemoryCatalog.empty();
        if (outputStyles == null) outputStyles = OutputStyleCatalog.builtIns();
        if (statusLine == null) statusLine = StatusLinePort.disabled();
        if (startupTrust == null) startupTrust = StartupTrustPort.trustAll();
        if (shutdown == null) shutdown = ShutdownPort.noop();
        if (awakeGuard == null) awakeGuard = TurnAwakeGuard.noop();
        if (taskBoard == null) taskBoard = TaskBoardPort.none();
        if (projects == null) projects = ProjectCatalogPort.none();
    }
}
