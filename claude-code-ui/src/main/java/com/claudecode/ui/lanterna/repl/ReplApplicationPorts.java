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
    TaskBoardPort taskBoard
) {}
