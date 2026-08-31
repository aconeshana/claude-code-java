package com.claudecode.commands.impl.integration;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.plugins.PluginRuntimePort;

/**
 * {@code /plugin} — manage Claude Code plugins (marketplaces, install, enable/disable, validate).
 */
@SlashCommand(
    name = "plugin",
    description = "Manage Claude Code plugins",
    aliases = {"plugins", "marketplace"}
)
public class PluginCommand implements AnnotatedCommand {

    @Override
    public boolean isImmediate() { return true; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        if (context.presentation().pluginDialogLauncher() != null) {
            context.presentation().pluginDialogLauncher().accept(args != null ? args.trim() : "");
            return CommandResult.skip();
        }
        return CommandResult.of(renderTextSummary(context.application().plugins()));
    }

    /** Headless fallback — active plugin runtime counters. */
    static String renderTextSummary(PluginRuntimePort runtime) {
        if (runtime == null) {
            return "Plugin system is not initialized in this session.";
        }
        PluginRuntimePort.Summary summary = runtime.summary();
        return "Plugins: " + summary.commandCount() + " commands, "
            + summary.agentCount() + " agents, "
            + summary.skillCount() + " skills, "
            + summary.mcpCount() + " MCP servers loaded"
            + (summary.errorCount() == 0
                ? "" : " (" + summary.errorCount() + " errors)")
            + "\nUse the interactive REPL for marketplace management.";
    }
}
